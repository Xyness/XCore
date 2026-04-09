package fr.xyness.XCore.Events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import fr.xyness.XCore.Models.PlayerData;

/**
 * Fired asynchronously after a player's data has been loaded or refreshed from the database.
 * <p>
 * Listeners should check {@link #isNewPlayer()} to determine if this is a first-time load
 * or a refresh of existing data.
 * </p>
 * <b>Warning:</b> This event fires on an async thread. Do not call Bukkit API methods
 * that require the main thread from listeners.
 */
public class PlayerDataLoadEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final PlayerData playerData;
    private final boolean newPlayer;

    /**
     * Creates a new PlayerDataLoadEvent.
     *
     * @param playerData The loaded player data.
     * @param newPlayer  {@code true} if this player was not previously in the database.
     */
    public PlayerDataLoadEvent(PlayerData playerData, boolean newPlayer) {
        super(true);
        this.playerData = playerData;
        this.newPlayer = newPlayer;
    }

    /** @return The loaded player data. */
    public PlayerData getPlayerData() { return playerData; }

    /** @return {@code true} if this is a first-time load (player was not in the database). */
    public boolean isNewPlayer() { return newPlayer; }

    @Override
    public HandlerList getHandlers() { return HANDLER_LIST; }

    public static HandlerList getHandlerList() { return HANDLER_LIST; }

}
