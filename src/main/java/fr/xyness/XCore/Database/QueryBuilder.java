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

    /** Same, but allowing one {@code table.column} qualification — needed once joins exist. */
    private static final Pattern VALID_QUALIFIED = Pattern.compile("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)?");

    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final Executor executor;
    private final String tableName;

    private enum QueryType { SELECT, INSERT, UPDATE, DELETE }

    private QueryType queryType;
    private String[] selectColumns;
    private final List<SetClause> setClauses = new ArrayList<>();
    private final List<WhereClause> whereClauses = new ArrayList<>();
    private final List<JoinClause> joinClauses = new ArrayList<>();
    private final List<List<Object>> batchRows = new ArrayList<>();
    private String orderByColumn;
    private boolean orderByDesc;
    private int limitValue = -1;
    private int offsetValue = -1;

    /** Whether the next {@code where} is joined with OR instead of AND. */
    private boolean nextIsOr = false;

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
            for (String col : columns) validateQualified(col);
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
        setClauses.add(new SetClause(column, value, false));
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
        validateQualified(column);
        whereClauses.add(new WhereClause(column, "=", value, nextIsOr));
        nextIsOr = false;
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
        validateQualified(column);
        validateOperator(operator);
        whereClauses.add(new WhereClause(column, operator, value, nextIsOr));
        nextIsOr = false;
        return this;
    }

    /**
     * Adds a {@code column IN (...)} clause. Multiple calls are joined with AND.
     *
     * <p>The parameters are still bound one by one — only the placeholder list is generated — so a
     * value containing a quote is as harmless here as anywhere else in this builder.</p>
     *
     * @param column The column name.
     * @param values The accepted values. An empty collection makes the query match nothing.
     * @return This builder.
     */
    public QueryBuilder whereIn(String column, java.util.Collection<?> values) {
        validateQualified(column);
        whereClauses.add(new WhereClause(column, "IN", new ArrayList<>(values), nextIsOr));
        nextIsOr = false;
        return this;
    }

    /**
     * Joins the <em>next</em> {@code where} clause with OR instead of AND.
     *
     * <pre>{@code query.where("a", 1).or().where("b", 2)  ->  WHERE a = ? OR b = ?}</pre>
     *
     * @return This builder.
     */
    public QueryBuilder or() {
        nextIsOr = true;
        return this;
    }

    /**
     * Adds an INNER JOIN.
     *
     * @param table       The table to join.
     * @param leftColumn  The column on this builder's table, qualified or not.
     * @param rightColumn The column on the joined table, qualified or not.
     * @return This builder.
     */
    public QueryBuilder join(String table, String leftColumn, String rightColumn) {
        return join("INNER", table, leftColumn, rightColumn);
    }

    /**
     * Adds a LEFT JOIN.
     *
     * @param table       The table to join.
     * @param leftColumn  The column on this builder's table.
     * @param rightColumn The column on the joined table.
     * @return This builder.
     */
    public QueryBuilder leftJoin(String table, String leftColumn, String rightColumn) {
        return join("LEFT", table, leftColumn, rightColumn);
    }

    private QueryBuilder join(String type, String table, String leftColumn, String rightColumn) {
        validateName(table);
        validateQualified(leftColumn);
        validateQualified(rightColumn);
        joinClauses.add(new JoinClause(type, table, qualify(leftColumn), qualify(rightColumn, table)));
        return this;
    }

    /** Qualifies a bare column with this builder's table. */
    private String qualify(String column) {
        return column.indexOf('.') >= 0 ? column : tableName + "." + column;
    }

    /** Qualifies a bare column with the given table. */
    private static String qualify(String column, String table) {
        return column.indexOf('.') >= 0 ? column : table + "." + column;
    }

    /**
     * Adds a relative SET clause: {@code column = COALESCE(column, 0) + delta}.
     *
     * <p>The arithmetic happens inside the database, so two servers applying a delta to the same row
     * cannot both read the old value and write back a total that loses the other's change. Every
     * counter — balances, statistics, stock — wants this rather than read-modify-write.</p>
     *
     * @param column The column name.
     * @param delta  The signed amount to apply.
     * @return This builder.
     */
    public QueryBuilder setRelative(String column, Number delta) {
        validateName(column);
        setClauses.add(new SetClause(column, delta, true));
        return this;
    }

    /**
     * Queues one row for a batch INSERT. Every row must set the same columns, in the same order.
     *
     * <pre>{@code
     * query.insert()
     *      .addRow(Map.of("uuid", a, "score", 1))
     *      .addRow(Map.of("uuid", b, "score", 2))
     *      .executeBatchAsync();
     * }</pre>
     *
     * @param row The column/value pairs for this row.
     * @return This builder.
     */
    public QueryBuilder addRow(Map<String, Object> row) {
        if (batchRows.isEmpty()) {
            // The first row fixes the column list for the whole batch.
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                validateName(entry.getKey());
                setClauses.add(new SetClause(entry.getKey(), null, false));
            }
        }
        List<Object> values = new ArrayList<>(setClauses.size());
        for (SetClause clause : setClauses) values.add(row.get(clause.column));
        batchRows.add(values);
        return this;
    }

    /**
     * Executes every row queued with {@link #addRow(Map)} as a single batch.
     *
     * @return A future completing with the number of rows written.
     */
    public CompletableFuture<Integer> executeBatchAsync() {
        if (queryType != QueryType.INSERT) {
            throw new IllegalStateException("executeBatchAsync() requires insert().");
        }
        if (batchRows.isEmpty()) return CompletableFuture.completedFuture(0);

        StringBuilder sb = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < setClauses.size(); i++) {
            if (i > 0) { sb.append(", "); values.append(", "); }
            sb.append(setClauses.get(i).column);
            values.append("?");
        }
        sb.append(") VALUES (").append(values).append(")");
        String sql = sb.toString();
        List<List<Object>> rows = new ArrayList<>(batchRows);

        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                for (List<Object> row : rows) {
                    for (int i = 0; i < row.size(); i++) ps.setObject(i + 1, row.get(i));
                    ps.addBatch();
                }
                int written = 0;
                for (int count : ps.executeBatch()) if (count > 0) written += count;
                return written;
            } catch (SQLException e) {
                throw new RuntimeException("Batch insert failed : " + e.getMessage(), e);
            }
        }, executor);
    }

    /**
     * Adds an ORDER BY clause.
     *
     * @param column The column to sort by.
     * @param desc   {@code true} for descending, {@code false} for ascending.
     * @return This builder.
     */
    public QueryBuilder orderBy(String column, boolean desc) {
        validateQualified(column);
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
            appendJoins(sb);
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
                appendJoins(sb);
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
                    SetClause clause = setClauses.get(i);
                    if (i > 0) sb.append(", ");
                    if (clause.relative) {
                        // COALESCE: a column added to an existing table is NULL on older rows, and
                        // NULL + n is NULL.
                        sb.append(clause.column).append(" = COALESCE(").append(clause.column).append(", 0) + ?");
                    } else {
                        sb.append(clause.column).append(" = ?");
                    }
                    params.add(clause.value);
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
        if (whereClauses.isEmpty()) return;
        sb.append(" WHERE ");
        for (int i = 0; i < whereClauses.size(); i++) {
            WhereClause w = whereClauses.get(i);
            if (i > 0) sb.append(w.or ? " OR " : " AND ");
            if ("IN".equals(w.operator)) {
                List<?> values = (List<?>) w.value;
                if (values.isEmpty()) {
                    // An empty IN list is not valid SQL, and "match nothing" is what the caller asked
                    // for by passing an empty collection.
                    sb.append("1 = 0");
                    continue;
                }
                sb.append(w.column).append(" IN (");
                for (int v = 0; v < values.size(); v++) {
                    if (v > 0) sb.append(", ");
                    sb.append("?");
                    params.add(values.get(v));
                }
                sb.append(")");
                continue;
            }
            sb.append(w.column).append(" ").append(w.operator).append(" ?");
            params.add(w.value);
        }
    }

    /** Appends every JOIN declared on this builder. */
    private void appendJoins(StringBuilder sb) {
        for (JoinClause join : joinClauses) {
            sb.append(" ").append(join.type).append(" JOIN ").append(join.table)
              .append(" ON ").append(join.left).append(" = ").append(join.right);
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

    /** Validates a possibly {@code table.column} qualified identifier. */
    private static void validateQualified(String name) {
        if (name == null || !VALID_QUALIFIED.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: '" + name
                    + "'. Must match [a-zA-Z0-9_]+ optionally qualified with a table.");
        }
    }

    private static void validateOperator(String operator) {
        String op = operator.trim().toUpperCase();
        // IN / IS / IS NOT are deliberately absent: appendWhere() renders every clause as
        // "column <op> ?", which is not valid SQL for any of them.
        if (!op.matches("=|!=|<>|<|>|<=|>=|LIKE|NOT LIKE")) {
            throw new IllegalArgumentException("Invalid SQL operator: '" + operator + "'.");
        }
    }

    // -- Internal types --

    private record SetClause(String column, Object value, boolean relative) {}
    private record WhereClause(String column, String operator, Object value, boolean or) {}
    private record JoinClause(String type, String table, String left, String right) {}
    private record SqlAndParams(String sql, List<Object> params) {}
}
