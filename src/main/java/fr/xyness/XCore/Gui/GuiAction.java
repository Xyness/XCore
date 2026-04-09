package fr.xyness.XCore.Gui;

import org.bukkit.entity.Player;

/**
 * Represents an action executed when a GUI item is clicked.
 * <p>
 * Implementations define a single {@link #execute(Player)} method
 * that is invoked with the clicking player as context.
 * </p>
 */
public interface GuiAction {

    /**
     * Executes this action for the given player.
     *
     * @param player The player who triggered the action.
     */
    void execute(Player player);
}
