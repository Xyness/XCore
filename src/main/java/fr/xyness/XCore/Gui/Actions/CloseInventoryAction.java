package fr.xyness.XCore.Gui.Actions;

import org.bukkit.entity.Player;

import fr.xyness.XCore.Gui.GuiAction;

/**
 * A {@link GuiAction} that closes the player's currently open inventory.
 */
public class CloseInventoryAction implements GuiAction {

    /**
     * Closes the player's inventory.
     *
     * @param player The player whose inventory will be closed.
     */
    @Override
    public void execute(Player player) {
        player.closeInventory();
    }
}
