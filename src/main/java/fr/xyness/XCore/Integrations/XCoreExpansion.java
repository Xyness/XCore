package fr.xyness.XCore.Integrations;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * PlaceholderAPI expansion for XCore itself.
 * <p>
 * Placeholders:
 * <ul>
 *   <li>{@code %xcore_name%} — Player name</li>
 *   <li>{@code %xcore_uuid%} — Player UUID</li>
 *   <li>{@code %xcore_last_login%} — Last login timestamp (requires online player)</li>
 *   <li>{@code %xcore_last_logout%} — Last logout timestamp (requires online player)</li>
 * </ul>
 * </p>
 */
public class XCoreExpansion extends PlaceholderExpansion {

    /** The plugin instance used to retrieve version information. */
    private final JavaPlugin plugin;

    /**
     * Creates a new XCore PAPI expansion.
     *
     * @param plugin The XCore plugin instance.
     */
    public XCoreExpansion(JavaPlugin plugin) {
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
     * @param params        The placeholder parameter (e.g. {@code "name"}, {@code "uuid"}).
     * @return The resolved placeholder value, or {@code null} if the parameter is unknown.
     */
    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (offlinePlayer == null) return null;

        return switch (params.toLowerCase()) {
            case "name" -> offlinePlayer.getName() != null ? offlinePlayer.getName() : "";
            case "uuid" -> offlinePlayer.getUniqueId().toString();
            case "last_login" -> {
                if (offlinePlayer instanceof Player player) {
                    yield String.valueOf(player.getLastLogin());
                }
                yield String.valueOf(offlinePlayer.getLastLogin());
            }
            case "last_logout" -> {
                if (offlinePlayer instanceof Player player) {
                    yield String.valueOf(player.getLastSeen());
                }
                yield String.valueOf(offlinePlayer.getLastSeen());
            }
            default -> null;
        };
    }
}
