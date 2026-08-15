package fr.xyness.XCore.Sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import fr.xyness.XCore.API.DatabaseType;

/**
 * Database polling-based sync transport.
 * <p>
 * Uses a single {@code xcore_sync} table shared by all channels. Each row contains
 * the full formatted message. A daemon scheduler polls at a configurable interval
 * and dispatches new rows to the callback. Old rows are cleaned up based on
 * the retention setting.
 * </p>
 * <p>
 * Table schema:
 * <pre>
 * id         BIGINT PK (auto-increment)
 * message    TEXT NOT NULL
 * created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * </pre>
 * </p>
 */
class DatabaseSyncTransport implements SyncManager.SyncTransport {

    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final SyncManager.TransportCallback callback;
    private final Executor handlerExecutor;
    private final int pollIntervalTicks;
    private final int retentionSeconds;
    private final SyncManager.LogCallback logDebug;
    private final SyncManager.LogCallback logError;
    private final AtomicLong lastSeenId = new AtomicLong(0);
    private volatile ScheduledExecutorService pollScheduler;

    /** Wall-clock of the last retention sweep. */
    private volatile long lastCleanup = 0L;

    /** How often the retention sweep may run, whatever the poll interval is. */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    DatabaseSyncTransport(DataSource dataSource, DatabaseType databaseType,
                          SyncManager.TransportCallback callback, Executor handlerExecutor,
                          int pollIntervalTicks, int retentionSeconds,
                          SyncManager.LogCallback logDebug, SyncManager.LogCallback logError) {
        this.dataSource = dataSource;
        this.databaseType = databaseType;
        this.callback = callback;
        this.handlerExecutor = handlerExecutor;
        this.pollIntervalTicks = pollIntervalTicks;
        this.retentionSeconds = retentionSeconds;
        this.logDebug = logDebug;
        this.logError = logError;
    }

    @Override
    public void start() {
        createTable();
        initLastSeenId();

        // Convert ticks to milliseconds (1 tick = 50ms)
        long pollIntervalMs = pollIntervalTicks * 50L;

        pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "XCore-SyncDBPoll");
            t.setDaemon(true);
            return t;
        });
        pollScheduler.scheduleAtFixedRate(this::poll, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        logDebug.log("Database sync transport started (poll=" + (pollIntervalMs / 1000) + "s).");
    }

    @Override
    public void stop() {
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
        }
    }

    @Override
    public void publish(String formattedMessage) {
        String sql = "INSERT INTO xcore_sync (message) VALUES (?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, formattedMessage);
            ps.executeUpdate();
        } catch (SQLException e) {
            logError.log("Failed to publish DB sync message : " + e.getMessage());
        }
    }

    private void poll() {
        try (Connection c = dataSource.getConnection()) {
            String selectSql = "SELECT id, message FROM xcore_sync WHERE id > ? ORDER BY id ASC";
            try (PreparedStatement ps = c.prepareStatement(selectSql)) {
                ps.setLong(1, lastSeenId.get());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String message = rs.getString("message");
                        lastSeenId.set(id);

                        // Dispatch to handler on the provided executor
                        handlerExecutor.execute(() -> {
                            try {
                                callback.onRawMessage(message);
                            } catch (Exception e) {
                                logError.log("Error dispatching DB sync message id=" + id + " : " + e.getMessage());
                            }
                        });
                    }
                }
            }

            // Retention is a housekeeping concern, not a per-poll one. Running the DELETE on every
            // sondage meant a scan of the whole table every three seconds, from every server of the
            // network at once, for rows that only need to disappear eventually.
            long now = System.currentTimeMillis();
            if (now - lastCleanup >= CLEANUP_INTERVAL_MS) {
                lastCleanup = now;
                cleanup(c);
            }

        } catch (SQLException e) {
            logError.log("DB sync poll error : " + e.getMessage());
        }
    }

    private void cleanup(Connection c) throws SQLException {
        String sql = switch (databaseType) {
            case SQLITE -> "DELETE FROM xcore_sync WHERE created_at <= datetime('now', '-" + retentionSeconds + " seconds')";
            case MYSQL -> "DELETE FROM xcore_sync WHERE created_at <= NOW() - INTERVAL " + retentionSeconds + " SECOND";
            case POSTGRESQL -> "DELETE FROM xcore_sync WHERE created_at <= NOW() - INTERVAL '" + retentionSeconds + " seconds'";
        };
        try (Statement stmt = c.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private void createTable() {
        try (Connection c = dataSource.getConnection(); Statement stmt = c.createStatement()) {
            String sql = switch (databaseType) {
                case MYSQL -> """
                    CREATE TABLE IF NOT EXISTS xcore_sync (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        message TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_xcs_id (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";
                case POSTGRESQL -> """
                    CREATE TABLE IF NOT EXISTS xcore_sync (
                        id BIGSERIAL PRIMARY KEY,
                        message TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )""";
                case SQLITE -> """
                    CREATE TABLE IF NOT EXISTS xcore_sync (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )""";
            };
            stmt.executeUpdate(sql);
            // The retention sweep filters on created_at; without this index it is a full scan.
            try {
                stmt.executeUpdate(databaseType == DatabaseType.MYSQL
                        ? "CREATE INDEX idx_xcs_created ON xcore_sync (created_at)"
                        : "CREATE INDEX IF NOT EXISTS idx_xcs_created ON xcore_sync (created_at)");
            } catch (SQLException ignored) {
                // MySQL has no IF NOT EXISTS for indexes: a duplicate is exactly what we want here.
            }
        } catch (SQLException e) {
            logError.log("Failed to create xcore_sync table : " + e.getMessage());
        }
    }

    private void initLastSeenId() {
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(id), 0) FROM xcore_sync")) {
            if (rs.next()) {
                lastSeenId.set(rs.getLong(1));
            }
        } catch (SQLException e) {
            logError.log("Failed to init last seen id for xcore_sync : " + e.getMessage());
        }
    }
}
