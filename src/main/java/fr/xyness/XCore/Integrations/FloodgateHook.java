package fr.xyness.XCore.Integrations;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Detects Bedrock players connected via Geyser/Floodgate.
 * <p>
 * Uses the Floodgate API via reflection when available to avoid a compile-time
 * dependency. Falls back to checking the well-known Geyser UUID prefix
 * ({@code 00000000-0000-0000-0009-}) when Floodgate is not installed.
 * </p>
 */
public class FloodgateHook {

    private static volatile boolean available = false;

    /**
     * Initializes the Floodgate hook by checking if the Floodgate plugin is loaded.
     * Should be called once during plugin startup.
     */
    public static void init() {
        available = Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    /**
     * Returns whether the Floodgate plugin is available on the server.
     *
     * @return {@code true} if Floodgate is installed and loaded.
     */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * Checks if a player is a Bedrock player (connected via Geyser/Floodgate).
     * <p>
     * Uses the Floodgate API if available, otherwise falls back to checking
     * the UUID prefix used by Geyser for Bedrock players.
     * </p>
     *
     * @param player The player to check.
     * @return {@code true} if the player is a Bedrock player.
     */
    public static boolean isBedrockPlayer(Player player) {
        if (!available) {
            return isBedrockUuid(player.getUniqueId());
        }
        try {
            Class<?> floodgateApi = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = floodgateApi.getMethod("getInstance").invoke(null);
            return (boolean) floodgateApi.getMethod("isFloodgatePlayer", UUID.class)
                .invoke(instance, player.getUniqueId());
        } catch (Throwable e) {
            return isBedrockUuid(player.getUniqueId());
        }
    }

    /**
     * Checks if a UUID belongs to a Bedrock player based on the Geyser UUID prefix.
     * <p>
     * This method does not use the Floodgate API and relies solely on the
     * well-known prefix {@code 00000000-0000-0000-0009-} that Geyser assigns
     * to Bedrock player UUIDs.
     * </p>
     *
     * @param uuid The UUID to check.
     * @return {@code true} if the UUID matches the Bedrock prefix pattern.
     */
    public static boolean isBedrockPlayer(UUID uuid) {
        return isBedrockUuid(uuid);
    }

    /**
     * Internal helper to check the Geyser Bedrock UUID prefix.
     */
    private static boolean isBedrockUuid(UUID uuid) {
        return uuid.toString().startsWith("00000000-0000-0000-0009-");
    }
}
