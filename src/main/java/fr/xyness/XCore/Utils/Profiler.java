package fr.xyness.XCore.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measures where the server's time goes, addon by addon.
 *
 * <h2>What it answers</h2>
 * "Which addon is costing me tick time?" — a question no timings report can answer here, because
 * every addon runs its listeners under XCore's plugin identity: to Spark and to the built-in
 * timings, the whole ecosystem is one plugin called XCore.
 *
 * <p>Every event handler registered through {@link fr.xyness.XCore.Addon.AddonListenerRegistry} is
 * timed under a key of the form {@code XBans/ChatListener#AsyncChatEvent}, and every database query
 * issued through the guarded data source is counted. {@code /xcore profile} prints the worst
 * offenders.</p>
 *
 * <h2>Cost when off</h2>
 * One volatile read per event. Sampling is only switched on by {@code profiling: true} in
 * {@code config.yml} or by {@code /xcore profile on}, so an ordinary server pays nothing.
 */
public final class Profiler {

    /** Whether samples are being recorded. Read on every event, hence volatile and nothing more. */
    private static volatile boolean enabled = false;

    /** One accumulator per measured key. */
    private final Map<String, Sample> samples = new ConcurrentHashMap<>();

    /** When sampling last started, for the "per second" figures. */
    private volatile long startedAt = System.currentTimeMillis();

    /** Accumulated timings for one key. */
    private static final class Sample {
        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();
    }

    /**
     * One line of the report.
     *
     * @param key         The measured key, e.g. {@code XBans/ChatListener#AsyncChatEvent}.
     * @param calls       How many times it ran.
     * @param totalMillis Total time spent inside it.
     * @param avgMicros   Average time per call, in microseconds.
     * @param maxMicros   Worst single call, in microseconds.
     */
    public record Entry(String key, long calls, double totalMillis, double avgMicros, double maxMicros) {}

    /** @return Whether sampling is currently on. */
    public static boolean isEnabled() { return enabled; }

    /**
     * Turns sampling on or off.
     *
     * @param value {@code true} to record samples.
     */
    public void setEnabled(boolean value) {
        enabled = value;
        if (value) reset();
    }

    /**
     * Records one measurement. No-op when sampling is off.
     *
     * @param key   The measured key.
     * @param nanos How long it took.
     */
    public void record(String key, long nanos) {
        if (!enabled) return;
        Sample sample = samples.computeIfAbsent(key, k -> new Sample());
        sample.calls.increment();
        sample.totalNanos.add(nanos);
        sample.maxNanos.accumulateAndGet(nanos, Math::max);
    }

    /** Drops every sample and restarts the measurement window. */
    public void reset() {
        samples.clear();
        startedAt = System.currentTimeMillis();
    }

    /** @return How long the current measurement window has been running, in seconds. */
    public long windowSeconds() {
        return Math.max(1L, (System.currentTimeMillis() - startedAt) / 1000L);
    }

    /**
     * Returns the heaviest keys, worst total time first.
     *
     * @param limit How many entries to return.
     * @return The report lines.
     */
    public List<Entry> top(int limit) {
        List<Entry> entries = new ArrayList<>(samples.size());
        samples.forEach((key, sample) -> {
            long calls = sample.calls.sum();
            if (calls == 0) return;
            long total = sample.totalNanos.sum();
            entries.add(new Entry(key, calls,
                    total / 1_000_000.0,
                    (total / (double) calls) / 1_000.0,
                    sample.maxNanos.get() / 1_000.0));
        });
        entries.sort(Comparator.comparingDouble(Entry::totalMillis).reversed());
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    /** @return How many distinct keys have been measured. */
    public int size() { return samples.size(); }
}
