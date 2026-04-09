package fr.xyness.XCore.API;

/**
 * Supported SQL column types for the fluent {@link ColumnBuilder} API.
 * <p>
 * Type-to-SQL translation is handled by {@link SqlDialect} implementations,
 * keeping this enum free of any backend-specific logic.
 * </p>
 */
public enum ColumnType {

    INTEGER,
    BIGINT,
    SMALLINT,
    TINYINT,
    FLOAT,
    DOUBLE,
    DECIMAL,
    BOOLEAN,
    VARCHAR,
    CHAR,
    TEXT,
    DATE,
    DATETIME,
    TIMESTAMP
}
