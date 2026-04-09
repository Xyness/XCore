package fr.xyness.XCore.API;

import java.util.ArrayList;
import java.util.List;

import fr.xyness.XCore.XCore;

/**
 * Fluent builder for adding columns to the {@code players} table.
 * <p>
 * Example usage:
 * <pre>{@code
 * api.columnBuilder()
 *     .addColumn("coins", ColumnType.INTEGER).defaultValue(0).notNull()
 *     .addColumn("rank", ColumnType.VARCHAR).length(32).defaultValue("default")
 *     .apply();
 * }</pre>
 * </p>
 */
public class ColumnBuilder {

    private final XCore main;
    private final List<ColumnDef> columns = new ArrayList<>();

    /**
     * Creates a new ColumnBuilder.
     *
     * @param main The main plugin instance used to apply column changes.
     */
    public ColumnBuilder(XCore main) {
        this.main = main;
    }

    /**
     * Adds a column definition to the builder.
     *
     * @param name The column name.
     * @param type The column type.
     * @return This builder for chaining.
     */
    public ColumnBuilder addColumn(String name, ColumnType type) {
        columns.add(new ColumnDef(name, type));
        return this;
    }

    /**
     * Sets the length for the last added column (applies to VARCHAR, CHAR, DECIMAL).
     *
     * @param length The column length.
     * @return This builder for chaining.
     */
    public ColumnBuilder length(int length) {
        lastColumn().length = length;
        return this;
    }

    /**
     * Sets the scale for the last added column (applies to DECIMAL).
     *
     * @param scale The decimal scale.
     * @return This builder for chaining.
     */
    public ColumnBuilder scale(int scale) {
        lastColumn().scale = scale;
        return this;
    }

    /**
     * Sets a default value for the last added column.
     *
     * @param value The default value (String, Number, Boolean, or {@code null}).
     * @return This builder for chaining.
     */
    public ColumnBuilder defaultValue(Object value) {
        lastColumn().defaultValue = value;
        return this;
    }

    /**
     * Marks the last added column as NOT NULL.
     *
     * @return This builder for chaining.
     */
    public ColumnBuilder notNull() {
        lastColumn().notNull = true;
        return this;
    }

    /**
     * Applies all column definitions to the default {@code players} table.
     *
     * @return {@code true} if at least one column was added.
     */
    public boolean apply() {
        return apply("players");
    }

    /**
     * Applies all column definitions to the specified table.
     *
     * @param table The target table name.
     * @return {@code true} if at least one column was added.
     */
    public boolean apply(String table) {
        SqlDialect dialect = main.getDialect();
        boolean anyAdded = false;
        for (ColumnDef col : columns) {
            if (main.methods().addColumnIfMissing(table, col.name, col.toSqlDefinition(dialect))) {
                anyAdded = true;
            }
            main.playerDAO().registerExtraColumn(col.name);
        }
        return anyAdded;
    }

    private ColumnDef lastColumn() {
        if (columns.isEmpty()) {
            throw new IllegalStateException("No column added yet. Call addColumn() first.");
        }
        return columns.get(columns.size() - 1);
    }

    // -------------------------------------------------------------------------

    private static class ColumnDef {

        final String name;
        final ColumnType type;
        int length = -1;
        int scale = -1;
        Object defaultValue;
        boolean notNull;

        ColumnDef(String name, ColumnType type) {
            this.name = name;
            this.type = type;
        }

        String toSqlDefinition(SqlDialect dialect) {
            StringBuilder sb = new StringBuilder(dialect.toSqlType(type));

            if (length > 0) {
                sb.append("(").append(length);
                if (scale >= 0) sb.append(", ").append(scale);
                sb.append(")");
            }

            if (notNull) {
                sb.append(" NOT NULL");
            }

            if (defaultValue != null) {
                sb.append(" DEFAULT ");
                if (defaultValue instanceof String s) {
                    sb.append("'").append(s.replace("'", "''")).append("'");
                } else if (defaultValue instanceof Boolean b) {
                    sb.append(dialect.booleanLiteral(b));
                } else {
                    sb.append(defaultValue);
                }
            }

            return sb.toString();
        }
    }
}
