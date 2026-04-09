package fr.xyness.XCore.Gui.Actions;

import org.bukkit.entity.Player;

import fr.xyness.XCore.Gui.GuiAction;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * A {@link GuiAction} that sends a MiniMessage-formatted message to the clicking player.
 * <p>
 * The message string supports the {@code %player%} placeholder, which is replaced
 * with the player's name before parsing. MiniMessage tags such as {@code <red>} or
 * {@code <bold>} are fully supported.
 * </p>
 */
public class MessageAction implements GuiAction {

    /** Shared MiniMessage parser instance. */
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The raw message template (may contain {@code %player%} and MiniMessage tags). */
    private final String message;

    /**
     * Creates a new message action.
     *
     * @param message The message string, optionally containing {@code %player%} and MiniMessage tags.
     */
    public MessageAction(String message) {
        this.message = message;
    }

    /**
     * Sends the formatted message to the player, replacing {@code %player%} with their name.
     *
     * @param player The player who triggered the action.
     */
    @Override
    public void execute(Player player) {
        player.sendMessage(MINI.deserialize(message.replace("%player%", player.getName())));
    }
}
