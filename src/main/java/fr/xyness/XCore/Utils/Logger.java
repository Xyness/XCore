package fr.xyness.XCore.Utils;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Custom logger for XCore using Adventure's MiniMessage API.
 * <p>
 * Each log level has its own colored symbol prefix. Messages support
 * MiniMessage formatting tags (e.g. {@code <aqua>}, {@code <bold>}).
 * Debug messages are only printed when debug mode is enabled via {@link #setDebug(boolean)}.
 * </p>
 */
public class Logger {

	/** Whether debug messages should be printed. */
    private boolean DEBUG = false;

	/** Console sender used to dispatch Adventure components. */
    private final ConsoleCommandSender console = Bukkit.getConsoleSender();

	/** MiniMessage parser instance. */
    private static final MiniMessage MINI = MiniMessage.miniMessage();

	/** MiniMessage prefix for informational messages. */
    private final String infoPrefix;

	/** MiniMessage prefix for warning messages. */
    private final String warningPrefix;

	/** MiniMessage prefix for error messages. */
    private final String errorPrefix;

	/** MiniMessage prefix for severe messages. */
    private final String severePrefix;

	/** MiniMessage prefix for debug messages. */
    private final String debugPrefix;

	/** Visual separator bar component. */
    private static final Component BAR = MINI.deserialize("<dark_gray><st>                                                                 </st>");

	/**
	 * Creates a new Logger with prefixes scoped to the given loader name.
	 *
	 * @param loader The subsystem or class name to include in the prefix.
	 */
	public Logger(String loader) {
		String base = "<gray>XCore <dark_gray>@ <gray>" + loader + " <dark_gray>│ ";
		this.infoPrefix = "<green>● " + base + "<gray>";
		this.warningPrefix = "<gold>▲ " + base + "<yellow>";
		this.errorPrefix = "<red>✗ " + base + "<red>";
		this.severePrefix = "<dark_red>✗ " + base + "<dark_red>";
		this.debugPrefix = "<color:#888888>● " + base + "<gray><i>";
	}

	/**
	 * Parses a MiniMessage string with a prefix and sends it to the console.
	 *
	 * @param prefix  The MiniMessage-formatted prefix.
	 * @param message The message (supports MiniMessage tags).
	 */
    private void log(String prefix, String message) {
        console.sendMessage(MINI.deserialize(prefix + message));
    }

	/**
	 * Enables or disables debug message output.
	 *
	 * @param debug {@code true} to enable debug logging.
	 */
    public void setDebug(boolean debug) { this.DEBUG = debug; }

	/**
	 * Logs a debug message (only visible when debug mode is enabled).
	 *
	 * @param message The debug message.
	 */
    public void sendDebug(String message) { if (DEBUG) log(debugPrefix, message); }

	/** Prints a visual separator bar to the console. */
    public void sendRawBar() { console.sendMessage(BAR); }

	/**
	 * Logs an informational message (green prefix).
	 *
	 * @param message The message (supports MiniMessage tags).
	 */
    public void sendInfo(String message) { log(infoPrefix, message); }

	/**
	 * Logs a warning message (gold prefix).
	 *
	 * @param message The message (supports MiniMessage tags).
	 */
    public void sendWarning(String message) { log(warningPrefix, message); }

	/**
	 * Logs an error message (red prefix).
	 *
	 * @param message The message (supports MiniMessage tags).
	 */
    public void sendError(String message) { log(errorPrefix, message); }

	/**
	 * Logs a severe message (dark-red prefix).
	 *
	 * @param message The message (supports MiniMessage tags).
	 */
    public void sendSevere(String message) { log(severePrefix, message); }

	/**
	 * Logs a raw message with no prefix.
	 *
	 * @param message The message (supports MiniMessage tags).
	 */
    public void sendRaw(String message) { console.sendMessage(MINI.deserialize(message)); }
}
