package fr.xyness.XCore.Sync;

/**
 * A registered sync channel with a name and its associated listener.
 *
 * @param name     The channel name (e.g. {@code "players"}, {@code "sanctions"}, {@code "auctions"}).
 * @param listener The listener that handles incoming messages on this channel.
 */
public record SyncChannel(String name, SyncListener listener) {
}
