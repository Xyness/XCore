package fr.xyness.XCore.Gui;

import java.util.Optional;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import fr.xyness.XCore.Utils.SchedulerAdapter;

/**
 * Central Bukkit listener that dispatches inventory click and close events
 * to the appropriate {@link GuiClickHandler} via the {@link GuiManager}.
 * <p>
 * On click, the listener finds the active session for the player, resolves
 * the handler for the holder type, and delegates. On close, it stops any
 * blink animation and removes the session.
 * </p>
 */
public class GuiListener implements Listener {

    /** The GUI manager that tracks sessions and handlers. */
    private final GuiManager guiManager;

    /** The scheduler adapter used to cancel blink tasks. */
    private final SchedulerAdapter scheduler;

    /**
     * Creates a new GUI listener.
     *
     * @param guiManager The GUI manager instance.
     * @param scheduler  The scheduler adapter for task cancellation.
     */
    public GuiListener(GuiManager guiManager, SchedulerAdapter scheduler) {
        this.guiManager = guiManager;
        this.scheduler = scheduler;
    }

    /**
     * Handles inventory click events for managed GUIs.
     * <p>
     * Cancels the event, finds the active session and registered handler,
     * then dispatches the click to the handler.
     * </p>
     *
     * @param event The inventory click event.
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Optional<GuiSession> optSession = guiManager.getSession(player);
        if (optSession.isEmpty()) return;

        GuiSession session = optSession.get();
        Inventory clickedInventory = event.getClickedInventory();
        Inventory topInventory = event.getView().getTopInventory();

        // Only handle clicks in the top (GUI) inventory
        if (clickedInventory == null || !clickedInventory.equals(topInventory)) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        InventoryHolder holder = session.getHolder();
        if (holder == null) return;

        GuiClickHandler handler = guiManager.getHandler(holder.getClass());
        if (handler != null) {
            handler.handle(event, player, session);
        }
    }

    /**
     * Handles inventory close events for managed GUIs.
     * <p>
     * Stops the blink controller and removes the session from the manager.
     * </p>
     *
     * @param event The inventory close event.
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        Optional<GuiSession> optSession = guiManager.getSession(player);
        if (optSession.isEmpty()) return;

        GuiSession session = optSession.get();
        if (session.getBlinkController() != null) {
            session.getBlinkController().stop(scheduler);
        }
        guiManager.close(player, scheduler);
    }
}
