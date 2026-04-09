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

	/** SQL statement for selecting a player by name (case-insensitive). */
    private static final String SELECT_BY_NAME =
         "SELECT * FROM players WHERE lower(player_name) = ?";

	/** SQL statement for selecting all players. */
    private static final String SELECT_ALL =
        "SELECT * FROM players";

	/** SQL statement for updating a player's core fields by server UUID. */
    private static final String UPDATE =
        "UPDATE players SET server_uuid = ?, mojang_uuid = ?, player_name = ?, head_textures = ? WHERE server_uuid = ?";

	/** SQL statement for deleting a player by server UUID. */
    private static final String DELETE =
        "DELETE FROM players WHERE server_uuid = ?";

	/**
	 * Creates a new PlayerDAO.
	 *
	 * @param main     The main plugin instance.
	 * @param executor The executor service for async operations.
	 */
    public PlayerDAO(XCore main, ExecutorService executor) { super(main, executor); }

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
                 PreparedStatement ps = c.prepareStatement(SELECT_BY_NAME)) {
                ps.setString(1, name.toLowerCase());
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
	 * Retrieves all players from the database asynchronously.
	 *
	 * @return A future containing a list of all {@link PlayerData} entries.
	 */
    public CompletableFuture<List<PlayerData>> findAllAsync() {
        return supplyAsync(() -> {
            List<PlayerData> list = new ArrayList<>();
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(SELECT_ALL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
                main.logger().sendDebug("[DAO] Select all players : " + main.methods().getNumberSeparate(list.size()) + " results.");
            } catch (SQLException e) {
				main.logger().sendError("[DAO] Failed to get all players : " + e.getMessage());
			}
            return list;
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
        return runAsync(() -> {
            String sql = "UPDATE players SET " + safeColumn + " = ? WHERE server_uuid = ?";
            try (Connection c = getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, value);
                ps.setString(2, serverUuid);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    main.logger().sendDebug("[DAO] Column update affected 0 rows for " + serverUuid + "." + safeColumn + " (player may not exist yet).");
                } else {
                    main.logger().sendDebug("[DAO] Updated column " + safeColumn + " for player " + serverUuid + ".");
                }
            } catch (SQLException e) {
                main.logger().sendError("[DAO] Failed to update column " + safeColumn + " for " + serverUuid + " : " + e.getMessage());
            }
        });
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
                    while (rs.next()) {
                        PlayerData data = map(rs);
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
        ResultSetMetaData meta = rs.getMetaData();
        int totalCols = meta.getColumnCount();

        PlayerData playerData = new PlayerData(
            UUID.fromString(rs.getString("server_uuid")),
            rs.getString("player_name"),
            rs.getString("head_textures"),
            rs.getString("mojang_uuid")
        );

        for (int i = 1; i <= totalCols; i++) {
            String colName = meta.getColumnName(i).toLowerCase();
            if (!CORE_COLUMNS.contains(colName)) {
                Object value = rs.getObject(i);
                if (value != null) {
                    playerData.setTargetData(colName, value);
                }
            }
        }

        return playerData;
    }
}
