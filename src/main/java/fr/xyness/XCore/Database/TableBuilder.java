package fr.xyness.XCore.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import fr.xyness.XCore.API.DatabaseType;

/**
 * Fluent builder for CREATE TABLE statements with multi-dialect support.
 * <p>
 * Handles AUTO_INCREMENT (MySQL) vs BIGSERIAL (PostgreSQL) vs INTEGER AUTOINCREMENT (SQLite)
 * and other dialect differences automatically. Column names are validated with a regex
 * to prevent SQL injection.
 * </p>
 *
 * <pre>
 * tableManager.createTable("xbans_sanctions")
 *     .column("id", ColumnType.SERIAL)
 *     .column("player_uuid", ColumnType.CHAR, 36).notNull()
 *     .column("reason", ColumnType.TEXT)
 *     .column("duration", ColumnType.BIGINT).defaultValue(0).notNull()
 *     .column("created_at", ColumnType.TIMESTAMP).defaultValue("CURRENT_TIMESTAMP")
 *     .index("player_uuid")
 *     .build();
 * </pre>
 */
public class TableBuilder {

    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9_]+");

    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final String tableName;
    private final List<ColumnDef> columns = new ArrayList<>();
    private final List<IndexDef> indexes = new ArrayList<>();

    // State for the current column being defined
    private ColumnDef currentColumn;

    TableBuilder(DataSource dataSource, DatabaseType databaseType, String tableName) {
        validateName(tableName);
        this.dataSource = dataSource;
        this.databaseType = databaseType;
        this.tableName = tableName;
    }

    /**
     * Adds a column with no length parameter.
     *
     * @param name The column name.
     * @param type The column type.
     * @return This builder.
     */
    public TableBuilder column(String name, ColumnType type) {
        return column(name, type, -1);
    }

    /**
     * Adds a column with a length parameter (used for VARCHAR, CHAR).
     *
     * @param name   The column name.
     * @param type   The column type.
     * @param length The length (ignored for types that don't use it).
     * @return This builder.
     */
    public TableBuilder column(String name, ColumnType type, int length) {
        validateName(name);
        finalizeCurrentColumn();
        currentColumn = new ColumnDef(name, type, length);
        return this;
    }

    /**
     * Marks the current column as NOT NULL.
     *
     * @return This builder.
     * @throws IllegalStateException if no column is being defined.
     */
    public TableBuilder notNull() {
        requireCurrentColumn();
        currentColumn.notNull = true;
        return this;
    }

    /**
     * Sets a default value for the current column.
     * <p>
     * Special string values like {@code "CURRENT_TIMESTAMP"} are emitted as-is (not quoted).
     * Numeric and boolean values are emitted without quotes. All other values are quoted.
     * </p>
     *
     * @param val The default value.
     * @return This builder.
     * @throws IllegalStateException if no column is being defined.
     */
    public TableBuilder defaultValue(Object val) {
        requireCurrentColumn();
        currentColumn.defaultValue = val;
        return this;
    }

    /**
     * Adds an index on the specified columns.
     *
     * @param indexColumns The column names to index.
     * @return This builder.
     */
    public TableBuilder index(String... indexColumns) {
        for (String col : indexColumns) {
            validateName(col);
        }
        indexes.add(new IndexDef(indexColumns));
        return this;
    }

    /**
     * Builds and executes the CREATE TABLE statement and any index statements.
     *
     * @throws RuntimeException if the SQL execution fails.
     */
    public void build() {
        finalizeCurrentColumn();

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(renderColumn(columns.get(i)));
        }

        // For MySQL, inline indexes
        if (databaseType == DatabaseType.MYSQL) {
            for (IndexDef idx : indexes) {
                sb.append(", INDEX ").append(idx.indexName(tableName)).append(" (");
                sb.append(String.join(", ", idx.columns));
                sb.append(")");
            }
        }

        sb.append(")");

        if (databaseType == DatabaseType.MYSQL) {
            sb.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }

        try (Connection c = dataSource.getConnection(); Statement stmt = c.createStatement()) {
            stmt.executeUpdate(sb.toString());

            // For PostgreSQL and SQLite, create indexes separately
            if (databaseType != DatabaseType.MYSQL) {
                for (IndexDef idx : indexes) {
                    String idxSql = "CREATE INDEX IF NOT EXISTS " + idx.indexName(tableName) +
                                    " ON " + tableName + " (" + String.join(", ", idx.columns) + ")";
                    stmt.executeUpdate(idxSql);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create table '" + tableName + "' : " + e.getMessage(), e);
        }
    }

    // -- Internal --

    private String renderColumn(ColumnDef col) {
        StringBuilder sb = new StringBuilder();
        sb.append(col.name).append(" ");

        // Handle SERIAL type specially per dialect
        if (col.type == ColumnType.SERIAL) {
            switch (databaseType) {
                case MYSQL -> sb.append("BIGINT PRIMARY KEY AUTO_INCREMENT");
                case POSTGRESQL -> sb.append("BIGSERIAL PRIMARY KEY");
                case SQLITE -> sb.append("INTEGER PRIMARY KEY AUTOINCREMENT");
            }
            return sb.toString();
        }

        sb.append(sqlType(col));

        if (col.notNull) sb.append(" NOT NULL");

        if (col.defaultValue != null) {
            sb.append(" DEFAULT ");
            if (col.defaultValue instanceof Number) {
                sb.append(col.defaultValue);
            } else if (col.defaultValue instanceof Boolean b) {
                sb.append(b ? "1" : "0");
            } else {
                String val = col.defaultValue.toString();
                // SQL keywords/functions are not quoted
                if (val.equalsIgnoreCase("CURRENT_TIMESTAMP") || val.equalsIgnoreCase("NULL")
                        || val.equalsIgnoreCase("TRUE") || val.equalsIgnoreCase("FALSE")) {
                    sb.append(val);
                } else {
                    sb.append("'").append(val.replace("'", "''")).append("'");
                }
            }
        }

        return sb.toString();
    }

    private String sqlType(ColumnDef col) {
        return switch (col.type) {
            case INT -> "INT";
            case BIGINT -> databaseType == DatabaseType.SQLITE ? "INTEGER" : "BIGINT";
            case SERIAL -> ""; // handled above
            case VARCHAR -> "VARCHAR(" + (col.length > 0 ? col.length : 255) + ")";
            case CHAR -> "CHAR(" + (col.length > 0 ? col.length : 36) + ")";
            case TEXT -> "TEXT";
            case BOOLEAN -> databaseType == DatabaseType.SQLITE ? "INTEGER" : "BOOLEAN";
            case DOUBLE -> switch (databaseType) {
                case MYSQL -> "DOUBLE";
                case POSTGRESQL -> "DOUBLE PRECISION";
                case SQLITE -> "REAL";
            };
            case TIMESTAMP -> "TIMESTAMP";
            case DATE -> "DATE";
        };
    }

    private void finalizeCurrentColumn() {
        if (currentColumn != null) {
            columns.add(currentColumn);
            currentColumn = null;
        }
    }

    private void requireCurrentColumn() {
        if (currentColumn == null) {
            throw new IllegalStateException("No column is being defined. Call column() first.");
        }
    }

    private static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: '" + name + "'. Must match [a-zA-Z0-9_]+.");
        }
    }

    // -- Internal data classes --

    private static class ColumnDef {
        final String name;
        final ColumnType type;
        final int length;
        boolean notNull;
        Object defaultValue;

        ColumnDef(String name, ColumnType type, int length) {
            this.name = name;
            this.type = type;
            this.length = length;
        }
    }

    private static class IndexDef {
        final String[] columns;

        IndexDef(String[] columns) {
            this.columns = columns;
        }

        String indexName(String tableName) {
            return "idx_" + tableName + "_" + String.join("_", columns);
        }
    }
}
