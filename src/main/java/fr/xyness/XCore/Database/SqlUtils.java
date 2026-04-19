package fr.xyness.XCore.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import fr.xyness.XCore.API.DatabaseType;

/**
 * Shared SQL utility helpers for cross-dialect compatibility.
 */
public final class SqlUtils {

    private static final Pattern SAFE_IDENT = Pattern.compile("[a-zA-Z0-9_]+");
    private static final Pattern SAFE_COLUMNS = Pattern.compile("[a-zA-Z0-9_,\\s]+");

    private SqlUtils() {}

    /**
     * Creates an index only if it does not already exist. Works on all supported dialects.
     * MySQL does not support {@code CREATE INDEX IF NOT EXISTS} prior to 8.0.29, so this
     * probes {@code information_schema.statistics} first. PostgreSQL and SQLite use the
     * native {@code IF NOT EXISTS} syntax.
     *
     * @param conn      An open JDBC connection.
     * @param dbType    The current database dialect.
     * @param indexName The index name (validated).
     * @param tableName The target table name (validated).
     * @param columns   The comma-separated column list (validated).
     */
    public static void createIndexIfNotExists(Connection conn, DatabaseType dbType,
                                              String indexName, String tableName, String columns)
            throws SQLException {
        if (!SAFE_IDENT.matcher(indexName).matches()
                || !SAFE_IDENT.matcher(tableName).matches()
                || !SAFE_COLUMNS.matcher(columns).matches()) {
            throw new SQLException("Invalid identifier for index creation: "
                    + indexName + " / " + tableName + " / " + columns);
        }

        if (dbType == DatabaseType.MYSQL) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
                ps.setString(1, tableName);
                ps.setString(2, indexName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return;
                }
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE INDEX " + indexName + " ON " + tableName + " (" + columns + ")");
            }
        } else {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS " + indexName
                        + " ON " + tableName + " (" + columns + ")");
            }
        }
    }
}
