package fr.xyness.XCore.Utils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

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

    /** Uses a one-hour ceiling for entries nobody reads back. */
    public Cooldowns() {
        this(Duration.ofHours(1));
    }

    /**
     * @param maxDuration The longest cooldown this instance will ever hold, so entries can be
     *                    dropped once they cannot possibly still be running.
     */
    public Cooldowns(Duration maxDuration) {
        this.entries = Caffeine.newBuilder()
                .expireAfterWrite(maxDuration.toMillis(), TimeUnit.MILLISECONDS)
                .maximumSize(50_000)
                .build();
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
