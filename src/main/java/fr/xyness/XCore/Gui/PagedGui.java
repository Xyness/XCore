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
 * Base class for a paginated screen: pages, the bar at 48 / 49 / 50, the blink task and its
 * cancellation, and the sound, permission and action keys of the YAML definition. A subclass
 * supplies the list and how to draw one entry.
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
 * <p>XCore listens for these screens itself, so clicks arrive without registering anything.</p>
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
    private Object refreshTask;
    private long ticksSinceRefresh;

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
     * Everything to page through.
     *
     * <p>Called off the server thread, once per open and per page change, so reading the database
     * here is the expected thing to do.</p>
     *
     * @param viewer Who is looking.
     * @return The full list; paging is handled for you.
     */
    protected abstract List<T> items(Player viewer);

    /**
     * How many entries there are in total, when the list is paged by the database.
     *
     * <p>Left at -1 the whole list comes from {@link #items(Player)} and is paged in memory, which
     * is what almost every screen wants. Override this together with
     * {@link #loadPage(Player, int, int)} when the list is large enough that holding it per viewer
     * is out of the question — the player table of a server that has seen fifty thousand people,
     * for one.</p>
     *
     * <p>Called off the server thread, before the page is loaded.</p>
     *
     * @param viewer Who is looking.
     * @return The total count, or -1 to page in memory.
     */
    protected int totalItems(Player viewer) {
        return -1;
    }

    /**
     * One page of entries, when {@link #totalItems(Player)} says the database does the paging.
     *
     * <p>Called off the server thread, once per open and per page change.</p>
     *
     * @param viewer  Who is looking.
     * @param page    The page, 1-based and already clamped.
     * @param perPage How many entries fit on a page.
     * @return That page's entries.
     */
    protected List<T> loadPage(Player viewer, int page, int perPage) {
        return List.of();
    }

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

    /**
     * The language key of the title. The definition's own key unless a screen serves several lists
     * from one YAML file and each wants its own heading.
     *
     * @return The key to look up.
     */
    protected String titleKey() {
        return definition.getTitleKey();
    }

    /**
     * Whether {@link #render} has to run again on every blink tick.
     *
     * <p>Off by default, and that is the answer for nearly every list: the entries are drawn once
     * per page and only the bar alternates. Turning it on means rebuilding a full page of items
     * twice a second — worth it only when an entry really does have two faces.</p>
     *
     * @return {@code true} to redraw the entries along with the bar.
     */
    protected boolean itemsBlink() {
        return false;
    }

    /**
     * How long a rendered entry is kept before being built again, in ticks.
     *
     * <p>Only consulted when {@link #itemsBlink()} is on. The two faces of an entry are cached, so
     * the blink task places an item instead of parsing a lore; every so often the cache is dropped
     * so anything live in there — a remaining time, a current bid — catches up. Five seconds is a
     * fair trade for a countdown shown in minutes.</p>
     *
     * <p>Return 0 for a value that must be exact on every tick, and accept the cost.</p>
     *
     * @return The lifetime in ticks, 0 to disable the cache.
     */
    protected long itemCacheTicks() {
        return 100L;
    }

    /**
     * How often the list is read again while the screen stays open, in ticks.
     *
     * <p>0 — the default — never: a list of warps or of sanctions does not change under the reader
     * often enough to be worth a query. A screen showing something other people are changing while
     * it is open, an auction house being the obvious one, returns an interval here.</p>
     *
     * @return The interval in ticks, 0 to never re-read.
     */
    protected long refreshTicks() {
        return 0L;
    }

    // -------------------------------------------------------------------------
    // Opening and drawing
    // -------------------------------------------------------------------------

    /**
     * Opens the screen at a given page.
     *
     * <p>{@link #items(Player)} is called off the server thread, because a list screen almost
     * always reads the database to build it. Everything after that — creating the inventory,
     * drawing it, opening it — happens on the viewer's own thread.</p>
     *
     * @param player The viewer.
     * @param page   The page, 1-based; clamped to what exists.
     */
    public void open(Player player, int page) {
        this.viewer = player;
        int requested = Math.max(1, page);

        scheduler.runAsyncTask(() -> {
            Loaded<T> loaded = read(player, requested);
            scheduler.runEntityTask(player, () -> {
                if (!player.isOnline()) return;
                build(loaded.items(), loaded.page(), loaded.pages());
                player.openInventory(inventory);
                startBlinking();
            });
        });
    }

    /**
     * One page's worth of entries and where it sits.
     *
     * @param items The entries.
     * @param page  The page they belong to.
     * @param pages How many pages there are.
     * @param <E>   What one slot shows.
     */
    private record Loaded<E>(List<E> items, int page, int pages) {}

    /**
     * Reads one page. Off the server thread.
     *
     * @param player    The viewer.
     * @param requested The page asked for.
     * @return What to draw.
     */
    private Loaded<T> read(Player player, int requested) {
        int perPage = Math.max(1, definition.pageSlots().size());
        try {
            int total = totalItems(player);
            if (total >= 0) {
                // The database pages: only the entries actually shown are read.
                int pages = Math.max(1, (int) Math.ceil(total / (double) perPage));
                int shown = Math.min(Math.max(1, requested), pages);
                List<T> loaded = loadPage(player, shown, perPage);
                return new Loaded<>(loaded == null ? List.of() : loaded, shown, pages);
            }
            List<T> all = items(player);
            Pagination<T> pagination = new Pagination<>(all == null ? List.of() : all, perPage);
            int pages = pagination.getMaxPage();
            int shown = Math.min(Math.max(1, requested), pages);
            return new Loaded<>(pagination.getPage(shown), shown, pages);
        } catch (Throwable t) {
            return new Loaded<>(List.of(), 1, 1);
        }
    }

    /**
     * Reads the list again into the screen already open, without reopening it.
     *
     * <p>What a live list needs: the auction house showed a new listing appearing while the player
     * watched, and losing that was not the point of moving it onto this class. Unlike
     * {@link #refresh()} nothing is closed and nothing is opened, so it can run on a timer without
     * fighting the player.</p>
     *
     * <p>The page number in the title is fixed when the screen opens, so a list that grows past a
     * page boundary shows the new count only after a page change. The screens this replaced had
     * the same limitation.</p>
     */
    public void reload() {
        if (viewer == null || inventory == null) return;
        if (!inventory.equals(viewer.getOpenInventory().getTopInventory())) return;
        final Player player = viewer;
        final int requested = page;
        scheduler.runAsyncTask(() -> {
            Loaded<T> loaded = read(player, requested);
            scheduler.runEntityTask(player, () -> {
                if (!player.isOnline() || inventory == null) return;
                if (!inventory.equals(player.getOpenInventory().getTopInventory())) return;
                this.maxPage = loaded.pages();
                this.page = loaded.page();
                this.pageItems = loaded.items();
                blink.clear();
                ticksSinceRefresh = 0;
                draw();
            });
        });
    }

    /**
     * Fills the fields for one page and creates the inventory. On the viewer's thread.
     *
     * @param items   The entries of the page.
     * @param shown   The page being shown.
     * @param pages   How many pages there are.
     */
    private void build(List<T> items, int shown, int pages) {
        this.maxPage = pages;
        this.page = shown;
        this.pageItems = items;

        String[] extra = titlePlaceholders();
        String[] placeholders = new String[extra.length + 4];
        placeholders[0] = "page";
        placeholders[1] = String.valueOf(this.page);
        placeholders[2] = "max";
        placeholders[3] = String.valueOf(maxPage);
        System.arraycopy(extra, 0, placeholders, 4, extra.length);

        this.inventory = Bukkit.createInventory(this, definition.getRows() * 9,
                lang.getComponent(titleKey(), placeholders));

        blink.clear();
        ticksSinceRefresh = 0;
        draw();
    }

    /**
     * Moves to another page, keeping the screen open.
     *
     * <p>The title carries the page number, so a new inventory is opened rather than the current
     * one being refilled.</p>
     *
     * @param page The page to show.
     */
    public void goTo(int page) {
        if (viewer == null) return;
        stopBlinking();
        open(viewer, page);
    }

    /**
     * Reads the list again and redraws the page being shown. What to call after an action that
     * changed the list — a sanction lifted, an item bought.
     *
     * <p>A refresh usually runs a few ticks after the action, to let the command finish writing.
     * If the viewer has closed the screen in the meantime, nothing happens: reopening a menu
     * somebody just walked away from is not a refresh, it is a menu that will not go away.</p>
     */
    public void refresh() {
        if (viewer == null || inventory == null) return;
        if (!inventory.equals(viewer.getOpenInventory().getTopInventory())) return;
        goTo(page);
    }

    /** Fills the inventory with the current page. */
    protected void draw() {
        boolean on = blinkState[0];
        List<Integer> slots = definition.pageSlots();

        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            if (i >= pageItems.size()) {
                inventory.setItem(slot, null);
                continue;
            }
            T item = pageItems.get(i);
            if (itemsBlink() && itemCacheTicks() > 0) {
                inventory.setItem(slot, blink.get(slot, on, () -> render(item, viewer, on)));
            } else {
                inventory.setItem(slot, render(item, viewer, on));
            }
        }

        decorate(inventory, viewer, on);
        drawBar(on);
    }

    /**
     * What the blink task redraws. The entries stay as they are unless {@link #itemsBlink()} says
     * otherwise, so a page of heads is built once and not forty-five times a second.
     */
    private void blinkTick() {
        if (itemsBlink()) {
            long lifetime = itemCacheTicks();
            ticksSinceRefresh += BLINK_PERIOD;
            if (lifetime > 0 && ticksSinceRefresh >= lifetime) {
                ticksSinceRefresh = 0;
                blink.clear();
            }
            draw();
            return;
        }
        decorate(inventory, viewer, blinkState[0]);
        drawBar(blinkState[0]);
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
        long every = refreshTicks();
        if (every > 0) {
            refreshTask = scheduler.runEntityTaskTimer(viewer, this::reload, every, every);
        }
        blinkTask = scheduler.runEntityTaskTimer(viewer, () -> {
            if (viewer == null || !viewer.isOnline()) {
                stopBlinking();
                return;
            }
            blinkState[0] = !blinkState[0];
            blinkTick();
        }, BLINK_PERIOD, BLINK_PERIOD);
    }

    /** Stops the blink task. Called on close, and when the viewer disconnects. */
    public void stopBlinking() {
        if (blinkTask != null) {
            scheduler.cancelTask(blinkTask);
            blinkTask = null;
        }
        if (refreshTask != null) {
            scheduler.cancelTask(refreshTask);
            refreshTask = null;
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
