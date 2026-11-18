package fr.xyness.XCore.Sync;

/**
 * A registered sync channel: its name, the listener of its messages, and the version of the format
 * those messages are written in.
 *
 * @param name     The channel name (e.g. {@code "players"}, {@code "sanctions"}, {@code "auctions"}).
 * @param listener The listener that handles incoming messages on this channel.
 * @param protocol The version of the messages on this channel. Only messages of the same version
 *                 reach the listener; see {@link SyncManager#registerChannel(String, int, SyncListener)}.
 */
public record SyncChannel(String name, SyncListener listener, int protocol) {

    /**
     * A channel at protocol 0: the version of every channel registered before versions existed, and
     * of every message an XCore 1.x server sends.
     *
     * @param name     The channel name.
     * @param listener The listener that handles incoming messages on this channel.
     */
    public SyncChannel(String name, SyncListener listener) {
        this(name, listener, 0);
    }
}
