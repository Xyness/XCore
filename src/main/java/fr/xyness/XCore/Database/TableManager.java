package fr.xyness.XCore.Database;

import java.util.concurrent.Executor;

import javax.sql.DataSource;

import fr.xyness.XCore.API.DatabaseType;

/**
 * Entry point for creating database tables and building queries.
 * <p>
 * Requires a {@link DataSource} (typically HikariCP), a {@link DatabaseType}
 * for SQL dialect handling, and an {@link Executor} for async query execution.
 * </p>
 *
 * <pre>
 * TableManager db = new TableManager(dataSource, DatabaseType.MYSQL, executor);
 *
 * // Create a table
 * db.createTable("xbans_sanctions")
 *     .column("id", ColumnType.SERIAL)
 *     .column("player_uuid", ColumnType.CHAR, 36).notNull()
 *     .column("reason", ColumnType.TEXT)
 *     .index("player_uuid")
 *     .build();
 *
 * // Query data
 * db.query("xbans_sanctions")
 *     .select("player_uuid", "reason")
 *     .where("player_uuid", uuid)
 *     .orderBy("id", true)
 *     .limit(10)
 *     .executeAsync();
 * </pre>
 */
public class TableManager {

    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final Executor executor;

    /**
     * Creates a new TableManager.
     *
     * @param dataSource   The database connection pool.
     * @param databaseType The database type for SQL dialect handling.
     * @param executor     The async executor for query operations.
     */
    public TableManager(DataSource dataSource, DatabaseType databaseType, Executor executor) {
        this.dataSource = dataSource;
        this.databaseType = databaseType;
        this.executor = executor;
    }

    /**
     * Starts building a CREATE TABLE statement for the given table name.
     *
     * @param tableName The table name. Must match {@code [a-zA-Z0-9_]+}.
     * @return A new {@link TableBuilder} for the table.
     */
    public TableBuilder createTable(String tableName) {
        return new TableBuilder(dataSource, databaseType, tableName);
    }

    /**
     * Starts building a query (SELECT, INSERT, UPDATE, or DELETE) for the given table.
     *
     * @param tableName The table name. Must match {@code [a-zA-Z0-9_]+}.
     * @return A new {@link QueryBuilder} for the table.
     */
    public QueryBuilder query(String tableName) {
        return new QueryBuilder(dataSource, databaseType, executor, tableName);
    }

    /** @return The database type. */
    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    /** @return The data source. */
    public DataSource getDataSource() {
        return dataSource;
    }

    /** @return The async executor. */
    public Executor getExecutor() {
        return executor;
    }
}
