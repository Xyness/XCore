package fr.xyness.XCore.Network;

/**
 * What one server in the network last said about itself.
 *
 * @param name      The server's name, as configured in {@code server-name}.
 * @param online    How many players were connected.
 * @param maximum   How many it accepts.
 * @param tps       Its last measured tick rate.
 * @param version   The server software it runs.
 * @param heardAt   When the heartbeat arrived, in milliseconds.
 * @param local     Whether this is the server we are running on.
 */
public record ServerInfo(String name, int online, int maximum, double tps,
                         String version, long heardAt, boolean local) {

    /**
     * @param staleAfterMillis How long a server may stay quiet before it counts as gone.
     * @return Whether the server has been quiet for too long.
     */
    public boolean isStale(long staleAfterMillis) {
        return !local && System.currentTimeMillis() - heardAt > staleAfterMillis;
    }
}
