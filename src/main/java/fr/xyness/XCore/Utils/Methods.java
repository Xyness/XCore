package fr.xyness.XCore.Utils;

import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.API.DatabaseType;

/**
 * Shared utility methods used across the plugin.
 * <p>
 * Provides number formatting, UUID manipulation, JSON parsing,
 * and dynamic database column management.
 * </p>
 */
public class Methods {

	/** Reference to the main plugin instance. */
    private final XCore main;

	/** Regex pattern for validating SQL identifiers (table/column names). */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

	/** Whitelist regex for SQL column definitions. Only allows safe SQL types with optional size, DEFAULT and NOT NULL. */
    private static final java.util.regex.Pattern SAFE_DEFINITION = java.util.regex.Pattern.compile(
	    "^(INT|INTEGER|BIGINT|SMALLINT|TINYINT|FLOAT|DOUBLE PRECISION|DOUBLE|DECIMAL|NUMERIC|BOOLEAN|BOOL|SERIAL|REAL" +
	    "|VARCHAR|CHAR|TEXT|TINYTEXT|MEDIUMTEXT|LONGTEXT" +
	    "|DATE|TIME|DATETIME|TIMESTAMP)" +
	    "(\\(\\d+(?:,\\s*\\d+)?\\))?" +
	    "(\\s+NOT\\s+NULL)?" +
	    "(\\s+DEFAULT\\s+('([^']*)'|\\d+(?:\\.\\d+)?|NULL|TRUE|FALSE|0|1))?" +
	    "$", java.util.regex.Pattern.CASE_INSENSITIVE
	);

	/**
	 * Creates a new Methods instance.
	 *
	 * @param main The main plugin instance.
	 */
	public Methods(XCore main) {
		this.main = main;
	}

	/**
	 * Formats a long number with commas as thousand separators.
	 * Example: {@code 1234567} &rarr; {@code "1,234,567"}.
	 *
	 * @param number The number to format.
	 * @return The formatted string with comma separators.
	 */
    public String getNumberSeparate(long number) {
    	String text = String.valueOf(number);
        StringBuilder sb = new StringBuilder(text);
        for (int i = sb.length() - 3; i > 0; i -= 3) {
            sb.insert(i, ',');
        }
        return sb.toString();
    }

	/**
	 * Safely parses JSON from an {@link InputStreamReader}, logging errors with context.
	 *
	 * @param reader  The reader to parse from.
	 * @param context A description of what is being parsed (for error messages).
	 * @return The parsed {@link JsonObject}, or {@code null} on failure.
	 */
	public JsonObject safeParseJson(InputStreamReader reader, String context) {
	    try {
	        return JsonParser.parseReader(reader).getAsJsonObject();
	    } catch (Exception e) {
	        main.logger().sendError("Failed to parse JSON (" + context + ") : " + e.getMessage());
	        return null;
	    }
	}

	/**
	 * Inserts dashes into a 32-character UUID string to produce the standard 36-character format.
	 * If the input is not 32 characters, it is returned unchanged.
	 *
	 * @param uuid The UUID string, potentially without dashes.
	 * @return The UUID string with dashes inserted.
	 */
    public String addDashesToUUID(String uuid) {
        if (uuid.length() == 32) {
            return uuid.replaceFirst(
                    "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                    "$1-$2-$3-$4-$5"
            );
        }
        return uuid;
    }

	/**
	 * Adds a column to a database table if it does not already exist.
	 * <p>
	 * Supports all three database backends:
	 * <ul>
	 *   <li><b>SQLite</b>: Uses {@code PRAGMA table_info()} to check column existence.</li>
	 *   <li><b>MySQL</b>: Queries {@code information_schema.columns} filtered by {@code DATABASE()}.</li>
	 *   <li><b>PostgreSQL</b>: Queries {@code information_schema.columns} filtered by {@code current_schema()}.</li>
	 * </ul>
	 * Table/column names and definitions are validated against safe patterns to prevent SQL injection.
	 * </p>
	 *
	 * @param table      The table name.
	 * @param column     The column name to add.
	 * @param definition The SQL column definition (e.g. {@code "VARCHAR(255) DEFAULT ''"}).
	 * @return {@code true} if the column was added, {@code false} if it already existed or on error.
	 */
    public boolean addColumnIfMissing(String table, String column, String definition) {
        if (!SAFE_IDENTIFIER.matcher(table).matches() || !SAFE_IDENTIFIER.matcher(column).matches()) {
            main.logger().sendError("Invalid table or column name: table=" + table + ", column=" + column);
            return false;
        }
        if (!SAFE_DEFINITION.matcher(definition).matches()) {
            main.logger().sendError("Invalid column definition: " + definition);
            return false;
        }

        boolean exists = false;
        DatabaseType dbType = main.getDatabaseType();

        try (Connection connection = main.getDataSource().getConnection()) {

            if (dbType.equals(DatabaseType.SQLITE)) {
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ");")) {
                    while (rs.next()) {
                        if (column.equalsIgnoreCase(rs.getString("name"))) {
                            exists = true;
                            break;
                        }
                    }
                }
            } else {
                String schemaFunc = dbType.equals(DatabaseType.MYSQL) ? "DATABASE()" : "current_schema()";
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = " + schemaFunc + " AND table_name = ? AND column_name = ?")) {
                    ps.setString(1, table);
                    ps.setString(2, column);
                    try (ResultSet rs = ps.executeQuery()) {
                        exists = rs.next();
                    }
                }
            }

            if (!exists) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition + ";");
                }
                return true;
            }

        } catch (SQLException e) {
            main.logger().sendError("Error adding column " + column + " to " + table + " : " + e.getMessage());
        }
        return false;
    }

}
