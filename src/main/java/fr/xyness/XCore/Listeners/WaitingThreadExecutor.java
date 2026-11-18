package fr.xyness.XCore.Listeners;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs the steps handed to it on the thread that waits for their result, for as long as it waits;
 * on a fallback pool once it has stopped.
 *
 * <h2>Why</h2>
 * Recognising a player at pre-login can mean asking Mojang for the UUID behind a name and for a
 * skin: requests that take up to their timeout when the API is slow. Run on XCore's work pool, a
 * burst of new players held every thread of it on those requests, and what the pool is for —
 * cache callbacks, Redis writes, sync messages — waited behind them. The login's own thread is the
 * one that is there to wait, one per connection: it runs them now. If the login stops waiting, what
 * is left of the work goes to the pool and finishes there, instead of being dropped.
 */
final class WaitingThreadExecutor implements Executor {

    /** Wakes the waiting thread when the result arrives from another thread. */
    private static final Runnable WAKE_UP = () -> { };

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Executor fallback;
    private volatile boolean waiting = true;

    /**
     * @param fallback Where the steps go once nobody waits for them any more.
     */
    WaitingThreadExecutor(Executor fallback) {
        this.fallback = fallback;
    }

    @Override
    public void execute(Runnable task) {
        if (!waiting) {
            fallback.execute(task);
            return;
        }
        queue.add(task);
        // Stopped waiting while this was added: whichever of the two removes it, runs it, once.
        if (!waiting && queue.remove(task)) fallback.execute(task);
    }

    /**
     * Runs the steps handed over until the result is in, or the deadline is reached.
     *
     * @param result        The future this thread waits for.
     * @param deadlineNanos When to stop, on the {@link System#nanoTime()} clock.
     * @return {@code true} when the result is in.
     * @throws InterruptedException When the thread is interrupted while it waits.
     */
    boolean runUntil(CompletableFuture<?> result, long deadlineNanos) throws InterruptedException {
        result.whenComplete((value, error) -> queue.add(WAKE_UP));
        try {
            while (!result.isDone()) {
                long left = deadlineNanos - System.nanoTime();
                if (left <= 0) break;
                Runnable task = queue.poll(left, TimeUnit.NANOSECONDS);
                if (task == null || task == WAKE_UP) continue;
                try {
                    task.run();
                } catch (RuntimeException ignored) {
                    // The steps are a future's: a failure ends up in the future, not here.
                }
            }
            return result.isDone();
        } finally {
            waiting = false;
            for (Runnable task = queue.poll(); task != null; task = queue.poll()) {
                if (task != WAKE_UP) fallback.execute(task);
            }
        }
    }
}
