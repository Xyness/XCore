package fr.xyness.XCore.Gui;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import fr.xyness.XCore.Integrations.FloodgateHook;
import fr.xyness.XCore.Lang.LangNamespace;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Centralized GUI utility class for creating and updating inventory items.
 * <p>
 * Uses Paper's Adventure Component API. All display names and lore lines
 * have italic decoration explicitly disabled via {@link #noItalic(Component)}.
 * </p>
 */
@SuppressWarnings("deprecation")
public class GuiUtils {

    /** Cached reflection reference for the 1.20.5+ setItemModel method. */
    private static final Method setItemModelMethod;

    /**
     * Every item flag, resolved once.
     *
     * <p>{@code ItemFlag.values()} clones its backing array on every call, and every item this class
     * builds used to ask for it — including the fifty rebuilt twice a second by a blinking GUI.</p>
     */
    private static final ItemFlag[] ALL_ITEM_FLAGS = ItemFlag.values();

    /** Shared instance for the static helpers, so nothing has to allocate a utils object. */
    private static final GuiUtils SHARED = new GuiUtils();

    /**
     * Textured heads, keyed by texture hash.
     *
     * <p>Building one means creating a {@link PlayerProfile}, parsing a URL and writing the texture
     * property — a page of a player list did that fifty-four times, and did it again on every
     * refresh. The head is built once and cloned per use; only the name and lore differ.</p>
     */
    private static final com.github.benmanes.caffeine.cache.Cache<String, ItemStack> HEAD_CACHE =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(4_096)
                    .expireAfterAccess(java.time.Duration.ofMinutes(30))
                    .build();

    static {
        Method m = null;
        try {
            m = ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
        } catch (Throwable ignored) {}
        setItemModelMethod = m;
    }

    // -------------------------------------------------------------------------
    // Custom model / item model helpers
    // -------------------------------------------------------------------------

    /**
     * Sets custom model data on an {@link ItemMeta}, trying the 1.21.5+ API first
     * and falling back to the legacy {@code setCustomModelData} method.
     *
     * @param meta  The item meta to modify.
     * @param value The custom model data value.
     */
    public static void setCustomModelDataSafe(ItemMeta meta, int value) {
        if (meta == null) return;
        try {
            Class<?> componentClass = Class.forName("org.bukkit.inventory.meta.components.CustomModelDataComponent");
            Method customModelDataMethod = componentClass.getMethod("customModelData", List.class);
            Object componentInstance = customModelDataMethod.invoke(null, List.of((float) value));
            Method setComponentMethod = meta.getClass().getMethod("setCustomModelDataComponent", componentClass);
            setComponentMethod.invoke(meta, componentInstance);
        } catch (Throwable ignored) {
            try {
                meta.setCustomModelData(value);
            } catch (Throwable ignored2) {}
        }
    }

    /**
     * Sets an item model key on an {@link ItemMeta} (1.20.5+).
     *
     * @param meta     The item meta to modify.
     * @param modelKey The namespaced key string (e.g. {@code "my_pack:flame_sword"}).
     */
    public static void setItemModelSafe(ItemMeta meta, String modelKey) {
        if (meta == null || modelKey == null || setItemModelMethod == null) return;
        try {
            NamespacedKey key = NamespacedKey.fromString(modelKey);
            if (key == null) return;
            setItemModelMethod.invoke(meta, key);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Sound helper
    // -------------------------------------------------------------------------

    /**
     * Plays a sound for a player from a namespaced key string (e.g. {@code "minecraft:ui.button.click"}).
     * Uses {@link Registry#SOUNDS} for version-safe sound resolution.
     *
     * @param player   The player to play the sound for.
     * @param soundStr The namespaced sound key, or {@code null} to do nothing.
     */
    public static void playSound(Player player, String soundStr) {
        if (player == null || soundStr == null || soundStr.isBlank()) return;
        try {
            NamespacedKey key = NamespacedKey.fromString(soundStr);
            if (key == null) return;
            Sound sound = Registry.SOUNDS.get(key);
            if (sound != null) player.playSound(player.getLocation(), sound, 0.5f, 1f);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Component helpers
    // -------------------------------------------------------------------------

    /**
     * Removes italic decoration from a component.
     *
     * @param component The component to modify.
     * @return The component with italic set to {@code false}.
     */
    public static Component noItalic(Component component) {
        // Already flat and already non-italic: returning it untouched avoids rebuilding a component
        // tree that would be identical — the common case for lore coming out of the language cache.
        if (component.children().isEmpty()
                && component.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE) {
            return component;
        }
        return component.decoration(TextDecoration.ITALIC, false)
            .children(component.children().stream().map(GuiUtils::noItalic).toList());
    }

    /**
     * Removes italic decoration from all components in a list.
     *
     * @param components The list of components to modify.
     * @return A new list with italic disabled on each component, or {@code null} if input is {@code null}.
     */
    public static List<Component> noItalic(List<Component> components) {
        if (components == null) return null;
        return components.stream().map(GuiUtils::noItalic).toList();
    }

    // -------------------------------------------------------------------------
    // Item creation
    // -------------------------------------------------------------------------

    /**
     * Creates an {@link ItemStack} with a display name and lore.
     *
     * @param material The item material.
     * @param name     The display name component.
     * @param lore     The lore lines, or {@code null} for no lore.
     * @return The constructed item stack.
     */
    public ItemStack createItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(noItalic(name));
            if (lore != null && !lore.isEmpty()) meta.lore(noItalic(lore));
            meta.addItemFlags(ALL_ITEM_FLAGS);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Creates an {@link ItemStack} with a display name and no lore.
     *
     * @param material The item material.
     * @param name     The display name component.
     * @return The constructed item stack.
     */
    public ItemStack createItem(Material material, Component name) {
        return createItem(material, name, null);
    }

    /**
     * Creates an {@link ItemStack} from a {@link GuiItem} definition, applying all features:
     * custom head textures, custom model data, item model, and item flags.
     *
     * @param itemDef The GUI item definition.
     * @param name    The display name component.
     * @param lore    The lore lines, or {@code null} for no lore.
     * @return The constructed item stack with all features applied.
     */
    public ItemStack createItemFromDef(GuiItem itemDef, Component name, List<Component> lore) {
        return createItemFromDef(itemDef, name, lore, null);
    }

    /**
     * Creates an {@link ItemStack} from a {@link GuiItem} definition, applying all features:
     * custom head textures (with Bedrock check), custom model data, item model, and item flags.
     *
     * @param itemDef The GUI item definition.
     * @param name    The display name component.
     * @param lore    The lore lines, or {@code null} for no lore.
     * @param viewer  The player viewing the item, or {@code null} to skip the Bedrock check.
     * @return The constructed item stack with all features applied.
     */
    public ItemStack createItemFromDef(GuiItem itemDef, Component name, List<Component> lore, Player viewer) {
        ItemStack item;
        if (itemDef.isCustomHead()) {
            item = createPlayerHeadWithTexture(itemDef.getTextures(), name, lore, viewer);
        } else {
            item = createItem(Material.valueOf(itemDef.getMaterial()), name, lore);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (itemDef.isCustomModel()) {
                setCustomModelDataSafe(meta, itemDef.getCustomModelValue());
            }
            if (itemDef.isItemModel()) {
                setItemModelSafe(meta, itemDef.getItemModelKey());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // -------------------------------------------------------------------------
    // Blinking bar items
    // -------------------------------------------------------------------------

    /**
     * Builds one of the two faces of a bar item — the navigation and menu buttons every paginated
     * GUI carries along its bottom row — and remembers it.
     *
     * <p>Each addon had its own copy of this method, and each copy rebuilt the item from the
     * language file twice a second for as long as the GUI stayed open. What actually alternates is
     * the click hint, so there are two possible results and the {@link BlinkCache} holds both.</p>
     *
     * <p>The click hint is substituted for {@code %button%} when the lore contains it, and appended
     * after a blank line when it does not — the two conventions that were already in use.</p>
     *
     * <p>Only for items whose content is fully determined by the blink state. An item showing a
     * countdown must keep being rebuilt; see {@link BlinkCache}.</p>
     *
     * @param blink             The cache for this open GUI.
     * @param lang              The addon's language namespace.
     * @param itemDef           The item definition.
     * @param on                The blink state.
     * @param viewer            The player looking at it, for the permission check and the Bedrock
     *                          head fallback.
     * @param loreReplacements  Placeholder pairs for the lore, constant for as long as the GUI is
     *                          open — they are part of what gets cached.
     * @return The item to place in the slot.
     */
    public ItemStack blinkBarItem(BlinkCache blink, LangNamespace lang, GuiItem itemDef, boolean on,
                                  Player viewer, String... loreReplacements) {
        return blink.get(itemDef.getSlot(), on, () -> buildBarItem(lang, itemDef, on, viewer, loreReplacements));
    }

    /**
     * The uncached build behind {@link #blinkBarItem}, for a slot that cannot be cached.
     *
     * @param lang             The addon's language namespace.
     * @param itemDef          The item definition.
     * @param on               The blink state.
     * @param viewer           The player looking at it.
     * @param loreReplacements Placeholder pairs for the lore.
     * @return The item to place in the slot.
     */
    public ItemStack buildBarItem(LangNamespace lang, GuiItem itemDef, boolean on, Player viewer,
                                  String... loreReplacements) {
        Component title = lang.getComponent(itemDef.getTitleKey());

        String permission = itemDef.getPermission();
        boolean allowed = permission == null || permission.isBlank()
                || viewer == null || viewer.hasPermission(permission);

        String onKey = itemDef.getButtonOnKey() != null && !itemDef.getButtonOnKey().isBlank()
                ? itemDef.getButtonOnKey() : "gui-btn-click-access-on";
        String offKey = itemDef.getButtonOffKey() != null && !itemDef.getButtonOffKey().isBlank()
                ? itemDef.getButtonOffKey() : "gui-btn-click-access-off";
        String button = allowed
                ? lang.getMessageString(on ? onKey : offKey)
                : lang.getMessageString(on ? "gui-btn-no-perm-on" : "gui-btn-no-perm-off");

        String loreKey = itemDef.getLoreKey();
        if (loreKey == null || loreKey.isBlank()) {
            return createItemFromDef(itemDef, title, null, viewer);
        }

        String lore = lang.getMessage(loreKey, loreReplacements);
        lore = lore.contains("%button%") ? lore.replace("%button%", button)
                                         : lore + "\n<gray></gray>\n" + button;
        return createItemFromDef(itemDef, title, lang.getLore(lore), viewer);
    }

    // -------------------------------------------------------------------------
    // Item update
    // -------------------------------------------------------------------------

    /**
     * Updates the display name and/or lore of an existing item in an inventory.
     *
     * @param inv   The inventory containing the item.
     * @param slot  The slot index of the item to update.
     * @param title The new display name, or {@code null} to keep the current name.
     * @param lore  The new lore lines, or {@code null} to keep the current lore.
     */
    public void updateGuiItem(Inventory inv, int slot, Component title, List<Component> lore) {
        ItemStack existing = inv.getItem(slot);
        if (existing == null) return;
        // Cloned first: on CraftBukkit, getItem() hands back a live mirror of the stack the server
        // is holding. Writing meta straight into it mutates what a clicking player is reading at the
        // same moment — the "empty heads / container_set_slot kick" family of bug.
        ItemStack item = existing.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (title != null) meta.displayName(noItalic(title));
        if (lore != null) meta.lore(noItalic(lore));
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    // -------------------------------------------------------------------------
    // Navigation lore builder
    // -------------------------------------------------------------------------

    /**
     * Builds the lore for a navigation button (back, previous, next).
     * <p>
     * Replaces {@code %button%} in the lore template with the appropriate
     * on/off button string based on the blink state. Additional placeholder
     * pairs can be supplied via the varargs parameter.
     * </p>
     *
     * @param lang         The lang namespace to resolve keys from.
     * @param loreKey      The lang key for the lore template (must contain {@code %button%}).
     * @param buttonOffKey The lang key for the button-off text.
     * @param buttonOnKey  The lang key for the button-on text.
     * @param check        The current blink state ({@code true} = on).
     * @param replacements Optional pairs of placeholder name and value (e.g. "page", "2").
     * @return The parsed lore components.
     */
    public List<Component> buildNavLore(LangNamespace lang, String loreKey, String buttonOffKey, String buttonOnKey, boolean check, String... replacements) {
        String button = check ? lang.getMessageString(buttonOnKey) : lang.getMessageString(buttonOffKey);
        String loreStr = lang.getMessageString(loreKey);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            loreStr = loreStr.replace("%" + replacements[i] + "%", replacements[i + 1]);
        }
        loreStr = loreStr.replace("%button%", button);
        return lang.getLore(loreStr);
    }

    // -------------------------------------------------------------------------
    // Player head creation
    // -------------------------------------------------------------------------

    /**
     * Creates a player head {@link ItemStack} with a custom skin texture.
     *
     * @param texture The texture hash (appended to the Minecraft textures URL).
     * @param name    The display name component.
     * @param lore    The lore lines, or {@code null} for no lore.
     * @return The constructed player head item stack.
     */
    public ItemStack createPlayerHeadWithTexture(String texture, Component name, List<Component> lore) {
        return createPlayerHeadWithTexture(texture, name, lore, null);
    }

    /**
     * Creates a player head {@link ItemStack} with a custom skin texture.
     * <p>
     * If the viewer is a Bedrock player (detected via Floodgate/Geyser), the texture
     * is skipped because Bedrock clients cannot render custom skull textures. A plain
     * {@link Material#PLAYER_HEAD} is returned instead.
     * </p>
     *
     * @param texture The texture hash (appended to the Minecraft textures URL).
     * @param name    The display name component.
     * @param lore    The lore lines, or {@code null} for no lore.
     * @param viewer  The player viewing the item, or {@code null} to skip the Bedrock check.
     * @return The constructed player head item stack.
     */
    public ItemStack createPlayerHeadWithTexture(String texture, Component name, List<Component> lore, Player viewer) {
        // Bedrock players can't see custom skull textures
        if (viewer != null && FloodgateHook.isBedrockPlayer(viewer)) {
            return createItem(Material.PLAYER_HEAD, name, lore);
        }
        if (texture == null || texture.isBlank()) {
            return createItem(Material.PLAYER_HEAD, name, lore);
        }

        // The textured head is built once per skin and reused; only name and lore change per use.
        ItemStack head = HEAD_CACHE.get(texture, GuiUtils::buildTexturedHead).clone();
        ItemMeta meta = head.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(noItalic(name));
            if (lore != null && !lore.isEmpty()) meta.lore(noItalic(lore));
            meta.addItemFlags(ALL_ITEM_FLAGS);
            head.setItemMeta(meta);
        }
        return head;
    }

    /**
     * Builds the bare textured head for a skin hash. Returns an untextured head on a bad URL.
     *
     * @param texture The texture hash.
     * @return A {@link Material#PLAYER_HEAD} carrying the skin, never {@code null}.
     */
    private static ItemStack buildTexturedHead(String texture) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            URI uri = new URI("http://textures.minecraft.net/texture/" + texture);
            URL url = uri.toURL();
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(url);
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
            head.setItemMeta(meta);
        } catch (MalformedURLException | URISyntaxException e) {
            return head;
        }
        return head;
    }

    /**
     * Creates a Bedrock-safe item. For Bedrock players, {@link Material#PLAYER_HEAD}
     * is replaced with {@link Material#SKELETON_SKULL} since custom skull textures
     * do not render correctly on Bedrock clients.
     *
     * @param material The item material.
     * @param name     The display name component.
     * @param lore     The lore lines, or {@code null} for no lore.
     * @return The constructed item stack with Bedrock-safe material.
     */
    public static ItemStack createBedrockSafeItem(Material material, Component name, List<Component> lore) {
        if (material == Material.PLAYER_HEAD) {
            material = Material.SKELETON_SKULL;
        }
        return SHARED.createItem(material, name, lore);
    }

    /**
     * Clears the textured-head cache. Called on reload, when skins may have been refreshed.
     */
    public static void clearHeadCache() {
        HEAD_CACHE.invalidateAll();
    }
}
