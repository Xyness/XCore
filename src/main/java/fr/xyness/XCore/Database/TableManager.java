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

    /**
     * Runs several statements as one transaction, off the calling thread.
     *
     * <p>Either everything in the body is written or none of it is. That is what a purchase needs:
     * taking the money and handing over the item are two statements, and a crash between them
     * leaves a player robbed. Throw from the body to roll back on purpose.</p>
     *
     * <pre>{@code
     * db.transaction(conn -> {
     *     try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET coins = coins - ? WHERE server_uuid = ? AND coins >= ?")) {
     *         ...
     *         if (ps.executeUpdate() == 0) throw new SQLException("not enough coins");
     *     }
     *     ...
     *     return true;
     * });
     * }</pre>
     *
     * @param <T>  What the body returns.
     * @param body The statements to run on the shared connection.
     * @return A future completing with the body's result, or failing if it was rolled back.
     */
    public <T> java.util.concurrent.CompletableFuture<T> transaction(TransactionBody<T> body) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (java.sql.Connection conn = dataSource.getConnection()) {
                boolean previous = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    T result = body.run(conn);
                    conn.commit();
                    return result;
                } catch (Exception e) {
                    try {
                        conn.rollback();
                    } catch (java.sql.SQLException rollbackFailed) {
                        throw new RuntimeException("Transaction failed and could not be rolled back : "
                                + rollbackFailed.getMessage(), e);
                    }
                    throw e instanceof RuntimeException re ? re
                            : new RuntimeException("Transaction rolled back : " + e.getMessage(), e);
                } finally {
                    try { conn.setAutoCommit(previous); } catch (java.sql.SQLException ignored) {}
                }
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("Could not open a transaction : " + e.getMessage(), e);
            }
        }, executor);
    }

    /**
     * The body of a {@link #transaction(TransactionBody)}.
     *
     * @param <T> What it returns.
     */
    @FunctionalInterface
    public interface TransactionBody<T> {
        /**
         * @param connection The connection every statement of the transaction must use.
         * @return Whatever the caller wants back.
         * @throws Exception to roll the transaction back.
         */
        T run(java.sql.Connection connection) throws Exception;
    }

    /**
     * Returns the schema migrator for a set of tables.
     *
     * @param namespace An identifier for the addon, used as the key of its schema version.
     * @return A migrator scoped to that namespace.
     */
    public SchemaMigrator migrator(String namespace) {
        return new SchemaMigrator(dataSource, databaseType, namespace);
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
