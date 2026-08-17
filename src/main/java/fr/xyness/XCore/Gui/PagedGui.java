package fr.xyness.XCore.Gui;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import fr.xyness.XCore.Lang.LangNamespace;
import fr.xyness.XCore.Utils.SchedulerAdapter;

/**
 * The paginated screen every addon has been writing by hand.
 *
 * <p>Page maths, the bottom bar at 48 / 49 / 50, the blink task and its cancellation, the item cache
 * and the sound, permission and action keys from the YAML definition are all handled here. A
 * subclass supplies the list and how to draw one entry.</p>
 *
 * <pre>{@code
 * public class WarpGui extends PagedGui<Warp> {
 *
 *     protected List<Warp> items(Player viewer) { return manager.warps(); }
 *
 *     protected ItemStack render(Warp warp, Player viewer, boolean blinkOn) {
 *         return guiUtils().createItemFromDef(itemDef, title(warp), lore(warp), viewer);
 *     }
 *
 *     protected void onItemClick(Warp warp, Player viewer, ClickType click) {
 *         manager.teleport(viewer, warp);
 *     }
 * }
 * }</pre>
 *
 * <p>Clicks reach the subclass on their own: XCore listens for screens whose holder is a
 * {@code PagedGui}, so nothing has to be registered.</p>
 *
 * @param <T> What one page slot shows.
 */
public abstract class PagedGui<T> implements InventoryHolder {

    /** How often the bar buttons alternate, in ticks. */
    protected static final long BLINK_PERIOD = 10L;

    /** Bottom bar, the same three slots in every addon. */
    protected static final int SLOT_PREVIOUS = 48;
    protected static final int SLOT_BACK = 49;
    protected static final int SLOT_NEXT = 50;

    private final SchedulerAdapter scheduler;
    private final GuiUtils guiUtils;
    private final LangNamespace lang;
    private final GuiDefinition definition;

    private Inventory inventory;
    private Player viewer;
    private int page = 1;
    private int maxPage = 1;
    private List<T> pageItems = List.of();
    private final BlinkCache blink = new BlinkCache();
    private final boolean[] blinkState = {true};
    private Object blinkTask;

    /**
     * @param scheduler  The scheduler, for the blink task.
     * @param guiUtils   The addon's shared GUI utilities.
     * @param lang       The addon's language namespace.
     * @param definition The YAML definition of this screen.
     */
    protected PagedGui(SchedulerAdapter scheduler, GuiUtils guiUtils, LangNamespace lang, GuiDefinition definition) {
        this.scheduler = scheduler;
        this.guiUtils = guiUtils;
        this.lang = lang;
        this.definition = definition;
    }

    // -------------------------------------------------------------------------
    // What a subclass provides
    // -------------------------------------------------------------------------

    /**
     * @param viewer Who is looking.
     * @return Everything to page through. Called once per open and per page change.
     */
    protected abstract List<T> items(Player viewer);

    /**
     * Draws one entry.
     *
     * @param item     The entry.
     * @param viewer   Who is looking.
     * @param blinkOn  The current blink state.
     * @return The item to place, or {@code null} to leave the slot empty.
     */
    protected abstract ItemStack render(T item, Player viewer, boolean blinkOn);

    /**
     * Called when an entry is clicked.
     *
     * @param item   The entry.
     * @param viewer Who clicked.
     * @param click  How they clicked.
     */
    protected void onItemClick(T item, Player viewer, ClickType click) {
        // Nothing by default: a list can be read-only.
    }

    /**
     * Called when the middle button of the bar is clicked.
     *
     * @param viewer Who clicked.
     */
    protected void onBack(Player viewer) {
        viewer.closeInventory();
    }

    /**
     * Called for a click on any slot that is neither an entry nor a navigation button.
     *
     * @param slot   The slot clicked.
     * @param viewer Who clicked.
     * @param click  How they clicked.
     */
    protected void onOtherClick(int slot, Player viewer, ClickType click) {
        // Nothing by default.
    }

    /**
     * Draws the fixed part of the screen: borders, filters, information items.
     *
     * @param inventory The inventory being filled.
     * @param viewer    Who is looking.
     * @param blinkOn   The current blink state.
     */
    protected void decorate(Inventory inventory, Player viewer, boolean blinkOn) {
        // Nothing by default.
    }

    /**
     * Extra placeholders for the title, on top of {@code page} and {@code max}.
     *
     * @return Alternating names and values, empty by default.
     */
    protected String[] titlePlaceholders() {
        return new String[0];
    }

    // -------------------------------------------------------------------------
    // Opening and drawing
    // -------------------------------------------------------------------------

    /**
     * Opens the screen at a given page.
     *
     * @param player The viewer.
     * @param page   The page, 1-based; clamped to what exists.
     */
    public void open(Player player, int page) {
        this.viewer = player;
        this.page = Math.max(1, page);

        List<T> all = items(player);
        Pagination<T> pagination = new Pagination<>(all, Math.max(1, definition.pageSlots().size()));
        this.maxPage = pagination.getMaxPage();
        if (this.page > maxPage) this.page = maxPage;
        this.pageItems = pagination.getPage(this.page);

        String[] extra = titlePlaceholders();
        String[] placeholders = new String[extra.length + 4];
        placeholders[0] = "page";
        placeholders[1] = String.valueOf(this.page);
        placeholders[2] = "max";
        placeholders[3] = String.valueOf(maxPage);
        System.arraycopy(extra, 0, placeholders, 4, extra.length);

        this.inventory = Bukkit.createInventory(this, definition.getRows() * 9,
                lang.getComponent(definition.getTitleKey(), placeholders));

        blink.clear();
        draw();

        scheduler.runEntityTask(player, () -> {
            player.openInventory(inventory);
            startBlinking();
        });
    }

    /**
     * Moves to another page, keeping the screen open.
     *
     * @param page The page to show.
     */
    public void goTo(int page) {
        if (viewer == null) return;
        open(viewer, page);
    }

    /** Fills the inventory with the current page. */
    protected void draw() {
        boolean on = blinkState[0];
        List<Integer> slots = definition.pageSlots();

        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            if (i < pageItems.size()) {
                inventory.setItem(slot, render(pageItems.get(i), viewer, on));
            } else {
                inventory.setItem(slot, null);
            }
        }

        decorate(inventory, viewer, on);
        drawBar(on);
    }

    /** Draws the three navigation buttons, from the definition when it declares them. */
    protected void drawBar(boolean on) {
        GuiItem previous = definition.itemAt(SLOT_PREVIOUS);
        if (previous != null && page > 1) {
            inventory.setItem(SLOT_PREVIOUS, guiUtils.blinkBarItem(blink, lang, previous, on, viewer,
                    "page", String.valueOf(page - 1), "max", String.valueOf(maxPage)));
        } else if (previous != null) {
            inventory.setItem(SLOT_PREVIOUS, null);
        }

        GuiItem back = definition.itemAt(SLOT_BACK);
        if (back != null) {
            inventory.setItem(SLOT_BACK, guiUtils.blinkBarItem(blink, lang, back, on, viewer,
                    "page", String.valueOf(page), "max", String.valueOf(maxPage)));
        }

        GuiItem next = definition.itemAt(SLOT_NEXT);
        if (next != null && page < maxPage) {
            inventory.setItem(SLOT_NEXT, guiUtils.blinkBarItem(blink, lang, next, on, viewer,
                    "page", String.valueOf(page + 1), "max", String.valueOf(maxPage)));
        } else if (next != null) {
            inventory.setItem(SLOT_NEXT, null);
        }
    }

    private void startBlinking() {
        stopBlinking();
        blinkTask = scheduler.runEntityTaskTimer(viewer, () -> {
            if (viewer == null || !viewer.isOnline()) {
                stopBlinking();
                return;
            }
            blinkState[0] = !blinkState[0];
            draw();
        }, BLINK_PERIOD, BLINK_PERIOD);
    }

    /** Stops the blink task. Called on close, and when the viewer disconnects. */
    public void stopBlinking() {
        if (blinkTask != null) {
            scheduler.cancelTask(blinkTask);
            blinkTask = null;
        }
    }

    // -------------------------------------------------------------------------
    // Clicks
    // -------------------------------------------------------------------------

    /**
     * Routes a click. Called by XCore's listener; a subclass rarely needs to touch it.
     *
     * @param event The click.
     */
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(inventory)) return;

        int slot = event.getSlot();
        if (!GuiUtils.handleCommonFeatures(player, slot, event.getClick(), definition)) return;

        if (slot == SLOT_PREVIOUS && page > 1) {
            goTo(page - 1);
            return;
        }
        if (slot == SLOT_NEXT && page < maxPage) {
            goTo(page + 1);
            return;
        }
        if (slot == SLOT_BACK) {
            onBack(player);
            return;
        }

        int index = definition.pageSlots().indexOf(slot);
        if (index >= 0 && index < pageItems.size()) {
            onItemClick(pageItems.get(index), player, event.getClick());
            return;
        }
        onOtherClick(slot, player, event.getClick());
    }

    // -------------------------------------------------------------------------
    // Accessors for subclasses
    // -------------------------------------------------------------------------

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** @return The player this screen was opened for. */
    protected Player viewer() {
        return viewer;
    }

    /** @return The page being shown, 1-based. */
    protected int page() {
        return page;
    }

    /** @return How many pages the current list fills. */
    protected int maxPage() {
        return maxPage;
    }

    /** @return The entries on the current page. */
    protected List<T> pageItems() {
        return pageItems;
    }

    /** @return The YAML definition behind this screen. */
    protected GuiDefinition definition() {
        return definition;
    }

    /** @return The addon's GUI utilities. */
    protected GuiUtils guiUtils() {
        return guiUtils;
    }

    /** @return The addon's language namespace. */
    protected LangNamespace lang() {
        return lang;
    }

    /** @return The cache holding both faces of every blinking slot. */
    protected BlinkCache blink() {
        return blink;
    }

    /** @return The scheduler. */
    protected SchedulerAdapter scheduler() {
        return scheduler;
    }
}
