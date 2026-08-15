package fr.xyness.XCore.Integrations;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Detects Bedrock players connected through Geyser/Floodgate.
 *
 * <p>The Floodgate API is reached by reflection so the plugin stays optional at runtime and absent
 * at compile time. When it is not installed, detection falls back to the shape of the UUID
 * Floodgate hands out.</p>
 *
 * <h2>Why the UUID test is what it is</h2>
 * Floodgate derives a Bedrock player's Java UUID as {@code new UUID(0, xuid)} — the whole high
 * half is zero and the low half is the Xbox XUID. Testing the high half is therefore exact.
 *
 * <p>This used to test for the literal prefix {@code 00000000-0000-0000-0009-}, which is not a
 * constant at all: those four digits are bits 48-63 of the XUID. Today's XUIDs land on
 * {@code 0008}, {@code 0009} or {@code 000a} depending on the account's age, so a real share of
 * Bedrock players went undetected — and a Java player can never collide, because both online (v4)
 * and offline (v3) UUIDs carry their version nibble in the high half.</p>
 */
public class FloodgateHook {

    private static volatile boolean available = false;

    /** {@code FloodgateApi.isFloodgatePlayer(UUID)}, resolved once. */
    private static volatile Method isFloodgatePlayer;

    /** The {@code FloodgateApi} singleton, resolved once. */
    private static volatile Object api;

    /**
     * Initializes the Floodgate hook. Should be called once during plugin startup.
     *
     * <p>The API handles are resolved here rather than on every call: this runs inside GUI code,
     * once per rendered head.</p>
     */
    public static void init() {
        available = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        if (!available) return;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            api = apiClass.getMethod("getInstance").invoke(null);
            isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable t) {
            // A Floodgate too old or too new to expose this: the UUID test still answers correctly.
            api = null;
            isFloodgatePlayer = null;
        }
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
     * Checks whether a player joined from Bedrock.
     *
     * @param player The player to check.
     * @return {@code true} if the player is a Bedrock player.
     */
    public static boolean isBedrockPlayer(Player player) {
        return player != null && isBedrockPlayer(player.getUniqueId());
    }

    /**
     * Checks whether a UUID belongs to a Bedrock player.
     *
     * <p>Answers from the Floodgate API when it is there, and from the UUID shape otherwise — so
     * an offline player, or a lookup made before Floodgate finished loading, is still classified
     * correctly.</p>
     *
     * @param uuid The UUID to check.
     * @return {@code true} if the UUID belongs to a Bedrock player.
     */
    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) return false;
        Method method = isFloodgatePlayer;
        Object instance = api;
        if (method != null && instance != null) {
            try {
                return (boolean) method.invoke(instance, uuid);
            } catch (Throwable ignored) {
                // Fall through to the UUID test.
            }
        }
        return isBedrockUuid(uuid);
    }

    /**
     * Whether a UUID has the shape Floodgate produces: a zero high half, and a low half that is
     * the XUID. The nil UUID is excluded — it means "nobody", not "a Bedrock player".
     */
    private static boolean isBedrockUuid(UUID uuid) {
        return uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() != 0L;
    }
}
