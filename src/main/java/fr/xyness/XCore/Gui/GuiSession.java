package fr.xyness.XCore.Gui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Represents the per-player state for an open GUI session.
 * <p>
 * Tracks the player, the inventory, the holder type, the current page,
 * a {@link BlinkController} for animated slots, and arbitrary metadata
 * that click handlers can use to store contextual information.
 * </p>
 */
public class GuiSession {

    /** The player who owns this session. */
    private final Player player;

    /** The Bukkit inventory displayed to the player. */
    private final Inventory inventory;

    /** The inventory holder used to identify the GUI type. */
    private final InventoryHolder holder;

    /** The blink controller for animated slots, or {@code null} if none. */
    private BlinkController blinkController;

    /** The current page index for paginated GUIs. */
    private int page;

    /** Arbitrary metadata stored by click handlers. */
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();

    /**
     * Creates a new GUI session.
     *
     * @param player    The player who owns this session.
     * @param inventory The Bukkit inventory.
     * @param holder    The inventory holder.
     */
    public GuiSession(Player player, Inventory inventory, InventoryHolder holder) {
        this.player = player;
        this.inventory = inventory;
        this.holder = holder;
    }

    /**
     * Returns the player who owns this session.
     *
     * @return The player.
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Returns the Bukkit inventory for this session.
     *
     * @return The inventory.
     */
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Returns the inventory holder identifying the GUI type.
     *
     * @return The inventory holder.
     */
    public InventoryHolder getHolder() {
        return holder;
    }

    /**
     * Returns the blink controller, or {@code null} if none is active.
     *
     * @return The blink controller.
     */
    public BlinkController getBlinkController() {
        return blinkController;
    }

    /**
     * Sets the blink controller for this session.
     *
     * @param blinkController The blink controller to set.
     */
    public void setBlinkController(BlinkController blinkController) {
        this.blinkController = blinkController;
    }

    /**
     * Returns the current page index.
     *
     * @return The page number.
     */
    public int getPage() {
        return page;
    }

    /**
     * Sets the current page index.
     *
     * @param page The page number.
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * Stores a metadata value under the given key.
     *
     * @param key   The metadata key.
     * @param value The value to store.
     */
    public void put(String key, Object value) {
        metadata.put(key, value);
    }

    /**
     * Retrieves a metadata value by key.
     *
     * @param key The metadata key.
     * @return The stored value, or {@code null} if not present.
     */
    public Object get(String key) {
        return metadata.get(key);
    }

    /**
     * Retrieves a metadata value by key, cast to the expected type.
     *
     * @param key  The metadata key.
     * @param type The expected class type.
     * @param <T>  The value type.
     * @return The stored value cast to {@code T}, or {@code null} if not present or wrong type.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = metadata.get(key);
        if (type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }
}
