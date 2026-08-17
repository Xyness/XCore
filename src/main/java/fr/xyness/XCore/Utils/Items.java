package fr.xyness.XCore.Utils;

import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Turning items into strings and back, and handing them to a player without losing any.
 *
 * <p>The format is Paper's own {@code serializeAsBytes}, so stored items survive a version upgrade
 * the way the server's own data does.</p>
 */
public final class Items {

    private Items() {}

    /**
     * Encodes an item for storage.
     *
     * @param item The item, or {@code null}.
     * @return The encoded item, or {@code null} if there was nothing to encode.
     */
    public static String toBase64(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /**
     * Decodes an item written by {@link #toBase64(ItemStack)}.
     *
     * @param encoded The stored string, or {@code null}.
     * @return The item, or {@code null} when the string is empty or unreadable.
     */
    public static ItemStack fromBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Encodes several items at once, keeping their order.
     *
     * @param items The items.
     * @return The encoded list, or {@code null} when there was nothing to encode.
     */
    public static String toBase64(Collection<ItemStack> items) {
        if (items == null || items.isEmpty()) return null;
        try {
            return Base64.getEncoder().encodeToString(
                    ItemStack.serializeItemsAsBytes(items.toArray(new ItemStack[0])));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Decodes a list written by {@link #toBase64(Collection)}.
     *
     * @param encoded The stored string.
     * @return The items, empty when the string is unreadable.
     */
    public static List<ItemStack> listFromBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        try {
            return List.of(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded)));
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Gives an item to a player, dropping at their feet whatever does not fit. Must run on the
     * player's own thread.
     *
     * @param player The receiver.
     * @param item   The item to hand over.
     * @return How many were dropped on the ground instead of stored.
     */
    public static int give(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir()) return 0;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
        int dropped = 0;
        for (ItemStack left : leftovers.values()) {
            dropped += left.getAmount();
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        return dropped;
    }

    /**
     * Tells how many of an item an inventory can still take.
     *
     * @param inventory The inventory to measure.
     * @param item      The item, used for its type and its maximum stack size.
     * @return The number that would fit right now.
     */
    public static int roomFor(Inventory inventory, ItemStack item) {
        if (inventory == null || item == null || item.getType().isAir()) return 0;
        int max = item.getMaxStackSize();
        int room = 0;
        for (ItemStack slot : inventory.getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                room += max;
            } else if (slot.isSimilar(item)) {
                room += Math.max(0, max - slot.getAmount());
            }
        }
        return room;
    }

    /**
     * Counts how many of an item a player carries.
     *
     * @param player The player.
     * @param sample The item to match, compared with {@code isSimilar}.
     * @return The total held.
     */
    public static int count(Player player, ItemStack sample) {
        if (player == null || sample == null) return 0;
        int total = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot != null && slot.isSimilar(sample)) total += slot.getAmount();
        }
        return total;
    }

    /**
     * Removes a number of matching items from a player's inventory.
     *
     * @param player The player.
     * @param sample The item to match.
     * @param amount How many to take.
     * @return {@code true} when the full amount was taken; nothing is removed otherwise.
     */
    public static boolean take(Player player, ItemStack sample, int amount) {
        if (player == null || sample == null || amount <= 0) return false;
        if (count(player, sample) < amount) return false;

        int left = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        Map<Integer, ItemStack> updated = new HashMap<>();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack slot = contents[i];
            if (slot == null || !slot.isSimilar(sample)) continue;
            int taken = Math.min(left, slot.getAmount());
            left -= taken;
            if (slot.getAmount() - taken <= 0) {
                updated.put(i, null);
            } else {
                ItemStack copy = slot.clone();
                copy.setAmount(slot.getAmount() - taken);
                updated.put(i, copy);
            }
        }
        updated.forEach((slot, stack) -> player.getInventory().setItem(slot, stack));
        return true;
    }
}
