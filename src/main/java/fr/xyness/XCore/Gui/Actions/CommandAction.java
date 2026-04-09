package fr.xyness.XCore.Gui.Actions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import fr.xyness.XCore.Gui.GuiAction;

/**
 * A {@link GuiAction} that dispatches a command as either the console or the clicking player.
 * <p>
 * The command string supports the {@code %player%} placeholder, which is replaced
 * with the executing player's name at runtime.
 * </p>
 */
public class CommandAction implements GuiAction {

    /**
     * Determines who executes the command.
     */
    public enum Executor {
        /** The server console. */
        CONSOLE,
        /** The clicking player. */
        PLAYER
    }

    /** Who will execute the command. */
    private final Executor executor;

    /** The command template (may contain {@code %player%}). */
    private final String command;

    /**
     * Creates a new command action.
     *
     * @param executor The executor identifier ({@code "console"} for console, anything else for player).
     * @param command  The command string, optionally containing {@code %player%}.
     */
    public CommandAction(String executor, String command) {
        this.executor = "console".equalsIgnoreCase(executor) ? Executor.CONSOLE : Executor.PLAYER;
        this.command = command;
    }

    /**
     * Executes the command, replacing {@code %player%} with the player's name.
     *
     * @param player The player who triggered the action.
     */
    @Override
    public void execute(Player player) {
        String cmd = command.replace("%player%", player.getName());
        if (executor == Executor.CONSOLE) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } else {
            player.performCommand(cmd);
        }
    }
}
