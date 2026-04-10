package fr.xyness.XCore.Gui;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Immutable data class representing a complete GUI definition.
 * <p>
 * A definition is typically loaded from a YAML configuration file and holds
 * all the information needed to construct and render a GUI: title, size,
 * item layout, pagination slots, and default sound.
 * </p>
 */
public class GuiDefinition {

    /** The GUI name, derived from the configuration filename. */
    private final String name;

    /** The language key used to resolve the inventory title. */
    private final String titleKey;

    /** The number of rows in the inventory (1-6). */
    private final int rows;

    /** The configured items keyed by their inventory slot. */
    private final Map<Integer, GuiItem> items;

    /** The ordered list of slots reserved for paginated content. */
    private final List<Integer> pageSlots;

    /** The global click sound for page slots, or {@code null} for default. */
    private final String sound;

    /**
     * Creates a new GuiDefinition instance.
     *
     * @param name      The GUI name.
     * @param titleKey  The language key for the inventory title.
     * @param rows      The number of inventory rows (1-6).
     * @param items     The items mapped by slot index.
     * @param pageSlots The slots reserved for paginated content.
     * @param sound     The global click sound for page slots, or {@code null}.
     */
    public GuiDefinition(String name, String titleKey, int rows,
                         Map<Integer, GuiItem> items, List<Integer> pageSlots, String sound) {
        this.name = name;
        this.titleKey = titleKey;
        this.rows = rows;
        this.items = items;
        this.pageSlots = pageSlots;
        this.sound = sound;
    }

    /**
     * Returns the GUI name.
     *
     * @return The name derived from the configuration file.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the language key for the inventory title.
     *
     * @return The title language key.
     */
    public String getTitleKey() {
        return titleKey;
    }

    /**
     * Returns the number of inventory rows.
     *
     * @return The row count (1-6).
     */
    public int getRows() {
        return rows;
    }

    /**
     * Returns the item at the given inventory slot.
     *
     * @param slot The slot index.
     * @return The {@link GuiItem} at that slot, or {@code null} if empty.
     */
    public GuiItem itemAt(int slot) {
        return items.get(slot);
    }

    /**
     * Returns the item with the given key name, regardless of its slot.
     *
     * @param key The item key (e.g. "BackPage", "TimeSort").
     * @return The {@link GuiItem} with that key, or {@code null} if not found.
     */
    public GuiItem itemByKey(String key) {
        for (GuiItem item : items.values()) {
            if (item.getKey().equals(key)) return item;
        }
        return null;
    }

    /**
     * Returns all configured items in this GUI.
     *
     * @return An unmodifiable collection of all {@link GuiItem} instances.
     */
    public Collection<GuiItem> items() {
        return items.values();
    }

    /**
     * Returns the ordered list of slots reserved for paginated content.
     *
     * @return The page slot list.
     */
    public List<Integer> pageSlots() {
        return pageSlots;
    }

    /**
     * Returns the global click sound for page slots.
     *
     * @return The sound string, or {@code null} for the default sound.
     */
    public String getSound() {
        return sound;
    }
}
