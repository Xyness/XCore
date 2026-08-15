package fr.xyness.XCore.Gui;

import org.bukkit.configuration.ConfigurationSection;

import fr.xyness.XCore.Gui.Actions.CloseInventoryAction;
import fr.xyness.XCore.Gui.Actions.CommandAction;
import fr.xyness.XCore.Gui.Actions.MessageAction;

/**
 * Factory for creating {@link GuiAction} instances from configuration data.
 * <p>
 * Supports both the modern section-based format (with {@code type}, {@code executor},
 * and {@code value} keys)
 * ({@code type:executor:value}).
 * </p>
 */
public class GuiActionFactory {

    /**
     * Creates a {@link GuiAction} from a configuration section.
     * <p>
     * The section must contain a {@code type} key. Supported types:
     * <ul>
     *   <li>{@code close} / {@code close_inventory} — closes the player's inventory</li>
     *   <li>{@code msg} / {@code message} — sends a MiniMessage-formatted message</li>
     *   <li>{@code cmd} / {@code command} — executes a command as player or console</li>
     * </ul>
     * </p>
     *
     * @param section The configuration section containing action data.
     * @return The created {@link GuiAction}, or {@code null} if the type is unknown.
     */
    public static GuiAction fromSection(ConfigurationSection section) {
        String type = section.getString("type", "");
        return switch (type.toLowerCase()) {
            case "close", "close_inventory" -> new CloseInventoryAction();
            case "msg", "message" -> new MessageAction(section.getString("value", ""));
            case "cmd", "command" -> new CommandAction(
                section.getString("executor", "player"),
                section.getString("value", ""));
            default -> null;
        };
    }

}
