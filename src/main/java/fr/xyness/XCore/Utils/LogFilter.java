package fr.xyness.XCore.Utils;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/**
 * Log4j filter that suppresses all log messages originating from HikariCP.
 * <p>
 * HikariCP produces verbose connection pool lifecycle logs that clutter the
 * server console. This filter is attached to the root logger and silently
 * drops any event whose logger name contains "Hikari".
 * </p>
 */
public class LogFilter extends AbstractFilter {

	/**
	 * Registers this filter on the Log4j root logger.
	 * Should be called once during plugin startup, before the HikariCP pool is created.
	 */
    public static void registerFilter() {
        Logger logger = (Logger) LogManager.getRootLogger();
        logger.addFilter(new LogFilter());
    }

	/**
	 * Filters log events by logger name. Events from Hikari loggers are denied.
	 *
	 * @param event The log event to evaluate.
	 * @return {@link Result#DENY} for Hikari events, {@link Result#NEUTRAL} otherwise.
	 */
    @Override
    public Result filter(LogEvent event) {
        if (event == null) {
            return Result.NEUTRAL;
        }
        if (event.getLoggerName() != null && event.getLoggerName().contains("Hikari")) {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return Result.NEUTRAL;
    }
}
