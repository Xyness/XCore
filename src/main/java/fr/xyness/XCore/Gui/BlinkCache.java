package fr.xyness.XCore.Gui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.inventory.ItemStack;

/**
 * Holds the two faces of every blinking item in one open GUI.
 *
 * <h2>Why</h2>
 * A blink task fires twice a second and, in every addon, used to rebuild each item from scratch on
 * every tick: read the language file, substitute the placeholders, parse each lore line as
 * MiniMessage, allocate an {@link ItemStack} and an {@code ItemMeta}, strip italics. All of that to
 * produce one of exactly <b>two</b> possible results — the button lit, or the button dimmed.
 *
 * <p>The two faces are built once, on the first tick that needs them, and reused from then on. The
 * timer is left with a map lookup and an {@code inv.setItem}.</p>
 *
 * <h2>When not to use it</h2>
 * Only for items whose content depends on nothing but the blink state. An item showing a countdown,
 * a live price or a remaining time genuinely changes between ticks and must keep being rebuilt —
 * cache it and it will freeze on screen.
 *
 * <h2>Lifecycle</h2>
 * One instance per open GUI, discarded with it. {@link #clear()} on a page change, or
 * {@link #invalidate(int)} for a single slot whose content just changed (a toggled filter, a
 * purchased item).
 *
 * <pre>{@code
 * BlinkCache blink = new BlinkCache();
 * // inside the blink task:
 * inv.setItem(slot, blink.get(slot, check[0],
 *         () -> guiUtils.createItemFromDef(itemDef, title, lang.getLore(loreStr), player)));
 * }</pre>
 */
public final class BlinkCache {

    /** Both faces of every slot, keyed by {@code slot * 2 + state}. */
    private final Map<Integer, ItemStack> items = new ConcurrentHashMap<>();

    /**
     * Returns the item for a slot in the given blink state, building it once on first use.
     *
     * <p>The returned stack is shared, not copied — {@code Inventory#setItem} copies it into the
     * container anyway. Do not mutate it; build a new one instead.</p>
     *
     * @param slot    The inventory slot.
     * @param on      The blink state.
     * @param builder Builds the item for that state. Called at most once per (slot, state).
     * @return The item to place, or {@code null} if the builder returned {@code null}.
     */
    public ItemStack get(int slot, boolean on, Supplier<ItemStack> builder) {
        int key = (slot << 1) | (on ? 1 : 0);
        ItemStack cached = items.get(key);
        if (cached != null) return cached;
        ItemStack built = builder.get();
        if (built != null) items.put(key, built);
        return built;
    }

    /**
     * Drops both faces of one slot, so the next tick rebuilds them.
     *
     * @param slot The inventory slot.
     */
    public void invalidate(int slot) {
        items.remove((slot << 1));
        items.remove((slot << 1) | 1);
    }

    /**
     * Drops every cached face. Call on a page change or any full re-render.
     */
    public void clear() {
        items.clear();
    }

    /** @return How many faces are currently held (both states counted separately). */
    public int size() {
        return items.size();
    }
}
