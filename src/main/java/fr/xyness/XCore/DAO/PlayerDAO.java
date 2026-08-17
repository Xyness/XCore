package fr.xyness.XCore.DAO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Models.PlayerData;

/**
 * Data Access Object for the {@code players} table.
 * <p>
 * All CRUD operations are executed asynchronously via {@link CompletableFuture},
 * using the shared executor inherited from {@link AbstractDAO}.
 * SQL statements are standard and compatible with SQLite, MySQL and PostgreSQL.
 * </p>
 */
public class PlayerDAO extends AbstractDAO {

	/** Core column names that are mapped directly to {@link PlayerData} fields. */
    private static final Set<String> CORE_COLUMNS = Set.of("id", "server_uuid", "mojang_uuid", "player_name", "head_textures", "created_at");

	/** Regex for safe SQL identifiers. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    /** Set of known extra column names (populated from DB metadata on first access and on column addition). */
    private final Set<String> knownExtraColumns = ConcurrentHashMap.newKeySet();

	/** SQL statement for inserting a new player row. */
    private static final String INSERT =
        "INSERT INTO players (server_uuid, mojang_uuid, player_name, head_textures, created_at) VALUES (?, ?, ?, ?, ?)";

	/** SQL statement for selecting a player by their server UUID. */
    private static final String SELECT_BY_UUID =
        "SELECT * FROM players WHERE server_uuid = ?";

	/**
	 * SQL for a case-insensitive lookup by name, in a form the index can actually serve.
	 *
	 * <p>{@code lower(player_name) = ?} applies a function to the column, which disqualifies
	 * {@code idx_players_name} on every engine: the lookup degraded into a full table scan, once per
	 * name resolution that missed the cache. Each dialect gets the spelling it can index instead —
	 * MySQL's default collation is already case-insensitive, SQLite has {@code COLLATE NOCASE}, and
	 * PostgreSQL keeps the function but is given a matching functional index.</p>
	 */
    private final String selectByName;

	/** Whether {@link #selectByName} expects its parameter already lower-cased. */
    private final boolean selectByNameLowercased;

	/** SQL statement for selecting one page of players, ordered so the pages do not shift. */
    private static final String SELECT_PAGE =
        "SELECT * FROM players ORDER BY server_uuid LIMIT ? OFFSET ?";

	/** SQL statement for updating a player's core fields by server UUID. */
    private static final String UPDATE =
        "UPDATE players SET server_uuid = ?, mojang_uuid = ?, player_name = ?, head_textures = ? WHERE server_uuid = ?";

	/** SQL statement for deleting a player by server UUID. */
    private static final String DELETE =
        "DELETE FROM players WHERE server_uuid = ?";

	/** Gathers column writes so a player's row is updated once rather than once per column. */
    private final PlayerWriteBuffer writeBuffer;

	/** Told which players were just written, so their caches elsewhere can be dropped. */
    private volatile java.util.function.Consumer<java.util.List<String>> flushListener;

	/**
	 * Creates a new PlayerDAO.
	 *
	 * @param main     The main plugin instance.
	 * @param executor The executor service for async operations.
	 */
    public PlayerDAO(XCore main, ExecutorService executor) {
        super(main, executor);
        this.writeBuffer = new PlayerWriteBuffer(main, executor, uuids -> {
            java.util.function.Consumer<java.util.List<String>> listener = flushListener;
            if (listener != null) listener.accept(uuids);
        });
        switch (main.getDatabaseType()) {
            case MYSQL -> {
                selectByName = "SELECT * FROM players WHERE player_name = ?";
                selectByNameLowercased = false;
            }
            case POSTGRESQL -> {
                selectByName = "SELECT * FROM players WHERE lower(player_name) = ?";
                selectByNameLowercased = true;
            }
            default -> {
                selectByName = "SELECT * FROM players WHERE player_name = ? COLLATE NOCASE";
                selectByNameLowercased = false;
            }
        }
    }

	/**
	 * Inserts a new player into the database asynchronously.
	 * The {@code created_at} timestamp is set to the current date-time.
	 *
	 * @param p The player data to insert.
	 * @return A {@link CompletableFuture} that completes when the insert finishes.
	 */
    public CompletableFuture<Void> insertAsync(PlayerData p) {
        return runAsync(() -> {
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(INSERT)) {
                ps.setString(1, p.getUuid().toString());
                ps.setString(2, p.getMojangUUID());
                ps.setString(3, p.getName());
                ps.setString(4, p.getTexture());
                ps.setString(5, LocalDateTime.now().format(XCore.FORMATTER));
                ps.executeUpdate();
                main.logger().sendDebug("[DAO] Inserted player " + p + ".");
            } catch (SQLException e) {
				main.logger().sendError("[DAO] Failed to insert player " + p + " : " + e.getMessage());
			}
        });
    }

	/**
	 * Finds a player by their server UUID asynchronously.
	 *
	 * @param uuid The server-side UUID string.
	 * @return A future containing an {@link Optional} with the player data, or empty if not found.
	 */
    public CompletableFuture<Optional<PlayerData>> findByServerUuidAsync(String uuid) {
        return supplyAsync(() -> {
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(SELECT_BY_UUID)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                	main.logger().sendDebug("[DAO] Select player by uuid : " + uuid + ".");
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
				main.logger().sendError("[DAO] Failed to get player by uuid '" + uuid + "' : " + e.getMessage());
			}
            return Optional.empty();
        });
    }

	/**
	 * Finds a player by their name asynchronously (case-insensitive).
	 *
	 * @param name The player name to search for.
	 * @return A future containing an {@link Optional} with the player data, or empty if not found.
	 */
    public CompletableFuture<Optional<PlayerData>> findByNameAsync(String name) {
        return supplyAsync(() -> {
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(selectByName)) {
                ps.setString(1, selectByNameLowercased ? name.toLowerCase() : name);
                try (ResultSet rs = ps.executeQuery()) {
                	main.logger().sendDebug("[DAO] Select player by name : " + name + ".");
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
				main.logger().sendError("[DAO] Failed to get player by name '" + name + "' : " + e.getMessage());
			}
            return Optional.empty();
        });
    }

	/**
	 * Finds a player by the Mojang UUID recorded for them.
	 *
	 * <p>The one identifier that survives a change of UUID mode: {@code server_uuid} is the offline
	 * hash of the name in one mode and Mojang's own value in the other, so it cannot be used to
	 * recognise a returning player across that switch.</p>
	 *
	 * @param mojangUuid The Mojang UUID, dashed.
	 * @return A future containing the player, or empty when no row carries that Mojang UUID.
	 */
    public CompletableFuture<Optional<PlayerData>> findByMojangUuidAsync(String mojangUuid) {
        return supplyAsync(() -> {
            if (mojangUuid == null || mojangUuid.isBlank() || "none".equalsIgnoreCase(mojangUuid)) {
                return Optional.empty();
            }
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT * FROM players WHERE mojang_uuid = ?")) {
                ps.setString(1, mojangUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(map(rs));
                }
            } catch (SQLException e) {
                main.logger().sendError("[DAO] Failed to get player by mojang uuid '" + mojangUuid + "' : " + e.getMessage());
            }
            return Optional.empty();
        });
    }

	/**
	 * Retrieves a page of players from the database asynchronously.
	 *
	 * <p>Paged rather than whole. This used to be {@code findAllAsync()} — {@code SELECT * FROM
	 * players} with no bound — which held every row of the table, with every column every addon has
	 * added to it, in one list. That is fine on a test server and is a heap exhaustion on a network
	 * with two hundred thousand players, and being a public method it was an invitation to find out
	 * which one you had.</p>
	 *
	 * @param offset How many rows to skip.
	 * @param limit  How many rows to return; clamped to 1000.
	 * @return A future containing that page of {@link PlayerData} entries, ordered by UUID so the
	 *         pages are stable between calls.
	 */
    public CompletableFuture<List<PlayerData>> findPageAsync(int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return supplyAsync(() -> {
            List<PlayerData> list = new ArrayList<>();
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(SELECT_PAGE)) {
                ps.setInt(1, safeLimit);
                ps.setInt(2, safeOffset);
                try (ResultSet rs = ps.executeQuery()) {
                    String[] extraColumns = extraColumnNames(rs);
                    while (rs.next()) list.add(map(rs, extraColumns));
                }
                main.logger().sendDebug("[DAO] Select players page : " + list.size() + " rows from " + safeOffset + ".");
            } catch (SQLException e) {
				main.logger().sendError("[DAO] Failed to get players page : " + e.getMessage());
			}
            return list;
        });
    }

	/**
	 * Counts the rows of the players table.
	 *
	 * @return A future containing the total, for paging through {@link #findPageAsync(int, int)}.
	 */
    public CompletableFuture<Integer> countAsync() {
        return supplyAsync(() -> {
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM players");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
				main.logger().sendError("[DAO] Failed to count players : " + e.getMessage());
			}
            return 0;
        });
    }

	/**
	 * Updates a player's data asynchronously, matching on the player's current UUID.
	 *
	 * @param p The updated player data.
	 * @return A future that completes when the update finishes.
	 */
    public CompletableFuture<Void> updateAsync(PlayerData p) {
        return updateAsync(p, p.getUuid().toString());
    }

	/**
	 * Updates a player's data asynchronously, matching on a previous (old) UUID.
	 * Used when a player's server UUID has changed (e.g. online/offline mode switch).
	 *
	 * @param p       The updated player data with the new UUID.
	 * @param oldUuid The previous server UUID to match in the WHERE clause.
	 * @return A future that completes when the update finishes.
	 */
    public CompletableFuture<Void> updateAsync(PlayerData p, String oldUuid) {
        return runAsync(() -> {
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(UPDATE)) {
            	ps.setString(1, p.getUuid().toString());
            	ps.setString(2, p.getMojangUUID());
                ps.setString(3, p.getName());
                ps.setString(4, p.getTexture());
                ps.setString(5, oldUuid);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    main.logger().sendWarning("[DAO] Update affected 0 rows for player " + p + " (oldUuid=" + oldUuid + ").");
                } else {
                    main.logger().sendDebug("[DAO] Updated player " + p + ".");
                }
            } catch (SQLException e) {
				main.logger().sendError("[DAO] Failed to update player '" + p.getName() + "' : " + e.getMessage());
			}
        });
    }

	/**
	 * Deletes a player from the database asynchronously.
	 *
	 * @param serverUuid The server UUID of the player to delete.
	 * @return A future that completes when the deletion finishes.
	 */
    public CompletableFuture<Void> deleteAsync(String serverUuid) {
        return runAsync(() -> {
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(DELETE)) {
                ps.setString(1, serverUuid);
                ps.executeUpdate();
                main.logger().sendDebug("[DAO] Deleted player '" + serverUuid + "'.");
            } catch (SQLException e) {
				main.logger().sendError("[DAO] Failed to delete player '" + serverUuid + "' : " + e.getMessage());
			}
        });
    }

	/**
	 * Registers an extra column name as known (called after ColumnBuilder.apply()).
	 *
	 * @param column The column name to register.
	 */
    public void registerExtraColumn(String column) {
        knownExtraColumns.add(column.toLowerCase());
    }

	/**
	 * Forgets an extra column (called after ColumnBuilder.dropColumn()).
	 *
	 * @param column The column name to forget.
	 */
    public void unregisterExtraColumn(String column) {
        knownExtraColumns.remove(column.toLowerCase());
    }

	/**
	 * Returns the set of known extra column names (unmodifiable).
	 *
	 * @return The known extra columns.
	 */
    public Set<String> getKnownExtraColumns() {
        return Collections.unmodifiableSet(knownExtraColumns);
    }

	/**
	 * Loads extra column names from the database metadata.
	 * Called once during plugin startup to populate the known columns set.
	 */
    public void loadExtraColumnsFromMetadata() {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM players LIMIT 0");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String colName = meta.getColumnName(i).toLowerCase();
                if (!CORE_COLUMNS.contains(colName)) {
                    knownExtraColumns.add(colName);
                }
            }
            main.logger().sendDebug("[DAO] Loaded " + knownExtraColumns.size() + " extra column(s) from metadata.");
        } catch (SQLException e) {
            main.logger().sendError("[DAO] Failed to load column metadata : " + e.getMessage());
        }
    }

	/**
	 * Updates a single dynamic column value for a player asynchronously.
	 * The column name is validated against the known extra columns set and a safe identifier regex.
	 *
	 * @param serverUuid The server UUID of the player.
	 * @param column     The column name to update (must be a registered extra column).
	 * @param value      The new value.
	 * @return A future that completes when the update finishes.
	 */
    public CompletableFuture<Void> updateColumnAsync(String serverUuid, String column, Object value) {
        String safeColumn = column.toLowerCase();
        if (!SAFE_IDENTIFIER.matcher(safeColumn).matches() || !knownExtraColumns.contains(safeColumn)) {
            main.logger().sendError("[DAO] Rejected column update: unknown or invalid column '" + column + "'.");
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown or invalid column: " + column));
        }
        if (writeBuffer.isWriteThrough(safeColumn)) {
            return writeColumnNow(serverUuid, safeColumn, value);
        }
        return writeBuffer.queue(serverUuid, safeColumn, value);
    }

	/**
	 * Writes one column straight away, for the columns that cannot wait in the buffer.
	 *
	 * @param serverUuid The server UUID of the player.
	 * @param column     The column name, already validated.
	 * @param value      The new value.
	 * @return A future that completes when the update is persisted.
	 */
    private CompletableFuture<Void> writeColumnNow(String serverUuid, String column, Object value) {
        return runAsync(() -> {
            String sql = "UPDATE players SET " + column + " = ? WHERE server_uuid = ?";
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, value);
                ps.setString(2, serverUuid);
                if (ps.executeUpdate() == 0) {
                    main.logger().sendDebug("[DAO] Column update affected 0 rows for " + serverUuid
                            + "." + column + " (player may not exist yet).");
                }
            } catch (SQLException e) {
                main.logger().sendError("[DAO] Failed to update column " + column + " for " + serverUuid + " : " + e.getMessage());
            }
        });
    }

	/**
	 * Updates several columns of a player in one statement.
	 *
	 * @param serverUuid The server UUID of the player.
	 * @param values     The column names and their new values.
	 * @return A future that completes when the update is persisted.
	 */
    public CompletableFuture<Void> updateColumnsAsync(String serverUuid, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return CompletableFuture.completedFuture(null);
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String column = entry.getKey().toLowerCase();
            if (!SAFE_IDENTIFIER.matcher(column).matches() || !knownExtraColumns.contains(column)) {
                main.logger().sendError("[DAO] Rejected column update: unknown or invalid column '" + entry.getKey() + "'.");
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown or invalid column: " + entry.getKey()));
            }
            safe.put(column, entry.getValue());
        }

        // A write-through column in the middle of the batch would be delayed with the rest, so it
        // leaves on its own and the buffer takes what is left.
        java.util.List<CompletableFuture<Void>> now = new java.util.ArrayList<>();
        safe.entrySet().removeIf(entry -> {
            if (!writeBuffer.isWriteThrough(entry.getKey())) return false;
            now.add(writeColumnNow(serverUuid, entry.getKey(), entry.getValue()));
            return true;
        });

        CompletableFuture<Void> buffered = safe.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : writeBuffer.queue(serverUuid, safe);
        if (now.isEmpty()) return buffered;

        now.add(buffered);
        return CompletableFuture.allOf(now.toArray(new CompletableFuture[0]));
    }

	/** @return The buffer holding column writes that have not reached the database yet. */
    public PlayerWriteBuffer writeBuffer() {
        return writeBuffer;
    }

	/**
	 * Registers who to tell once a player's row has actually been written.
	 *
	 * @param listener Receives the server UUIDs of the players just updated.
	 */
    public void setFlushListener(java.util.function.Consumer<java.util.List<String>> listener) {
        this.flushListener = listener;
    }

	/**
	 * Finds multiple players by their server UUIDs in a single batch query.
	 *
	 * @param uuids The list of server-side UUID strings.
	 * @return A future containing a map of UUID string &rarr; Optional&lt;PlayerData&gt;.
	 */
    public CompletableFuture<Map<String, Optional<PlayerData>>> findByServerUuidsAsync(List<String> uuids) {
        return supplyAsync(() -> {
            Map<String, Optional<PlayerData>> result = new HashMap<>();
            if (uuids.isEmpty()) return result;

            String placeholders = uuids.stream().map(u -> "?").collect(Collectors.joining(","));
            String sql = "SELECT * FROM players WHERE server_uuid IN (" + placeholders + ")";
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < uuids.size(); i++) {
                    ps.setString(i + 1, uuids.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    String[] extraColumns = extraColumnNames(rs);
                    while (rs.next()) {
                        PlayerData data = map(rs, extraColumns);
                        result.put(data.getUuid().toString(), Optional.of(data));
                    }
                }
                main.logger().sendDebug("[DAO] Batch select " + uuids.size() + " players, found " + result.size() + ".");
            } catch (SQLException e) {
                main.logger().sendError("[DAO] Failed to batch get players : " + e.getMessage());
            }
            for (String uuid : uuids) {
                result.putIfAbsent(uuid, Optional.empty());
            }
            return result;
        });
    }

	/**
	 * Maps a {@link ResultSet} row to a {@link PlayerData} instance.
	 * Extra columns beyond the core fields are loaded as dynamic data, identified by name (not position).
	 *
	 * @param rs The result set positioned at the current row.
	 * @return The mapped {@link PlayerData}.
	 * @throws SQLException If a database access error occurs.
	 */
    private PlayerData map(ResultSet rs) throws SQLException {
        return map(rs, extraColumnNames(rs));
    }

	/**
	 * Resolves, once for a whole {@link ResultSet}, which columns are extra data and under what name.
	 *
	 * <p>Reading the metadata inside the row loop meant one {@code getColumnName} and one
	 * {@code toLowerCase} per column <em>per row</em> — six hundred thousand throw-away strings for a
	 * fifty-thousand-player export.</p>
	 *
	 * @param rs The result set.
	 * @return An array indexed by column position (1-based, index 0 unused); {@code null} marks a
	 *         core column that must not be copied into the dynamic data.
	 * @throws SQLException If the metadata cannot be read.
	 */
    private static String[] extraColumnNames(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int total = meta.getColumnCount();
        String[] names = new String[total + 1];
        for (int i = 1; i <= total; i++) {
            String colName = meta.getColumnName(i).toLowerCase();
            names[i] = CORE_COLUMNS.contains(colName) ? null : colName;
        }
        return names;
    }

	/**
	 * Maps one row using pre-resolved column names.
	 *
	 * @param rs           The result set positioned at the current row.
	 * @param extraColumns The output of {@link #extraColumnNames(ResultSet)}.
	 * @return The mapped {@link PlayerData}.
	 * @throws SQLException If a database access error occurs.
	 */
    private static PlayerData map(ResultSet rs, String[] extraColumns) throws SQLException {
        PlayerData playerData = new PlayerData(
            UUID.fromString(rs.getString("server_uuid")),
            rs.getString("player_name"),
            rs.getString("head_textures"),
            rs.getString("mojang_uuid")
        );

        for (int i = 1; i < extraColumns.length; i++) {
            String colName = extraColumns[i];
            if (colName == null) continue;
            Object value = rs.getObject(i);
            if (value != null) {
                playerData.setTargetData(colName, value);
            }
        }

        return playerData;
    }
}
