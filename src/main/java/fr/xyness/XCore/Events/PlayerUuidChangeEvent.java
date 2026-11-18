package fr.xyness.XCore.Events;

import java.util.UUID;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when XCore has recognised a returning player under a new UUID and moved their data onto it.
 *
 * <p>For plugins that only want to know. It is fired asynchronously, once XCore and every handler
 * registered through {@link fr.xyness.XCore.Identity.UuidChanges} are done, and nothing waits for
 * its listeners — the player's login is already going on. <b>Do not block in a listener</b>, and do
 * not move data here that the login needs: a listener that is still copying rows while the player
 * joins is the race this hook exists to remove. Work the login must wait for belongs in a handler,
 * {@code api().uuidChanges().register(plugin, handler)}.</p>
 *
 * <p>Like every asynchronous event, its listeners cannot touch the world: schedule that.</p>
 */
public class PlayerUuidChangeEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final UUID oldUuid;
    private final UUID newUuid;
    private final String name;
    private final String previousName;

    /**
     * @param oldUuid      The UUID the player's data was stored under.
     * @param newUuid      The UUID the player is connecting with.
     * @param name         The name the player is connecting with.
     * @param previousName The name stored with the old UUID; differs from {@code name} after a rename.
     */
    public PlayerUuidChangeEvent(UUID oldUuid, UUID newUuid, String name, String previousName) {
        super(true);
        this.oldUuid = oldUuid;
        this.newUuid = newUuid;
        this.name = name;
        this.previousName = previousName;
    }

    /** @return The UUID the player's data was stored under. */
    public UUID getOldUuid() { return oldUuid; }

    /** @return The UUID the player is connecting with, which their data now belongs to. */
    public UUID getNewUuid() { return newUuid; }

    /** @return The name the player is connecting with. */
    public String getName() { return name; }

    /** @return The name that was stored with the old UUID: not {@link #getName()} when they renamed. */
    public String getPreviousName() { return previousName; }

    @Override
    public HandlerList getHandlers() { return HANDLER_LIST; }

    public static HandlerList getHandlerList() { return HANDLER_LIST; }
}
