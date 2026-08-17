package fr.xyness.XCore.Utils;

import java.time.Duration;

import org.bukkit.entity.Player;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * Titles, action bars and boss bars. Wraps the plumbing around Adventure's API: ticks into
 * {@link Duration}, and hiding a bar from a player who has already left.
 */
public final class Notify {

    private Notify() {}

    /**
     * Shows a title with the vanilla timings (10 / 70 / 20 ticks).
     *
     * @param player   The player.
     * @param title    The big line.
     * @param subtitle The small line, may be {@code null}.
     */
    public static void title(Player player, Component title, Component subtitle) {
        title(player, title, subtitle, 10, 70, 20);
    }

    /**
     * Shows a title with explicit timings.
     *
     * @param player     The player.
     * @param title      The big line.
     * @param subtitle   The small line, may be {@code null}.
     * @param fadeIn     Fade-in, in ticks.
     * @param stay       Hold, in ticks.
     * @param fadeOut    Fade-out, in ticks.
     */
    public static void title(Player player, Component title, Component subtitle,
                             int fadeIn, int stay, int fadeOut) {
        if (player == null) return;
        player.showTitle(Title.title(
                title == null ? Component.empty() : title,
                subtitle == null ? Component.empty() : subtitle,
                Title.Times.times(ticks(fadeIn), ticks(stay), ticks(fadeOut))));
    }

    /**
     * Sends an action bar line.
     *
     * @param player  The player.
     * @param message The line.
     */
    public static void actionBar(Player player, Component message) {
        if (player == null || message == null) return;
        player.sendActionBar(message);
    }

    /**
     * Shows a boss bar to a player.
     *
     * @param player  The player.
     * @param text    The label.
     * @param color   The bar colour.
     * @param overlay The segment style.
     * @param progress Where the bar starts, between 0 and 1.
     * @return The bar, to update or hide later.
     */
    public static BossBar bossBar(Player player, Component text, BossBar.Color color,
                                  BossBar.Overlay overlay, float progress) {
        BossBar bar = BossBar.bossBar(text == null ? Component.empty() : text,
                clamp(progress), color, overlay);
        if (player != null) player.showBossBar(bar);
        return bar;
    }

    /**
     * Hides a boss bar, ignoring a player who has already left.
     *
     * @param player The player.
     * @param bar    The bar, may be {@code null}.
     */
    public static void hide(Player player, BossBar bar) {
        if (player == null || bar == null) return;
        try {
            player.hideBossBar(bar);
        } catch (Throwable ignored) {
            // Gone already; nothing to hide.
        }
    }

    /**
     * Updates a bar's label and progress in one call.
     *
     * @param bar      The bar.
     * @param text     The new label, or {@code null} to keep it.
     * @param progress The new progress, between 0 and 1.
     */
    public static void update(BossBar bar, Component text, float progress) {
        if (bar == null) return;
        if (text != null) bar.name(text);
        bar.progress(clamp(progress));
    }

    private static float clamp(float progress) {
        if (progress < 0f) return 0f;
        if (progress > 1f) return 1f;
        return progress;
    }

    private static Duration ticks(int count) {
        return Duration.ofMillis(Math.max(0, count) * 50L);
    }
}
