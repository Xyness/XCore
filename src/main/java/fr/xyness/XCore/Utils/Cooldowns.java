package fr.xyness.XCore.Utils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

/**
 * Per-player cooldowns. Entries expire on their own, so nothing has to be cleaned up when a player
 * leaves.
 *
 * <pre>{@code
 * private final Cooldowns cooldowns = new Cooldowns();
 *
 * long left = cooldowns.remaining("report", player.getUniqueId());
 * if (left > 0) {
 *     player.sendMessage(lang.getComponent("wait", "seconds", String.valueOf(left)));
 *     return;
 * }
 * cooldowns.set("report", player.getUniqueId(), 30);
 * }</pre>
 */
public class Cooldowns {

    /** Expiry timestamps, keyed by {@code name:uuid}. */
    private final Cache<String, Long> entries;

    /** Entries go away when they run out, whenever that is. */
    public Cooldowns() {
        this.entries = Caffeine.newBuilder()
                // Each entry expires at its own deadline. A fixed window would have been simpler,
                // but it silently caps every cooldown at that window: a one-day ban appeal delay
                // held in a one-hour cache is a one-hour delay.
                .expireAfter(new Expiry<String, Long>() {
                    @Override
                    public long expireAfterCreate(String key, Long until, long currentTime) {
                        return TimeUnit.MILLISECONDS.toNanos(
                                Math.max(0, until - System.currentTimeMillis()));
                    }

                    @Override
                    public long expireAfterUpdate(String key, Long until, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, until, currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, Long until, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(50_000)
                .build();
    }

    /**
     * Kept for callers that used to size the cache themselves. The ceiling is no longer needed —
     * every entry now expires on its own deadline — and the argument is ignored.
     *
     * @param maxDuration Ignored.
     * @deprecated Use {@link #Cooldowns()}.
     */
    @Deprecated
    public Cooldowns(Duration maxDuration) {
        this();
    }

    private static String key(String name, UUID player) {
        return name + ":" + player;
    }

    /**
     * Starts a cooldown.
     *
     * @param name    What the cooldown is for.
     * @param player  The player.
     * @param seconds How long it lasts.
     */
    public void set(String name, UUID player, long seconds) {
        entries.put(key(name, player), System.currentTimeMillis() + seconds * 1000L);
    }

    /**
     * Starts a cooldown given in milliseconds.
     *
     * @param name   What the cooldown is for.
     * @param player The player.
     * @param millis How long it lasts.
     */
    public void setMillis(String name, UUID player, long millis) {
        entries.put(key(name, player), System.currentTimeMillis() + millis);
    }

    /**
     * Time left, rounded up to the next second.
     *
     * @param name   What the cooldown is for.
     * @param player The player.
     * @return The seconds remaining, 0 when nothing is running.
     */
    public long remaining(String name, UUID player) {
        Long until = entries.getIfPresent(key(name, player));
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /**
     * Time left in milliseconds.
     *
     * @param name   What the cooldown is for.
     * @param player The player.
     * @return The milliseconds remaining, 0 when nothing is running.
     */
    public long remainingMillis(String name, UUID player) {
        Long until = entries.getIfPresent(key(name, player));
        if (until == null) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    /**
     * @param name   What the cooldown is for.
     * @param player The player.
     * @return Whether the player still has to wait.
     */
    public boolean isActive(String name, UUID player) {
        return remainingMillis(name, player) > 0;
    }

    /**
     * Checks and starts in one call: the usual shape at the top of a command.
     *
     * @param name    What the cooldown is for.
     * @param player  The player.
     * @param seconds How long the new cooldown lasts.
     * @return 0 when the action may go ahead (and the cooldown has just started), otherwise the
     *         seconds left to wait.
     */
    public long tryUse(String name, UUID player, long seconds) {
        long left = remaining(name, player);
        if (left > 0) return left;
        set(name, player, seconds);
        return 0;
    }

    /**
     * Ends a cooldown early.
     *
     * @param name   What the cooldown is for.
     * @param player The player.
     */
    public void clear(String name, UUID player) {
        entries.invalidate(key(name, player));
    }

    /** Ends every cooldown held here. */
    public void clearAll() {
        entries.invalidateAll();
    }
}
