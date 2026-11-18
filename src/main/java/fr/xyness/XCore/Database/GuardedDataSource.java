package fr.xyness.XCore.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import fr.xyness.XCore.Addon.AddonClassLoader;
import fr.xyness.XCore.Utils.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The connection pool, with two things bolted on: a counter, and an alarm.
 *
 * <h2>The alarm</h2>
 * A database round trip on a tick thread stalls the server for as long as the database takes to
 * answer — a few milliseconds on a good day, seconds when the disk or the network hiccups. It is the
 * single most common way a plugin turns a healthy server into a stuttering one, and it is invisible:
 * nothing crashes, nothing logs, the TPS just drops.
 *
 * <p>Every addon and every plugin built on XCore borrows its connections from here, so this is the
 * one place that can see it happen. On Folia a region thread is a tick thread like the global one:
 * blocking there stalls every player of the region.</p>
 *
 * <p>Always on. It used to wait for {@code debug: true}, so it only ever spoke on the servers of
 * people already looking for a problem, and the servers that stuttered were the others. The check is
 * one thread test per borrow. What it says is one line per call site — the first frame outside
 * XCore's database code, so the plugin and the line are named — then, for as long as that site keeps
 * doing it, at most one line every five minutes with how many times it happened since. Never a
 * stack trace.</p>
 *
 * <p>Only while the server runs. Plugins create their tables on the main thread while the server
 * starts and save on it while it stops; neither keeps a player waiting, and reporting them would
 * fill every console at every boot with lines nobody can act on. {@code debug: true} (or
 * {@code profiling: true}) reports those as well, as it always did.</p>
 *
 * <p>Nothing is refused — a warning that breaks a working feature is worse than the problem it
 * reports.</p>
 */
public class GuardedDataSource extends HikariDataSource {

    /** Whether borrows made while the server starts or stops are reported too (debug, profiling). */
    private static volatile boolean warnOnTickThread = false;

    /**
     * Whether the server is running: set a moment after the first tick, cleared when XCore stops.
     * Before and after, the main thread is busy starting or stopping, and nobody is waiting on it.
     */
    private static volatile boolean serverRunning = false;

    /** How long a call site that already has its line waits before it gets another. */
    private static final long REPEAT_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** Above this many call sites, the rest are counted together: a bound, not a limit anyone reaches. */
    private static final int MAX_SITES = 512;

    /** Key of the bucket the call sites above {@link #MAX_SITES} share. */
    private static final String OVERFLOW = "(other call sites)";

    /** {@link Site#lastLine} of a site that has not had its line yet. */
    private static final long NEVER = Long.MIN_VALUE;

    /** Frames are walked lazily and only as far as needed: the cost of a report is a few frames, not a stack trace. */
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** How deep a walk goes before giving up: the frames that matter are near the top. */
    private static final int MAX_DEPTH = 96;

    private static final String XCORE_PACKAGE = "fr.xyness.XCore.";

    /** Total connections handed out, for {@code /xcore diag}. */
    private static final LongAdder borrows = new LongAdder();

    /** Connections handed out on a tick thread while the server ran — the number that should stay at zero. */
    private static final LongAdder tickThreadBorrows = new LongAdder();

    /** The call sites seen taking a connection on a tick thread, with what has been said about them. */
    private final Map<String, Site> sites = new ConcurrentHashMap<>();

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
     * Also reports the borrows made from a tick thread while the server starts or stops, which are
     * left out otherwise. What {@code debug} and {@code profiling} turn on.
     *
     * @param value {@code true} to report those as well.
     */
    public static void setWarnOnTickThread(boolean value) {
        warnOnTickThread = value;
    }

    /**
     * Says whether the server is running: from a moment after its first tick until XCore stops.
     *
     * @param value {@code true} once the server ticks, {@code false} when it begins to stop.
     */
    public static void setServerRunning(boolean value) {
        serverRunning = value;
    }

    /** @return How many connections have been handed out since startup. */
    public static long getBorrowCount() { return borrows.sum(); }

    /**
     * @return How many of them were taken from a tick thread while the server was running — the ones
     *         that made it wait. Those taken while it started or stopped are not counted.
     */
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

    /** Notes a borrow made from a tick thread, and reports it when its call site is due a line. */
    private void check() {
        boolean onTick;
        try {
            // The main thread, and on Folia the global thread and every region thread: all of them
            // are places where blocking is a stall. A type test on the current thread, nothing more.
            onTick = Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return;
        }
        if (!onTick) return;

        boolean running = serverRunning && !isStopping();
        if (running) {
            tickThreadBorrows.increment();
        } else if (!warnOnTickThread) {
            return;
        }

        try {
            record(running);
        } catch (Throwable ignored) {
            // The report is a courtesy; the connection is what the caller asked for.
        }
    }

    /** @return Whether the server is shutting down: the plugins save on the main thread then. */
    private static boolean isStopping() {
        try {
            return Bukkit.isStopping();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Counts a borrow against its call site, and prints its line when it is due one.
     *
     * @param running Whether the server was running, rather than starting or stopping.
     */
    private void record(boolean running) {
        Origin origin = WALKER.walk(GuardedDataSource::locate);
        String key = origin.key();
        Site site = sites.get(key);
        if (site == null) {
            if (sites.size() >= MAX_SITES) key = OVERFLOW;
            Origin described = OVERFLOW.equals(key) ? Origin.UNKNOWN : origin;
            site = sites.computeIfAbsent(key, k -> new Site(describe(described)));
        }
        site.total.increment();
        site.pending.incrementAndGet();

        long now = System.nanoTime();
        long last = site.lastLine.get();
        if (last != NEVER && now - last < REPEAT_NANOS) return;
        if (!site.lastLine.compareAndSet(last, now)) return;   // another thread is saying it
        print(site, site.pending.getAndSet(0), last, now, running);
    }

    /**
     * Prints the lines the call sites are due and have not had: a site that went on taking
     * connections after its line is told about at most every five minutes, with the count, even when
     * it has stopped since. Called on a timer, off the main thread.
     */
    public void reportPending() {
        long now = System.nanoTime();
        for (Site site : sites.values()) {
            if (site.pending.get() == 0) continue;
            long last = site.lastLine.get();
            if (last != NEVER && now - last < REPEAT_NANOS) continue;
            if (!site.lastLine.compareAndSet(last, now)) continue;
            long count = site.pending.getAndSet(0);
            if (count > 0) print(site, count, last, now, true);
        }
    }

    /**
     * One line about one call site.
     *
     * @param site     The call site.
     * @param count    How many borrows since its previous line.
     * @param previous When its previous line was printed, {@link #NEVER} for its first.
     * @param now      Now, on the same clock.
     * @param running  Whether the server was running, rather than starting or stopping.
     */
    private void print(Site site, long count, long previous, long now, boolean running) {
        String where = MiniMessage.miniMessage().escapeTags(site.description);
        if (previous == NEVER) {
            logger.sendWarning("Database access on a tick thread: <white>" + where + "<yellow>."
                    + " The server waits for the database every time this runs; move it onto the database pool"
                    + " (api().getDbExecutor())."
                    + (running ? "" : " Seen while the server started or stopped, reported because debug or profiling is on."));
            return;
        }
        long minutes = Math.max(1, TimeUnit.NANOSECONDS.toMinutes(now - previous));
        logger.sendWarning("Database access on a tick thread, still: <white>" + where + "<yellow> — "
                + count + " more time(s) in the last " + minutes + " minutes, " + site.total.sum() + " in all.");
    }

    // -------------------------------------------------------------------------
    // Where a borrow comes from
    // -------------------------------------------------------------------------

    /**
     * Finds who took the connection: the first frame outside XCore's database code, and when that
     * frame is XCore's own — the economy behind a Vault call, say — the first frame of whoever called
     * into XCore, so the line names both.
     */
    private static Origin locate(Stream<StackWalker.StackFrame> frames) {
        StackWalker.StackFrame culprit = null;
        Iterator<StackWalker.StackFrame> it = frames.limit(MAX_DEPTH).iterator();
        while (it.hasNext()) {
            StackWalker.StackFrame frame = it.next();
            String type = frame.getClassName();
            if (culprit == null) {
                if (isDatabasePlumbing(type)) continue;
                culprit = frame;
                if (!type.startsWith(XCORE_PACKAGE)) return new Origin(culprit, null);
                continue;
            }
            if (type.startsWith(XCORE_PACKAGE) || isServerOrRuntime(type)) continue;
            return new Origin(culprit, frame);
        }
        return culprit == null ? Origin.UNKNOWN : new Origin(culprit, null);
    }

    /**
     * The frames a borrow goes through on its way to the pool, none of which is the reason for it:
     * the JDK, Hikari, and XCore's database helpers — the query builder, the table and schema
     * helpers, the DAOs, the column builder and the column helpers of {@code Methods}.
     */
    private static boolean isDatabasePlumbing(String type) {
        return type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jdk.")
                || type.startsWith("sun.") || type.startsWith("com.sun.")
                || type.startsWith("com.zaxxer.hikari.")
                || type.startsWith("fr.xyness.XCore.Database.") || type.startsWith("fr.xyness.XCore.DAO.")
                || type.equals("fr.xyness.XCore.API.ColumnBuilder") || type.equals("fr.xyness.XCore.Utils.Methods");
    }

    /**
     * Frames that call into XCore on somebody else's behalf: the server and the runtime, and the
     * two plugins every other one reaches XCore's economy through — a balance placeholder rendered
     * for an offline player names the plugin that asked PlaceholderAPI, not PlaceholderAPI.
     */
    private static boolean isServerOrRuntime(String type) {
        return type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jdk.")
                || type.startsWith("sun.") || type.startsWith("com.sun.")
                || type.startsWith("org.bukkit.") || type.startsWith("io.papermc.") || type.startsWith("net.minecraft.")
                || type.startsWith("com.destroystokyo.") || type.startsWith("org.spigotmc.")
                || type.startsWith("ca.spottedleaf.") || type.startsWith("co.aikar.") || type.startsWith("com.mojang.")
                || type.startsWith("io.netty.") || type.startsWith("net.kyori.") || type.startsWith("com.google.")
                || type.startsWith("me.clip.placeholderapi.") || type.startsWith("net.milkbowl.vault.");
    }

    /** "XBans · SanctionCache.load:412", or "unknown" for a frame we could not see. */
    private static String frame(StackWalker.StackFrame frame) {
        if (frame == null) return "unknown";
        String type = frame.getClassName();
        String owner = ownerOf(frame);
        String where = type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        return owner == null ? where : owner + " · " + where;
    }

    /** The plugin or addon a frame's class belongs to, or {@code null} when it belongs to neither. */
    private static String ownerOf(StackWalker.StackFrame frame) {
        try {
            Class<?> type = frame.getDeclaringClass();
            if (type.getClassLoader() instanceof AddonClassLoader addon && addon.getDescriptor() != null) {
                return addon.getDescriptor().getName();
            }
            return JavaPlugin.getProvidingPlugin(type).getName();
        } catch (Throwable notAPlugin) {
            return null;
        }
    }

    private static String describe(Origin origin) {
        if (origin == Origin.UNKNOWN) return OVERFLOW;
        String text = frame(origin.culprit());
        return origin.caller() == null ? text : text + ", called from " + frame(origin.caller());
    }

    /**
     * Where a borrow came from.
     *
     * @param culprit The first frame outside XCore's database code.
     * @param caller  When that frame is XCore's own, the first frame of whoever called into XCore.
     */
    private record Origin(StackWalker.StackFrame culprit, StackWalker.StackFrame caller) {

        static final Origin UNKNOWN = new Origin(null, null);

        String key() {
            if (culprit == null) return OVERFLOW;
            String key = culprit.getClassName() + "." + culprit.getMethodName() + ":" + culprit.getLineNumber();
            if (caller == null) return key;
            return key + "<" + caller.getClassName() + "." + caller.getMethodName() + ":" + caller.getLineNumber();
        }
    }

    /** One call site, with its count and the moment of its last line. */
    private static final class Site {
        final String description;
        final LongAdder total = new LongAdder();
        final AtomicLong pending = new AtomicLong();
        final AtomicLong lastLine = new AtomicLong(NEVER);

        Site(String description) {
            this.description = description;
        }
    }
}
