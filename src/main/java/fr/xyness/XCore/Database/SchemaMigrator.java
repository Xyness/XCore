package fr.xyness.XCore.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import fr.xyness.XCore.API.DatabaseType;

/**
 * Runs the schema changes an addon needs, once each, in order.
 *
 * <p>For what {@code CREATE TABLE IF NOT EXISTS} and {@code addColumnIfMissing} cannot do: renaming
 * a column, backfilling a value, splitting a table. The version reached is stored per addon, so
 * steps below it are skipped on the next start.</p>
 *
 * <pre>{@code
 * api().tableManager().migrator("XHomes")
 *     .version(1, conn -> { ... create the new table ... })
 *     .version(2, conn -> { ... copy the old column into it ... })
 *     .run();
 * }</pre>
 *
 * <p>Steps run in a transaction, so a failure leaves the database and the version as they were.
 * Note that SQLite does not always roll back DDL.</p>
 */
public class SchemaMigrator {

    private static final String TABLE = "xcore_schema_versions";
    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final String namespace;
    private final List<Step> steps = new ArrayList<>();

    SchemaMigrator(DataSource dataSource, DatabaseType databaseType, String namespace) {
        if (namespace == null || !SAFE_NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid migration namespace: " + namespace);
        }
        this.dataSource = dataSource;
        this.databaseType = databaseType;
        this.namespace = namespace;
    }

    /**
     * Declares one step. Numbers must be positive and are applied in ascending order.
     *
     * @param version The step number.
     * @param body    What to run.
     * @return This migrator.
     */
    public SchemaMigrator version(int version, Migration body) {
        if (version <= 0) throw new IllegalArgumentException("Migration versions start at 1.");
        steps.add(new Step(version, body));
        return this;
    }

    /**
     * Applies every step the database has not seen yet.
     *
     * <p>Synchronous: this belongs in {@code onEnable()}, before anything queries the tables it
     * touches.</p>
     *
     * @return The version now recorded, or -1 if a step failed.
     */
    public int run() {
        steps.sort((a, b) -> Integer.compare(a.version(), b.version()));
        try (Connection conn = dataSource.getConnection()) {
            ensureTable(conn);
            int current = readVersion(conn);
            int applied = current;

            for (Step step : steps) {
                if (step.version() <= current) continue;

                boolean previousAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    step.body().apply(conn);
                    writeVersion(conn, step.version());
                    conn.commit();
                    applied = step.version();
                } catch (Exception e) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    throw new SQLException("Migration " + namespace + " v" + step.version()
                            + " failed : " + e.getMessage(), e);
                } finally {
                    try { conn.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) {}
                }
            }
            return applied;
        } catch (SQLException e) {
            java.util.logging.Logger.getLogger("XCore").severe(
                    "Schema migration for " + namespace + " stopped : " + e.getMessage());
            return -1;
        }
    }

    /** @return The version currently recorded for this namespace, 0 when there is none. */
    public int currentVersion() {
        try (Connection conn = dataSource.getConnection()) {
            ensureTable(conn);
            return readVersion(conn);
        } catch (SQLException e) {
            return 0;
        }
    }

    private void ensureTable(Connection conn) throws SQLException {
        String stamp = databaseType == DatabaseType.MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "namespace VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "version INT NOT NULL, "
                    + "updated_at VARCHAR(19) NOT NULL)" + stamp);
        }
    }

    private int readVersion(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT version FROM " + TABLE + " WHERE namespace = ?")) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void writeVersion(Connection conn, int version) throws SQLException {
        String sql = switch (databaseType) {
            case MYSQL -> "INSERT INTO " + TABLE + " (namespace, version, updated_at) VALUES (?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE version = VALUES(version), updated_at = VALUES(updated_at)";
            default -> "INSERT INTO " + TABLE + " (namespace, version, updated_at) VALUES (?, ?, ?)"
                    + " ON CONFLICT (namespace) DO UPDATE SET version = excluded.version, updated_at = excluded.updated_at";
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, namespace);
            ps.setInt(2, version);
            ps.setString(3, LocalDateTime.now().format(STAMP));
            ps.executeUpdate();
        }
    }

    /** One schema change. */
    @FunctionalInterface
    public interface Migration {
        /**
         * @param connection The transaction's connection.
         * @throws Exception to abort the step and keep the recorded version where it was.
         */
        void apply(Connection connection) throws Exception;
    }

    /** A numbered step. */
    private record Step(int version, Migration body) {}
}
