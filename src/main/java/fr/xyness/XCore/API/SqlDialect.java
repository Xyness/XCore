package fr.xyness.XCore.API;

/**
 * Strategy interface for SQL dialect-specific type mappings.
 * <p>
 * Each supported database backend provides its own implementation
 * so that {@link ColumnType} and {@link ColumnBuilder} remain
 * agnostic of backend-specific quirks.
 * </p>
 */
public interface SqlDialect {

    /**
     * Returns the SQL type keyword for the given {@link ColumnType}
     * in this dialect (e.g. {@code "INTEGER"}, {@code "NUMERIC"}).
     *
     * @param type The logical column type.
     * @return The SQL type string for this dialect.
     */
    String toSqlType(ColumnType type);

    /**
     * Returns the SQL literal for a boolean value in this dialect.
     * <p>
     * SQLite uses {@code 1}/{@code 0}, while MySQL and PostgreSQL
     * support {@code TRUE}/{@code FALSE}.
     * </p>
     *
     * @param value The boolean value.
     * @return The SQL literal string.
     */
    String booleanLiteral(boolean value);

    // -------------------------------------------------------------------------
    // Built-in implementations
    // -------------------------------------------------------------------------

    /** SQLite dialect. */
    SqlDialect SQLITE = new SqlDialect() {

        @Override
        public String toSqlType(ColumnType type) {
            return switch (type) {
                case BIGINT, TINYINT, BOOLEAN -> "INTEGER";
                case DECIMAL                  -> "NUMERIC";
                default                       -> type.name();
            };
        }

        @Override
        public String booleanLiteral(boolean value) {
            return value ? "1" : "0";
        }
    };

    /** MySQL dialect. */
    SqlDialect MYSQL = new SqlDialect() {

        @Override
        public String toSqlType(ColumnType type) {
            // MySQL supports all standard names; no overrides needed.
            return type.name();
        }

        @Override
        public String booleanLiteral(boolean value) {
            return value ? "TRUE" : "FALSE";
        }
    };

    /** PostgreSQL dialect. */
    SqlDialect POSTGRESQL = new SqlDialect() {

        @Override
        public String toSqlType(ColumnType type) {
            return switch (type) {
                case DOUBLE   -> "DOUBLE PRECISION";
                case DATETIME -> "TIMESTAMP";
                default       -> type.name();
            };
        }

        @Override
        public String booleanLiteral(boolean value) {
            return value ? "TRUE" : "FALSE";
        }
    };

    /**
     * Returns the built-in {@link SqlDialect} that corresponds to the
     * given {@link DatabaseType}.
     *
     * @param db The database type.
     * @return The matching dialect.
     */
    static SqlDialect of(DatabaseType db) {
        return switch (db) {
            case SQLITE     -> SQLITE;
            case MYSQL      -> MYSQL;
            case POSTGRESQL -> POSTGRESQL;
        };
    }
}
