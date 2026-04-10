package fr.xyness.XCore.Gui;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable data class representing a single configurable item in a GUI.
 * <p>
 * Each instance holds the visual properties (material, textures, model data),
 * language keys for display name and lore, permission requirements,
 * and a map of click actions keyed by {@link ClickKind}.
 * </p>
 */
public class GuiItem {

    /** The item identifier used in configuration. */
    private final String key;

    /** The inventory slot this item occupies. */
    private final int slot;

    /** The material name (e.g. "STONE", "PLAYER_HEAD"). */
    private final String material;

    /** Whether this item is a custom head with base64 textures. */
    private final boolean customHead;

    /** The base64 head texture string (only meaningful if {@link #customHead} is {@code true}). */
    private final String textures;

    /** Whether custom model data is set on this item. */
    private final boolean customModel;

    /** The custom model data value (only meaningful if {@link #customModel} is {@code true}). */
    private final int customModelValue;

    /** The item model key for 1.20.5+ item model support. */
    private final String itemModelKey;

    /** The language key used to resolve the display name. */
    private final String titleKey;

    /** The language key used to resolve the lore lines. */
    private final String loreKey;

    /** The language key used when a toggle button is in the ON state. */
    private final String buttonOnKey;

    /** The language key used when a toggle button is in the OFF state. */
    private final String buttonOffKey;

    /** The permission required to use this item, or {@code null} if none. */
    private final String permission;

    /** The sound to play on click, or {@code null} for the default sound. */
    private final String sound;

    /** Actions mapped by click type. */
    private final Map<ClickKind, List<GuiAction>> actions;

    /**
     * Creates a new GuiItem instance with all properties.
     *
     * @param key              The item identifier.
     * @param slot             The inventory slot.
     * @param material         The material name.
     * @param customHead       Whether this is a custom textured head.
     * @param textures         The base64 head texture string.
     * @param customModel      Whether custom model data is set.
     * @param customModelValue The custom model data value.
     * @param itemModelKey     The item model key for 1.20.5+.
     * @param titleKey         The language key for the display name.
     * @param loreKey          The language key for the lore.
     * @param buttonOnKey      The language key for the button ON state.
     * @param buttonOffKey     The language key for the button OFF state.
     * @param permission       The required permission, or {@code null}.
     * @param sound            The click sound, or {@code null} for default.
     * @param actions          The actions mapped by click type.
     */
    public GuiItem(String key, int slot, String material, boolean customHead, String textures,
                   boolean customModel, int customModelValue, String itemModelKey,
                   String titleKey, String loreKey, String buttonOnKey, String buttonOffKey,
                   String permission, String sound, Map<ClickKind, List<GuiAction>> actions) {
        this.key = key;
        this.slot = slot;
        this.material = material;
        this.customHead = customHead;
        this.textures = textures;
        this.customModel = customModel;
        this.customModelValue = customModelValue;
        this.itemModelKey = itemModelKey;
        this.titleKey = titleKey;
        this.loreKey = loreKey;
        this.buttonOnKey = buttonOnKey;
        this.buttonOffKey = buttonOffKey;
        this.permission = permission;
        this.sound = sound;
        this.actions = actions;
    }

    /** @return The item identifier. */
    public String getKey() { return key; }

    /** @return The inventory slot this item occupies. */
    public int getSlot() { return slot; }

    /** @return The material name. */
    public String getMaterial() { return material; }

    /** @return {@code true} if this is a custom head with textures. */
    public boolean isCustomHead() { return customHead; }

    /** @return The base64 head texture string, or {@code null}. */
    public String getTextures() { return textures; }

    /** @return {@code true} if custom model data is present. */
    public boolean isCustomModel() { return customModel; }

    /** @return The custom model data integer. */
    public int getCustomModelValue() { return customModelValue; }

    /** @return The item model key string. */
    public String getItemModelKey() { return itemModelKey; }

    /** @return {@code true} if an item model key is present. */
    public boolean isItemModel() { return itemModelKey != null && !itemModelKey.isBlank(); }

    /** @return The title language key. */
    public String getTitleKey() { return titleKey; }

    /** @return The lore language key. */
    public String getLoreKey() { return loreKey; }

    /** @return The button ON language key. */
    public String getButtonOnKey() { return buttonOnKey; }

    /** @return The button OFF language key. */
    public String getButtonOffKey() { return buttonOffKey; }

    /** @return The permission string, or {@code null} if none required. */
    public String getPermission() { return permission; }

    /** @return The click sound string, or {@code null} for the default sound. */
    public String getSound() { return sound; }

    /** @return An unmodifiable view of the actions map. */
    public Map<ClickKind, List<GuiAction>> getActionsMap() { return actions; }

    /**
     * Returns the list of actions for the given click type.
     *
     * @param kind The click type.
     * @return The list of actions, or an empty list if none are defined.
     */
    public List<GuiAction> getActions(ClickKind kind) {
        return actions.getOrDefault(kind, Collections.emptyList());
    }
}
