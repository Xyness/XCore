package fr.xyness.XCore.Integrations;

import org.bukkit.OfflinePlayer;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Utils.Formats;

/**
 * PlaceholderAPI expansion for XCore itself.
 * <p>
 * Placeholders:
 * <ul>
 *   <li>{@code %xcore_name%} — player name</li>
 *   <li>{@code %xcore_uuid%} — player UUID</li>
 *   <li>{@code %xcore_last_login%} / {@code %xcore_last_logout%} — as XCore recorded them</li>
 *   <li>{@code %xcore_playtime%} — total time connected, formatted</li>
 *   <li>{@code %xcore_playtime_seconds%} — the same, as a number</li>
 *   <li>{@code %xcore_server%} — this server's name</li>
 *   <li>{@code %xcore_network_online%} — players connected across the network</li>
 *   <li>{@code %xcore_servers%} — how many servers are up</li>
 *   <li>{@code %xcore_top_<board>_<rank>_name%} and {@code _value%} — any registered leaderboard</li>
 * </ul>
 */
public class XCoreExpansion extends PlaceholderExpansion {

    private final XCore plugin;

    /**
     * Creates a new XCore PAPI expansion.
     *
     * @param plugin The XCore plugin instance.
     */
    public XCoreExpansion(XCore plugin) {
        this.plugin = plugin;
    }

    /** @return The expansion identifier ({@code "xcore"}). */
    @Override
    public String getIdentifier() {
        return "xcore";
    }

    /** @return The expansion author. */
    @Override
    public String getAuthor() {
        return "Xyness";
    }

    /** @return The expansion version, matching the plugin version. */
    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** @return {@code true} to persist across reloads. */
    @Override
    public boolean persist() {
        return true;
    }

    /**
     * Resolves placeholder values for the given player and parameter.
     *
     * @param offlinePlayer The player requesting the placeholder.
     * @param params        The placeholder parameter.
     * @return The resolved value, or {@code null} if the parameter is unknown.
     */
    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (params == null) return null;
        String key = params.toLowerCase();

        // Leaderboards first: they are the only family with a variable shape.
        if (key.startsWith("top_") && plugin.leaderboards() != null) {
            return plugin.leaderboards().resolvePlaceholder(params.substring(4));
        }

        if (key.equals("server")) return plugin.getServerName();
        if (key.equals("network_online")) {
            return plugin.network() == null ? "0" : String.valueOf(plugin.network().totalOnline());
        }
        if (key.equals("servers")) {
            return plugin.network() == null ? "1" : String.valueOf(plugin.network().servers().size());
        }

        if (offlinePlayer == null) return null;

        return switch (key) {
            case "name" -> offlinePlayer.getName() != null ? offlinePlayer.getName() : "";
            case "uuid" -> offlinePlayer.getUniqueId().toString();
            case "last_login" -> value(offlinePlayer, "last_login");
            case "last_logout" -> value(offlinePlayer, "last_logout");
            case "playtime" -> Formats.duration(playtime(offlinePlayer));
            case "playtime_seconds" -> String.valueOf(playtime(offlinePlayer));
            default -> null;
        };
    }

    /** Reads a column of the cached player row, empty when it is not loaded. */
    private String value(OfflinePlayer player, String column) {
        return plugin.playerCache().getPlayerSync(player.getUniqueId())
                .map(data -> data.getTargetData(column))
                .map(String::valueOf)
                .orElse("");
    }

    private long playtime(OfflinePlayer player) {
        Long stored = plugin.playerCache().getPlayerSync(player.getUniqueId())
                .map(data -> data.getTargetData("playtime", Long.class))
                .orElse(0L);
        long total = stored == null ? 0L : stored;

        // Add the session in progress, otherwise the number only moves when a player disconnects.
        Object start = plugin.playerCache().getTempPlayerData(player.getUniqueId()).get(XCore.SESSION_START_KEY);
        if (start instanceof Long since) {
            total += (System.currentTimeMillis() - since) / 1000L;
        }
        return total;
    }
}
