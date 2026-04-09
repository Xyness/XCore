package fr.xyness.XCore.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import fr.xyness.XCore.API.DatabaseType;

/**
 * Fluent query builder for SELECT, INSERT, UPDATE, and DELETE operations.
 * <p>
 * All queries use {@link PreparedStatement} with parameter binding to prevent
 * SQL injection. Column and table names are validated against {@code [a-zA-Z0-9_]+}.
 * </p>
 *
 * <pre>
 * // SELECT
 * List&lt;QueryResult&gt; results = queryBuilder
 *     .select("name", "score")
 *     .where("team", "red")
 *     .orderBy("score", true)
 *     .limit(10)
 *     .executeAsync().join();
 *
 * // INSERT
 * queryBuilder
 *     .insert()
 *     .set("name", "Steve")
 *     .set("score", 100)
 *     .executeUpdateAsync().join();
 *
 * // UPDATE
 * queryBuilder
 *     .update()
 *     .set("score", 200)
 *     .where("name", "Steve")
 *     .executeUpdateAsync().join();
 *
 * // DELETE
 * queryBuilder
 *     .delete()
 *     .where("name", "Steve")
 *     .executeUpdateAsync().join();
 * </pre>
 */
public class QueryBuilder {

    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9_]+");

    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final Executor executor;
    private final String tableName;

    private enum QueryType { SELECT, INSERT, UPDATE, DELETE }

    private QueryType queryType;
    private String[] selectColumns;
    private final List<SetClause> setClauses = new ArrayList<>();
    private final List<WhereClause> whereClauses = new ArrayList<>();
    private String orderByColumn;
    private boolean orderByDesc;
    private int limitValue = -1;
    private int offsetValue = -1;

    QueryBuilder(DataSource dataSource, DatabaseType databaseType, Executor executor, String tableName) {
        validateName(tableName);
        this.dataSource = dataSource;
        this.databaseType = databaseType;
        this.executor = executor;
        this.tableName = tableName;
    }

    /**
     * Configures this query as a SELECT.
     *
     * @param columns The columns to select. If empty, selects all ({@code *}).
     * @return This builder.
     */
    public QueryBuilder select(String... columns) {
        this.queryType = QueryType.SELECT;
        if (columns.length > 0) {
            for (String col : columns) validateName(col);
            this.selectColumns = columns;
        }
        return this;
    }

    /**
     * Configures this query as an INSERT.
     *
     * @return This builder.
     */
    public QueryBuilder insert() {
        this.queryType = QueryType.INSERT;
        return this;
    }

    /**
     * Configures this query as an UPDATE.
     *
     * @return This builder.
     */
    public QueryBuilder update() {
        this.queryType = QueryType.UPDATE;
        return this;
    }

    /**
     * Configures this query as a DELETE.
     *
     * @return This builder.
     */
    public QueryBuilder delete() {
        this.queryType = QueryType.DELETE;
        return this;
    }

    /**
     * Adds a SET clause (for INSERT and UPDATE).
     *
     * @param column The column name.
     * @param value  The value to set.
     * @return This builder.
     */
    public QueryBuilder set(String column, Object value) {
        validateName(column);
        setClauses.add(new SetClause(column, value));
        return this;
    }

    /**
     * Adds a WHERE clause (equality check). Multiple calls are joined with AND.
     *
     * @param column The column name.
     * @param value  The value to match.
     * @return This builder.
     */
    public QueryBuilder where(String column, Object value) {
        validateName(column);
        whereClauses.add(new WhereClause(column, "=", value));
        return this;
    }

    /**
     * Adds a WHERE clause with a custom operator (e.g. {@code ">", "<", ">=", "<=", "!=", "LIKE"}).
     *
     * @param column   The column name.
     * @param operator The SQL operator.
     * @param value    The value to match.
     * @return This builder.
     */
    public QueryBuilder where(String column, String operator, Object value) {
        validateName(column);
        validateOperator(operator);
        whereClauses.add(new WhereClause(column, operator, value));
        return this;
    }

    /**
     * Adds an ORDER BY clause.
     *
     * @param column The column to sort by.
     * @param desc   {@code true} for descending, {@code false} for ascending.
     * @return This builder.
     */
    public QueryBuilder orderBy(String column, boolean desc) {
        validateName(column);
        this.orderByColumn = column;
        this.orderByDesc = desc;
        return this;
    }

    /**
     * Sets the LIMIT for the query.
     *
     * @param limit The maximum number of rows to return.
     * @return This builder.
     */
    public QueryBuilder limit(int limit) {
        this.limitValue = limit;
        return this;
    }

    /**
     * Sets the OFFSET for the query.
     *
     * @param offset The number of rows to skip.
     * @return This builder.
     */
    public QueryBuilder offset(int offset) {
        this.offsetValue = offset;
        return this;
    }

    /**
     * Executes a SELECT query asynchronously and returns the results.
     *
     * @return A future containing the list of {@link QueryResult} rows.
     */
    public CompletableFuture<List<QueryResult>> executeAsync() {
        return CompletableFuture.supplyAsync(() -> {
            SqlAndParams sp = buildSql();
            List<QueryResult> results = new ArrayList<>();
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sp.sql)) {
                bindParams(ps, sp.params);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            String colName = meta.getColumnLabel(i);
                            Object value = rs.getObject(i);
                            if (value != null) {
                                row.put(colName, value);
                            }
                        }
                        results.add(new QueryResult(row));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Query execution failed : " + e.getMessage(), e);
            }
            return results;
        }, executor);
    }

    /**
     * Executes an INSERT, UPDATE, or DELETE query asynchronously.
     *
     * @return A future that completes when the operation finishes.
     */
    public CompletableFuture<Void> executeUpdateAsync() {
        return CompletableFuture.runAsync(() -> {
            SqlAndParams sp = buildSql();
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sp.sql)) {
                bindParams(ps, sp.params);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Update execution failed : " + e.getMessage(), e);
            }
        }, executor);
    }

    /**
     * Executes a SELECT COUNT(*) query asynchronously.
     *
     * @return A future containing the count.
     */
    public CompletableFuture<Integer> executeCountAsync() {
        return CompletableFuture.supplyAsync(() -> {
            // Build a count query based on the WHERE clauses
            StringBuilder sb = new StringBuilder();
            sb.append("SELECT COUNT(*) FROM ").append(tableName);
            List<Object> params = new ArrayList<>();
            appendWhere(sb, params);

            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sb.toString())) {
                bindParams(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                    return 0;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Count query failed : " + e.getMessage(), e);
            }
        }, executor);
    }

    // -- SQL building --

    private SqlAndParams buildSql() {
        if (queryType == null) {
            throw new IllegalStateException("Query type not set. Call select(), insert(), update(), or delete().");
        }

        StringBuilder sb = new StringBuilder();
        List<Object> params = new ArrayList<>();

        switch (queryType) {
            case SELECT -> {
                sb.append("SELECT ");
                if (selectColumns != null && selectColumns.length > 0) {
                    sb.append(String.join(", ", selectColumns));
                } else {
                    sb.append("*");
                }
                sb.append(" FROM ").append(tableName);
                appendWhere(sb, params);
                if (orderByColumn != null) {
                    sb.append(" ORDER BY ").append(orderByColumn).append(orderByDesc ? " DESC" : " ASC");
                }
                if (limitValue >= 0) {
                    sb.append(" LIMIT ").append(limitValue);
                }
                if (offsetValue >= 0) {
                    sb.append(" OFFSET ").append(offsetValue);
                }
            }
            case INSERT -> {
                if (setClauses.isEmpty()) {
                    throw new IllegalStateException("INSERT requires at least one set() clause.");
                }
                sb.append("INSERT INTO ").append(tableName).append(" (");
                StringBuilder values = new StringBuilder();
                for (int i = 0; i < setClauses.size(); i++) {
                    if (i > 0) { sb.append(", "); values.append(", "); }
                    sb.append(setClauses.get(i).column);
                    values.append("?");
                    params.add(setClauses.get(i).value);
                }
                sb.append(") VALUES (").append(values).append(")");
            }
            case UPDATE -> {
                if (setClauses.isEmpty()) {
                    throw new IllegalStateException("UPDATE requires at least one set() clause.");
                }
                sb.append("UPDATE ").append(tableName).append(" SET ");
                for (int i = 0; i < setClauses.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(setClauses.get(i).column).append(" = ?");
                    params.add(setClauses.get(i).value);
                }
                appendWhere(sb, params);
            }
            case DELETE -> {
                sb.append("DELETE FROM ").append(tableName);
                appendWhere(sb, params);
            }
        }

        return new SqlAndParams(sb.toString(), params);
    }

    private void appendWhere(StringBuilder sb, List<Object> params) {
        if (!whereClauses.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < whereClauses.size(); i++) {
                if (i > 0) sb.append(" AND ");
                WhereClause w = whereClauses.get(i);
                sb.append(w.column).append(" ").append(w.operator).append(" ?");
                params.add(w.value);
            }
        }
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: '" + name + "'. Must match [a-zA-Z0-9_]+.");
        }
    }

    private static void validateOperator(String operator) {
        String op = operator.trim().toUpperCase();
        if (!op.matches("=|!=|<>|<|>|<=|>=|LIKE|NOT LIKE|IN|IS|IS NOT")) {
            throw new IllegalArgumentException("Invalid SQL operator: '" + operator + "'.");
        }
    }

    // -- Internal types --

    private record SetClause(String column, Object value) {}
    private record WhereClause(String column, String operator, Object value) {}
    private record SqlAndParams(String sql, List<Object> params) {}
}
