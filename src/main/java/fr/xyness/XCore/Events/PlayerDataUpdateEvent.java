package fr.xyness.XCore.Events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import fr.xyness.XCore.Models.PlayerData;

/**
 * Fired asynchronously when a player's cached data is updated.
 * <p>
 * Provides access to both the previous and new data snapshots for comparison.
 * </p>
 * <b>Warning:</b> This event fires on an async thread. Do not call Bukkit API methods
 * that require the main thread from listeners.
 */
public class PlayerDataUpdateEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final PlayerData oldData;
    private final PlayerData newData;

    /**
     * Creates a new PlayerDataUpdateEvent.
     *
     * @param oldData The previous player data snapshot.
     * @param newData The new player data snapshot.
     */
    public PlayerDataUpdateEvent(PlayerData oldData, PlayerData newData) {
        super(true);
        this.oldData = oldData;
        this.newData = newData;
    }

    /** @return The previous player data. */
    public PlayerData getOldData() { return oldData; }

    /** @return The new player data. */
    public PlayerData getNewData() { return newData; }

    @Override
    public HandlerList getHandlers() { return HANDLER_LIST; }

    public static HandlerList getHandlerList() { return HANDLER_LIST; }

}
