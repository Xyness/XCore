package fr.xyness.XCore.Gui;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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

    /**
     * Removes italic decoration from a component.
     *
     * @param component The component to modify.
     * @return The component with italic set to {@code false}.
     */
    public static Component noItalic(Component component) {
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
            meta.addItemFlags(ItemFlag.values());
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
     * Updates the display name and/or lore of an existing item in an inventory.
     *
     * @param inv   The inventory containing the item.
     * @param slot  The slot index of the item to update.
     * @param title The new display name, or {@code null} to keep the current name.
     * @param lore  The new lore lines, or {@code null} to keep the current lore.
     */
    public void updateGuiItem(Inventory inv, int slot, Component title, List<Component> lore) {
        ItemStack item = inv.getItem(slot);
        if (item != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (title != null) meta.displayName(noItalic(title));
                if (lore != null) meta.lore(noItalic(lore));
                item.setItemMeta(meta);
                inv.setItem(slot, item);
            }
        }
    }

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

        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            String skinUrl = "http://textures.minecraft.net/texture/" + texture;
            try {
                URI uri = new URI(skinUrl);
                URL url = uri.toURL();
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(url);
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
                if (name != null) meta.displayName(noItalic(name));
                if (lore != null && !lore.isEmpty()) meta.lore(noItalic(lore));
                meta.addItemFlags(ItemFlag.values());
                head.setItemMeta(meta);
            } catch (MalformedURLException | URISyntaxException e) {
                return head;
            }
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
        GuiUtils utils = new GuiUtils();
        return utils.createItem(material, name, lore);
    }
}
