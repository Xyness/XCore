package fr.xyness.XCore.Gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Functional interface for handling GUI click events.
 * <p>
 * Implementations are registered per {@link org.bukkit.inventory.InventoryHolder}
 * class via {@link GuiManager#registerHandler(Class, GuiClickHandler)} and dispatched
 * by {@link GuiListener} when the player clicks inside a managed GUI.
 * </p>
 */
@FunctionalInterface
public interface GuiClickHandler {

    /**
     * Handles a GUI click event.
     *
     * @param event   The Bukkit inventory click event.
     * @param player  The player who clicked.
     * @param session The active GUI session for the player.
     */
    void handle(InventoryClickEvent event, Player player, GuiSession session);
}
