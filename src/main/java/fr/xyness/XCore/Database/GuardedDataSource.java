package fr.xyness.XCore.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.bukkit.Bukkit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import fr.xyness.XCore.Utils.Logger;

/**
 * The connection pool, with two things bolted on: a counter, and an alarm.
 *
 * <h2>The alarm</h2>
 * A database round trip on a tick thread stalls the server for as long as the database takes to
 * answer — a few milliseconds on a good day, seconds when the disk or the network hiccups. It is the
 * single most common way a plugin turns a healthy server into a stuttering one, and it is invisible:
 * nothing crashes, nothing logs, the TPS just drops.
 *
 * <p>Every addon borrows its connections from here, so this is the one place that can see it happen.
 * With {@code debug: true} (or {@code profiling: true}) the borrow is reported once per call site,
 * naming the class and line that did it. Off by default: the check is one boolean read.</p>
 *
 * <p>Nothing is refused — a warning that breaks a working feature is worse than the problem it
 * reports.</p>
 */
public class GuardedDataSource extends HikariDataSource {

    /** Whether borrows from a tick thread should be reported. */
    private static volatile boolean warnOnTickThread = false;

    /** Call sites already reported, so a hot path warns once and not once per event. */
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** Total connections handed out, for {@code /xcore diag}. */
    private static final LongAdder borrows = new LongAdder();

    /** Connections handed out on a tick thread — the number that should stay at zero. */
    private static final LongAdder tickThreadBorrows = new LongAdder();

    private final Logger logger;

    /**
     * Creates the pool.
     *
     * @param config The Hikari configuration.
     * @param logger Where warnings go.
     */
    public GuardedDataSource(HikariConfig config, Logger logger) {
        super(config);
        this.logger = logger;
    }

    /**
     * Turns the tick-thread alarm on or off.
     *
     * @param value {@code true} to report database access from a tick thread.
     */
    public static void setWarnOnTickThread(boolean value) {
        warnOnTickThread = value;
        if (value) reported.clear();
    }

    /** @return How many connections have been handed out since startup. */
    public static long getBorrowCount() { return borrows.sum(); }

    /** @return How many of them were taken from a tick thread. */
    public static long getTickThreadBorrowCount() { return tickThreadBorrows.sum(); }

    @Override
    public Connection getConnection() throws SQLException {
        borrows.increment();
        check();
        return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        borrows.increment();
        check();
        return super.getConnection(username, password);
    }

    /** Reports a borrow made from a tick thread, once per call site. */
    private void check() {
        boolean onTick;
        try {
            // On Folia this covers the global thread and every region thread — all of them are
            // places where blocking is a stall.
            onTick = Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return;
        }
        if (!onTick) return;
        tickThreadBorrows.increment();
        if (!warnOnTickThread) return;

        String origin = callSite();
        if (!reported.add(origin)) return;
        logger.sendWarning("Database access from a tick thread at <white>" + origin
                + "<yellow> — this blocks the server for the duration of the query."
                + " Move it onto the addon executor (api().getExecutor()).");
    }

    /** @return The first stack frame outside this class, as {@code Class.method:line}. */
    private static String callSite() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.")) continue;
            if (cls.equals(GuardedDataSource.class.getName())) continue;
            if (cls.startsWith("com.zaxxer.hikari")) continue;
            return frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                    + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        }
        return "unknown";
    }
}
