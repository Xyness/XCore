package fr.xyness.XCore.Database;

/**
 * Column types supported by the {@link TableBuilder}.
 * <p>
 * Maps to appropriate SQL types for each {@link DatabaseType}.
 * </p>
 */
public enum ColumnType {

    /** Integer type. Maps to INT (MySQL), INTEGER (PostgreSQL/SQLite). */
    INT,

    /** Big integer type. Maps to BIGINT (MySQL/PostgreSQL), INTEGER (SQLite). */
    BIGINT,

    /** Auto-incrementing primary key. Maps to BIGINT AUTO_INCREMENT (MySQL), BIGSERIAL (PostgreSQL), INTEGER AUTOINCREMENT (SQLite). */
    SERIAL,

    /** Variable-length string. Requires a length parameter. Maps to VARCHAR(n). */
    VARCHAR,

    /** Fixed-length string. Requires a length parameter. Maps to CHAR(n). */
    CHAR,

    /** Unlimited text. Maps to TEXT. */
    TEXT,

    /** Boolean type. Maps to BOOLEAN (MySQL/PostgreSQL), INTEGER (SQLite). */
    BOOLEAN,

    /** Double-precision floating point. Maps to DOUBLE (MySQL), DOUBLE PRECISION (PostgreSQL), REAL (SQLite). */
    DOUBLE,

    /** Timestamp type. Maps to TIMESTAMP. */
    TIMESTAMP,

    /** Date type. Maps to DATE. */
    DATE
}
