package fr.xyness.XCore.Models;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerTextures;

import com.destroystokyo.paper.profile.PlayerProfile;

/**
 * Immutable-core model representing a player's persistent data.
 * <p>
 * Core fields (uuid, name, texture, mojang_uuid) are set at construction and cannot change.
 * Additional dynamic data from extra database columns is stored in a thread-safe map
 * accessible via {@link #getData()}, {@link #getTargetData(String)} and {@link #setTargetData(String, Object)}.
 * </p>
 * <p>
 * The player head {@link ItemStack} is lazily built on first access and cached for reuse
 * using double-checked locking for thread safety.
 * </p>
 */
public class PlayerData {

	/** The server-side UUID (may differ from Mojang UUID in offline-mode servers). */
    private final UUID uuid;

	/** The player's current in-game name. */
    private final String name;

	/** The skin texture hash used to build the player head item. */
    private final String texture;

	/** The Mojang (online) UUID with dashes, or {@code "none"} if unavailable. */
    private final String mojang_uuid;

	/** Thread-safe map for dynamic extra data loaded from additional database columns. */
    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();

	/** Lazily-built player head item, cached after first access (textured or plain). */
    private volatile ItemStack head;

	/**
	 * Creates a new PlayerData instance.
	 *
	 * @param uuid       The server-side UUID.
	 * @param name       The player name.
	 * @param texture    The skin texture hash.
	 * @param mojang_uuid The Mojang UUID string.
	 */
    public PlayerData(UUID uuid, String name, String texture, String mojang_uuid) {
        this.uuid = uuid;
        this.name = name;
        this.texture = texture;
        this.mojang_uuid = mojang_uuid;
    }

    @Override
    public String toString() {
        return "PlayerData{" + "uuid=" + uuid + ", name=" + name + ", mojangUuid=" + mojang_uuid + '}';
    }

	/** @return The server-side UUID. */
    public UUID getUuid() { return uuid; }

	/** @return The player's current name. */
    public String getName() { return name; }

	/** @return The skin texture hash. */
    public String getTexture() { return texture; }

	/** @return The Mojang UUID string, or {@code "none"} if unavailable. */
    public String getMojangUUID() { return mojang_uuid; }

	/**
	 * Returns a {@link ItemStack} representing the player's head with the correct skin texture.
	 * The head is lazily built on first call and cached for subsequent access (thread-safe).
	 * If the texture is invalid, a plain head is cached and returned.
	 *
	 * @return The player head {@link ItemStack}.
	 */
    public ItemStack getHead() {
        if (head == null) {
            synchronized (this) {
                if (head == null) {
                    head = buildHead();
                }
            }
        }
        return head;
    }

	/**
	 * Builds the player head {@link ItemStack} with the skin texture applied via Paper's profile API.
	 * Returns a plain head on failure (never null).
	 *
	 * @return A new {@link ItemStack} of type {@code PLAYER_HEAD}, with or without the texture applied.
	 */
    private ItemStack buildHead() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        if (texture == null || texture.isBlank()) return item;
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) return item;
        PlayerProfile profile = Bukkit.createProfile(uuid);
        try {
            URI uri = URI.create("http://textures.minecraft.net/texture/" + texture);
            URL url = uri.toURL();
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(url);
            profile.setTextures(textures);
            meta.setPlayerProfile(profile);
            item.setItemMeta(meta);
        } catch (MalformedURLException e) {
            java.util.logging.Logger.getLogger("XCore").warning("Failed to parse skin URL: " + e.getMessage());
        }
        return item;
    }

	/**
	 * Returns an unmodifiable view of the dynamic extra data map.
	 *
	 * @return An unmodifiable map of extra column key-value pairs.
	 */
    public Map<String, Object> getData() {
    	synchronized (data) {
    	    return Map.copyOf(data);
    	}
    }

	/**
	 * Retrieves a specific dynamic data value by key.
	 *
	 * @param key The column/data key.
	 * @return The value, or {@code null} if not present.
	 */
    public Object getTargetData(String key) {
    	synchronized (data) {
    	    return data.get(key);
    	}
    }

	/**
	 * Retrieves a specific dynamic data value by key with type-safe casting.
	 *
	 * @param <T>  The expected return type.
	 * @param key  The column/data key.
	 * @param type The expected class type.
	 * @return The value cast to the requested type, or {@code null} if not present or wrong type.
	 */
    @SuppressWarnings("unchecked")
    public <T> T getTargetData(String key, Class<T> type) {
    	Object value = getTargetData(key);
    	if (value == null) return null;
    	if (type.isInstance(value)) return (T) value;
    	// Handle numeric conversions (DB drivers may return different Number types)
    	if (value instanceof Number n && Number.class.isAssignableFrom(type)) {
    	    if (type == Integer.class) return (T) Integer.valueOf(n.intValue());
    	    if (type == Long.class) return (T) Long.valueOf(n.longValue());
    	    if (type == Double.class) return (T) Double.valueOf(n.doubleValue());
    	    if (type == Float.class) return (T) Float.valueOf(n.floatValue());
    	}
    	return null;
    }

	/**
	 * Sets a dynamic data value for the given key.
	 *
	 * @param key   The column/data key.
	 * @param value The value to store.
	 * @return {@code true} if a previous value was replaced, {@code false} if this is a new entry.
	 */
    public boolean setTargetData(String key, Object value) {
    	synchronized (data) {
    	    return data.put(key, value) != null;
    	}
    }

	/**
	 * Atomically replaces all dynamic data with the contents of the provided map.
	 *
	 * @param newData The new data map to apply.
	 * @return {@code true} if the new data is non-empty, {@code false} if it was empty.
	 */
    public boolean setData(Map<String, Object> newData) {
    	synchronized (data) {
    	    data.clear();
    	    data.putAll(newData);
    	    return !data.isEmpty();
    	}
    }
}
