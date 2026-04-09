package fr.xyness.XCore.Gui;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;

/**
 * Fluent builder for constructing Bukkit inventories with optional blink slots.
 * <p>
 * Supports setting a title, row count, holder, static items, and blink slot
 * pairs (two item states that alternate). Use {@link #build()} to create the
 * final {@link Inventory}.
 * </p>
 */
public class GuiBuilder {

    /** The inventory title component. */
    private Component title = Component.empty();

    /** The number of rows (1-6). */
    private int rows = 6;

    /** The inventory holder identifying the GUI type. */
    private InventoryHolder holder;

    /** Static items keyed by slot index. */
    private final Map<Integer, ItemStack> items = new HashMap<>();

    /** Blink slot pairs: slot -> [stateA, stateB]. */
    private final Map<Integer, ItemStack[]> blinkSlots = new HashMap<>();

    /** The initial page number for paginated GUIs. */
    private int page = 1;

    /**
     * Sets the inventory title.
     *
     * @param title The title component.
     * @return This builder for chaining.
     */
    public GuiBuilder title(Component title) {
        this.title = title;
        return this;
    }

    /**
     * Sets the number of inventory rows.
     *
     * @param rows The row count (1-6).
     * @return This builder for chaining.
     */
    public GuiBuilder rows(int rows) {
        this.rows = Math.max(1, Math.min(6, rows));
        return this;
    }

    /**
     * Sets the inventory holder.
     *
     * @param holder The inventory holder.
     * @return This builder for chaining.
     */
    public GuiBuilder holder(InventoryHolder holder) {
        this.holder = holder;
        return this;
    }

    /**
     * Places an item at the given slot.
     *
     * @param slot The slot index.
     * @param item The item stack.
     * @return This builder for chaining.
     */
    public GuiBuilder item(int slot, ItemStack item) {
        this.items.put(slot, item);
        return this;
    }

    /**
     * Registers a blink slot with two alternating item states.
     *
     * @param slot   The slot index.
     * @param stateA The first item state (shown when blink state is {@code true}).
     * @param stateB The second item state (shown when blink state is {@code false}).
     * @return This builder for chaining.
     */
    public GuiBuilder blink(int slot, ItemStack stateA, ItemStack stateB) {
        this.blinkSlots.put(slot, new ItemStack[]{stateA, stateB});
        return this;
    }

    /**
     * Sets the initial page number.
     *
     * @param page The page number.
     * @return This builder for chaining.
     */
    public GuiBuilder page(int page) {
        this.page = page;
        return this;
    }

    /**
     * Builds the Bukkit {@link Inventory} with all configured items.
     * <p>
     * Static items and the first state of blink items are placed into the inventory.
     * </p>
     *
     * @return The constructed inventory.
     */
    public Inventory build() {
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);

        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            inv.setItem(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<Integer, ItemStack[]> entry : blinkSlots.entrySet()) {
            inv.setItem(entry.getKey(), entry.getValue()[0]);
        }

        return inv;
    }

    /**
     * Returns the blink slot map.
     *
     * @return A map of slot index to an array of two {@link ItemStack} states.
     */
    public Map<Integer, ItemStack[]> getBlinkSlots() {
        return blinkSlots;
    }

    /**
     * Returns the configured initial page number.
     *
     * @return The page number.
     */
    public int getPage() {
        return page;
    }

    /**
     * Returns the configured inventory holder.
     *
     * @return The holder, or {@code null} if not set.
     */
    public InventoryHolder getHolder() {
        return holder;
    }
}
