package fr.xyness.XCore.Utils;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.bukkit.Bukkit;

/**
 * What to do with a task when the pool queue is full.
 *
 * <p>{@code CallerRunsPolicy} is the usual answer and it is right most of the time: the thread that
 * submitted the work does it instead, which slows the producer down until the pool catches up. It
 * is wrong for exactly one caller, the server thread, because "slow the producer down" there means
 * running a database query mid-tick.</p>
 *
 * <p>So: caller runs, unless the caller is a tick thread, in which case a single overflow thread
 * takes it. Rejections are counted and reported once, since a pool that saturates is a symptom
 * worth seeing rather than a thing to absorb silently.</p>
 */
public class RejectedTaskPolicy implements RejectedExecutionHandler {

    private static final LongAdder rejections = new LongAdder();
    private static final LongAdder tickRescues = new LongAdder();

    private final Logger logger;
    private final String poolName;
    private final AtomicBoolean warned = new AtomicBoolean();

    /** Handles the tasks a tick thread must not run itself. One thread is enough: this is the tail. */
    private final java.util.concurrent.ExecutorService overflow;

    /**
     * @param poolName The pool this policy belongs to, for the warning.
     * @param logger   Where the warning goes.
     */
    public RejectedTaskPolicy(String poolName, Logger logger) {
        this.poolName = poolName;
        this.logger = logger;
        this.overflow = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, poolName + "-Overflow");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
        if (pool.isShutdown()) return;
        rejections.increment();

        boolean onTick;
        try {
            onTick = Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            onTick = false;
        }

        if (warned.compareAndSet(false, true) && logger != null) {
            logger.sendWarning("The " + poolName + " pool is saturated (" + pool.getQueue().size()
                    + " tasks queued). Work is being delayed; if this keeps happening, something is"
                    + " submitting far more than the database can absorb.");
        }

        if (onTick) {
            tickRescues.increment();
            overflow.execute(task);
        } else {
            task.run();
        }
    }

    /** Stops the overflow thread. */
    public void shutdown() {
        overflow.shutdown();
    }

    /** @return How many tasks have been rejected since startup, across every pool. */
    public static long getRejectionCount() {
        return rejections.sum();
    }

    /** @return How many of them were moved off a tick thread instead of running there. */
    public static long getTickRescueCount() {
        return tickRescues.sum();
    }
}
