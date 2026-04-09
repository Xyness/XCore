package fr.xyness.XCore.Sync;

/**
 * Immutable message transmitted through the sync system.
 *
 * @param action  The action identifier (e.g. {@code "UPDATE"}, {@code "REMOVE"}, {@code "RELOAD"}).
 * @param key     The affected entity key (e.g. a UUID, a sanction ID, etc.).
 * @param payload Optional extra data (JSON string, serialized object, etc.). May be empty.
 */
public record SyncMessage(String action, String key, String payload) {

    /**
     * Creates a SyncMessage with no payload.
     *
     * @param action The action identifier.
     * @param key    The affected entity key.
     */
    public SyncMessage(String action, String key) {
        this(action, key, "");
    }
}
