package fr.xyness.XCore.DAO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import fr.xyness.XCore.XCore;

/**
 * Collects column writes and sends them to the database in one statement per player.
 *
 * <p>A write used to be one UPDATE, one borrowed connection and one round trip, per column. An
 * addon setting five values on a player paid all of that five times, and with two dozen addons
 * writing to the same row the traffic is mostly overhead. Writes are gathered here instead and
 * flushed on a timer as {@code UPDATE players SET a = ?, b = ?, c = ? WHERE server_uuid = ?}.</p>
 *
 * <p>The caller's future completes when the row actually reaches the database, so nothing that
 * waited for a write before now waits for less. Later writes to the same column replace earlier
 * ones, which is what the caller would have got anyway.</p>
 *
 * <p>Anything still buffered is written on quit and on shutdown.</p>
 */
public class PlayerWriteBuffer {

    /** Pending column values per player, plus the futures waiting on them. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * Columns that must never wait in the buffer.
     *
     * <p>Balances are the case this exists for. They are also written straight to the database with
     * arithmetic the server does itself ({@code col = col - ?}, guarded on the funds being there),
     * and a buffered {@code SET col = ?} landing a moment later would overwrite whatever those
     * statements did in the meantime. A purchase would go through and then be refunded by a write
     * that was already in flight.</p>
     */
    private final java.util.Set<String> immediate = ConcurrentHashMap.newKeySet();

    private final XCore main;
    private final ExecutorService executor;

    /** Told which players were written, once the database has them. */
    private final Consumer<List<String>> onFlushed;

    /** Rows written since startup, and the statements it took. */
    private final java.util.concurrent.atomic.LongAdder columnsWritten = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder statements = new java.util.concurrent.atomic.LongAdder();

    /**
     * @param main      The plugin, for the data source and the log.
     * @param executor  The database executor.
     * @param onFlushed Called with the UUIDs whose row was just updated.
     */
    public PlayerWriteBuffer(XCore main, ExecutorService executor, Consumer<List<String>> onFlushed) {
        this.main = main;
        this.executor = executor;
        this.onFlushed = onFlushed;
    }

    /** One player's unwritten columns. */
    private static final class Pending {
        final Map<String, Object> columns = new LinkedHashMap<>();
        final List<CompletableFuture<Void>> waiting = new ArrayList<>();
    }

    /**
     * Declares a column that must be written as soon as it is set.
     *
     * @param column The column name, lower case.
     */
    public void writeThrough(String column) {
        if (column != null) immediate.add(column.toLowerCase());
    }

    /**
     * @param column The column name.
     * @return Whether that column skips the buffer.
     */
    public boolean isWriteThrough(String column) {
        return column != null && immediate.contains(column.toLowerCase());
    }

    /**
     * Queues one column value.
     *
     * @param serverUuid The player's server UUID.
     * @param column     The column name. Must already be validated by the caller.
     * @param value      The value to store.
     * @return A future completing once the value is in the database.
     */
    public CompletableFuture<Void> queue(String serverUuid, String column, Object value) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        pending.compute(serverUuid, (uuid, current) -> {
            Pending slot = current == null ? new Pending() : current;
            synchronized (slot) {
                slot.columns.put(column, value);
                slot.waiting.add(future);
            }
            return slot;
        });
        return future;
    }

    /**
     * Queues several columns at once.
     *
     * @param serverUuid The player's server UUID.
     * @param values     The column values.
     * @return A future completing once they are in the database.
     */
    public CompletableFuture<Void> queue(String serverUuid, Map<String, Object> values) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (values == null || values.isEmpty()) {
            future.complete(null);
            return future;
        }
        pending.compute(serverUuid, (uuid, current) -> {
            Pending slot = current == null ? new Pending() : current;
            synchronized (slot) {
                slot.columns.putAll(values);
                slot.waiting.add(future);
            }
            return slot;
        });
        return future;
    }

    /** Writes everything that is waiting. Called on a timer. */
    public void flush() {
        if (pending.isEmpty()) return;
        List<String> uuids = new ArrayList<>(pending.keySet());
        flush(uuids);
    }

    /**
     * Writes one player's pending columns immediately.
     *
     * @param serverUuid The player's server UUID.
     */
    public void flushPlayer(String serverUuid) {
        if (!pending.containsKey(serverUuid)) return;
        flush(List.of(serverUuid));
    }

    /** @return How many players have unwritten columns right now. */
    public int pendingPlayers() {
        return pending.size();
    }

    /** @return How many column values have been written since startup. */
    public long getColumnsWritten() {
        return columnsWritten.sum();
    }

    /** @return How many UPDATE statements that took. */
    public long getStatementCount() {
        return statements.sum();
    }

    /**
     * Writes the given players, on the database executor.
     *
     * <p>Each player is removed from the map before its statement is built, so a write arriving
     * meanwhile lands in a fresh slot and is picked up by the next flush rather than being lost.</p>
     */
    private void flush(List<String> uuids) {
        Map<String, Pending> batch = new LinkedHashMap<>();
        for (String uuid : uuids) {
            Pending slot = pending.remove(uuid);
            if (slot != null) batch.put(uuid, slot);
        }
        if (batch.isEmpty()) return;

        executor.execute(() -> {
            List<String> written = new ArrayList<>(batch.size());
            try (Connection conn = main.getDataSource().getConnection()) {
                for (Map.Entry<String, Pending> entry : batch.entrySet()) {
                    Pending slot = entry.getValue();
                    Map<String, Object> columns;
                    List<CompletableFuture<Void>> waiting;
                    synchronized (slot) {
                        columns = new LinkedHashMap<>(slot.columns);
                        waiting = new ArrayList<>(slot.waiting);
                    }
                    if (columns.isEmpty()) continue;

                    StringBuilder sql = new StringBuilder("UPDATE players SET ");
                    boolean first = true;
                    for (String column : columns.keySet()) {
                        if (!first) sql.append(", ");
                        sql.append(column).append(" = ?");
                        first = false;
                    }
                    sql.append(" WHERE server_uuid = ?");

                    try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                        int index = 1;
                        for (Object value : columns.values()) ps.setObject(index++, value);
                        ps.setString(index, entry.getKey());
                        int rows = ps.executeUpdate();
                        statements.increment();
                        columnsWritten.add(columns.size());
                        if (rows == 0) {
                            main.logger().sendDebug("[DAO] Buffered write touched no row for "
                                    + entry.getKey() + " (the player may not exist yet).");
                        } else {
                            written.add(entry.getKey());
                        }
                    }
                    waiting.forEach(future -> future.complete(null));
                }
            } catch (SQLException e) {
                main.logger().sendError("[DAO] Failed to write buffered player columns : " + e.getMessage());
                for (Pending slot : batch.values()) {
                    synchronized (slot) {
                        slot.waiting.forEach(future -> future.completeExceptionally(e));
                    }
                }
                return;
            }
            if (!written.isEmpty() && onFlushed != null) onFlushed.accept(written);
        });
    }
}
