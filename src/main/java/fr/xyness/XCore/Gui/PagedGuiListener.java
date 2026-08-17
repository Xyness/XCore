package fr.xyness.XCore.Gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Sends clicks to the {@link PagedGui} that owns the screen, and stops its blink task when the
 * screen goes away.
 *
 * <p>Registered by XCore itself, so a screen built on {@code PagedGui} works without the addon
 * registering anything.</p>
 */
public class PagedGuiListener implements Listener {

    /**
     * Routes a click to the screen it happened in.
     *
     * @param event The click.
     */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof PagedGui<?> gui) {
            gui.handleClick(event);
        }
    }

    /**
     * Refuses drags across a managed screen, which would otherwise move its items.
     *
     * @param event The drag.
     */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PagedGui<?>) {
            event.setCancelled(true);
        }
    }

    /**
     * Stops the blink task once the screen is closed.
     *
     * @param event The close.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof PagedGui<?> gui) {
            gui.stopBlinking();
        }
    }

    /**
     * Same on disconnect: a close event is not guaranteed there, and the task would outlive the
     * player.
     *
     * @param event The quit.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof PagedGui<?> gui) {
            gui.stopBlinking();
        }
    }
}
