package fr.xyness.XCore.Gui;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import fr.xyness.XCore.Utils.SchedulerAdapter;

/**
 * Central manager for GUI sessions and click handler registration.
 * <p>
 * Tracks open GUI sessions per player and dispatches click events to
 * registered {@link GuiClickHandler} instances based on the
 * {@link InventoryHolder} class of the open inventory.
 * </p>
 */
public class GuiManager {

    /** Active sessions keyed by player UUID. */
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();

    /** Click handlers keyed by InventoryHolder class. */
    private final Map<Class<? extends InventoryHolder>, GuiClickHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Opens a GUI for a player using the given builder and scheduler.
     * <p>
     * Creates a new {@link GuiSession}, sets up blink animation if the builder
     * has blink slots configured, and opens the inventory for the player.
     * </p>
     *
     * @param player    The player to open the GUI for.
     * @param builder   The GUI builder containing the inventory configuration.
     * @param scheduler The scheduler adapter for blink task scheduling.
     * @return The created GUI session.
     */
    public GuiSession open(Player player, GuiBuilder builder, SchedulerAdapter scheduler) {
        close(player, scheduler);

        Inventory inv = builder.build();
        InventoryHolder holder = builder.getHolder();
        GuiSession session = new GuiSession(player, inv, holder);
        session.setPage(builder.getPage());

        Map<Integer, ItemStack[]> blinkSlots = builder.getBlinkSlots();
        if (!blinkSlots.isEmpty()) {
            BlinkController blink = new BlinkController();
            session.setBlinkController(blink);
            blink.start(scheduler, () -> {
                boolean state = blink.getState();
                for (Map.Entry<Integer, ItemStack[]> entry : blinkSlots.entrySet()) {
                    inv.setItem(entry.getKey(), state ? entry.getValue()[0] : entry.getValue()[1]);
                }
            }, 10L);
        }

        sessions.put(player.getUniqueId(), session);
        player.openInventory(inv);
        return session;
    }

    /**
     * Closes the GUI session for a player, stopping any blink animation.
     *
     * @param player The player whose session should be closed.
     */
    public void close(Player player) {
        close(player, null);
    }

    /**
     * Closes the GUI session for a player, stopping any blink animation.
     *
     * @param player    The player whose session should be closed.
     * @param scheduler The scheduler adapter for cancelling blink tasks, or {@code null}.
     */
    public void close(Player player, SchedulerAdapter scheduler) {
        GuiSession session = sessions.remove(player.getUniqueId());
        if (session != null && session.getBlinkController() != null && scheduler != null) {
            session.getBlinkController().stop(scheduler);
        }
    }

    /**
     * Returns the active GUI session for a player, if one exists.
     *
     * @param player The player to look up.
     * @return An optional containing the session, or empty if none is active.
     */
    public Optional<GuiSession> getSession(Player player) {
        return Optional.ofNullable(sessions.get(player.getUniqueId()));
    }

    /**
     * Registers a click handler for a specific {@link InventoryHolder} class.
     *
     * @param holderClass The holder class to associate the handler with.
     * @param handler     The click handler to register.
     */
    public void registerHandler(Class<? extends InventoryHolder> holderClass, GuiClickHandler handler) {
        handlers.put(holderClass, handler);
    }

    /**
     * Returns the click handler registered for a specific holder class.
     *
     * @param holderClass The holder class to look up.
     * @return The registered handler, or {@code null} if none.
     */
    public GuiClickHandler getHandler(Class<? extends InventoryHolder> holderClass) {
        return handlers.get(holderClass);
    }
}
