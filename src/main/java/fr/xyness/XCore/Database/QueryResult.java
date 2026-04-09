package fr.xyness.XCore.Database;

import java.util.Collections;
import java.util.Map;

/**
 * Wrapper for a single database row returned by a query.
 * <p>
 * Provides type-safe accessors for common column types. The underlying data
 * is stored as a map of column name to value (as returned by JDBC).
 * </p>
 */
public class QueryResult {

    private final Map<String, Object> data;

    /**
     * Creates a new QueryResult wrapping the given row data.
     *
     * @param data The column name to value map. Must not be {@code null}.
     */
    public QueryResult(Map<String, Object> data) {
        this.data = data;
    }

    /**
     * Returns the raw data map (unmodifiable).
     *
     * @return An unmodifiable view of the row data.
     */
    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    /**
     * Retrieves a column value with type-safe casting.
     *
     * @param column The column name.
     * @param type   The expected class type.
     * @param <T>    The return type.
     * @return The value cast to the requested type, or {@code null} if not present or wrong type.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String column, Class<T> type) {
        Object value = data.get(column);
        if (value == null) return null;
        if (type.isInstance(value)) return (T) value;
        // Handle numeric conversions (JDBC drivers may return different Number types)
        if (value instanceof Number n) {
            if (type == Integer.class || type == int.class) return (T) Integer.valueOf(n.intValue());
            if (type == Long.class || type == long.class) return (T) Long.valueOf(n.longValue());
            if (type == Double.class || type == double.class) return (T) Double.valueOf(n.doubleValue());
            if (type == Float.class || type == float.class) return (T) Float.valueOf(n.floatValue());
            if (type == Short.class || type == short.class) return (T) Short.valueOf(n.shortValue());
            if (type == Byte.class || type == byte.class) return (T) Byte.valueOf(n.byteValue());
        }
        // Handle String conversion
        if (type == String.class) return (T) String.valueOf(value);
        return null;
    }

    /**
     * Retrieves a column value as a String.
     *
     * @param column The column name.
     * @return The value as a String, or {@code null} if not present.
     */
    public String getString(String column) {
        Object value = data.get(column);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * Retrieves a column value as an int.
     *
     * @param column The column name.
     * @return The value as an int, or {@code 0} if not present or not a number.
     */
    public int getInt(String column) {
        Object value = data.get(column);
        if (value instanceof Number n) return n.intValue();
        return 0;
    }

    /**
     * Retrieves a column value as a long.
     *
     * @param column The column name.
     * @return The value as a long, or {@code 0L} if not present or not a number.
     */
    public long getLong(String column) {
        Object value = data.get(column);
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }

    /**
     * Retrieves a column value as a double.
     *
     * @param column The column name.
     * @return The value as a double, or {@code 0.0} if not present or not a number.
     */
    public double getDouble(String column) {
        Object value = data.get(column);
        if (value instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    /**
     * Retrieves a column value as a boolean.
     *
     * @param column The column name.
     * @return The value as a boolean, or {@code false} if not present.
     */
    public boolean getBoolean(String column) {
        Object value = data.get(column);
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return false;
    }

    /**
     * Checks if a column exists in this result row.
     *
     * @param column The column name.
     * @return {@code true} if the column is present (even if its value is null).
     */
    public boolean hasColumn(String column) {
        return data.containsKey(column);
    }

    @Override
    public String toString() {
        return "QueryResult" + data;
    }
}
