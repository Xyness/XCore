package fr.xyness.XCore.Sync;

/**
 * Callback interface for receiving sync messages on a specific channel.
 * Implementations handle the message content (e.g. reload data from DB,
 * update local cache, etc.).
 */
@FunctionalInterface
public interface SyncListener {

    /**
     * Called when a sync message is received from another server.
     *
     * @param message The sync message. Never {@code null}.
     */
    void onMessage(SyncMessage message);
}
