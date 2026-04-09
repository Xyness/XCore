package fr.xyness.XCore.Models;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary per-session data associated with an online player.
 * <p>
 * This data is held in a Caffeine cache with a 5-minute access-based TTL
 * and is automatically removed when the player disconnects.
 * The underlying map is thread-safe ({@link ConcurrentHashMap}).
 * </p>
 */
public class PlayerTempData {

	/** The player's server-side UUID. */
    private final UUID uuid;

	/** Thread-safe map holding arbitrary temporary key-value data. */
    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();

	/**
	 * Creates a new temporary data container for the given player.
	 *
	 * @param uuid The player's server-side UUID.
	 */
    public PlayerTempData(UUID uuid) {
        this.uuid = uuid;
    }

    @Override
    public String toString() {
        return "PlayerTempData{" + "uuid=" + uuid + '}';
    }

	/** @return The player's server-side UUID. */
    public UUID getUuid() {
    	return uuid;
    }

	/**
	 * Returns the full underlying data map.
	 *
	 * @return The {@link ConcurrentHashMap} of temporary data.
	 */
    public ConcurrentHashMap<String, Object> getData() {
        return data;
    }

	/**
	 * Stores a temporary data entry.
	 *
	 * @param key   The data key.
	 * @param value The data value.
	 */
    public void put(String key, Object value) {
        data.put(key, value);
    }

	/**
	 * Retrieves a temporary data entry.
	 *
	 * @param key The data key.
	 * @return The value, or {@code null} if not present.
	 */
    public Object get(String key) {
        return data.get(key);
    }

	/**
	 * Removes a temporary data entry.
	 *
	 * @param key The data key to remove.
	 */
    public void remove(String key) {
        data.remove(key);
    }
}
