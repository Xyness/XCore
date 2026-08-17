package fr.xyness.XCore.Network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Sync.SyncMessage;

/**
 * Who else is up, and where each player is. Each server announces itself on a timer, and its
 * players as they come and go.
 *
 * <p>Everything is in memory and expires on its own: a server that stops talking disappears from
 * the list, and so do the players it was holding.</p>
 */
public class NetworkRegistry {

    /** The channel servers talk to each other on. */
    public static final String CHANNEL = "xcore_net";

    private static final String HEARTBEAT = "HEARTBEAT";
    private static final String PLAYER_JOIN = "PLAYER_JOIN";
    private static final String PLAYER_QUIT = "PLAYER_QUIT";
    private static final String GOODBYE = "GOODBYE";

    private final XCore main;
    private final String selfName;

    /** The last heartbeat of each server, ours included. */
    private final Map<String, ServerInfo> servers = new ConcurrentHashMap<>();

    /** Where each player was last seen, by UUID. */
    private final Map<UUID, String> locations = new ConcurrentHashMap<>();

    private Object task;
    private volatile long heartbeatSeconds = 10;

    /**
     * @param main The plugin instance.
     */
    public NetworkRegistry(XCore main) {
        this.main = main;
        this.selfName = main.getServerName();
    }

    /**
     * Registers the channel and starts announcing this server.
     *
     * <p>Does nothing when cross-server sync is off: there is nobody to talk to.</p>
     */
    public void start() {
        if (main.getSyncManager() == null || !main.getSyncManager().isRunning()) {
            // Still register ourselves, so the accessors answer sensibly on a single server.
            servers.put(selfName, snapshot());
            return;
        }
        this.heartbeatSeconds = Math.max(5, main.getConfig().getLong("cross-server.heartbeat-seconds", 10));

        main.getSyncManager().registerChannel(CHANNEL, this::onMessage);
        servers.put(selfName, snapshot());

        long ticks = heartbeatSeconds * 20L;
        task = main.schedulerAdapter().runAsyncTaskTimer(this::beat, 40L, ticks);
    }

    /** Says goodbye and stops announcing. */
    public void stop() {
        if (task != null) {
            main.schedulerAdapter().cancelTask(task);
            task = null;
        }
        if (main.getSyncManager() != null && main.getSyncManager().isRunning()) {
            main.getSyncManager().publish(CHANNEL, new SyncMessage(GOODBYE, selfName));
        }
    }

    /** @return Every server heard from recently, including this one. */
    public Collection<ServerInfo> servers() {
        forget();
        return servers.values();
    }

    /**
     * @param name The server name.
     * @return What that server last said, or empty when it is not in the network.
     */
    public Optional<ServerInfo> server(String name) {
        forget();
        return Optional.ofNullable(servers.get(name));
    }

    /** @return This server's name. */
    public String selfName() {
        return selfName;
    }

    /** @return How many players are connected across the whole network. */
    public int totalOnline() {
        forget();
        int total = 0;
        for (ServerInfo info : servers.values()) total += info.online();
        return total;
    }

    /**
     * Finds where a player is.
     *
     * @param uuid The player.
     * @return The server's name, empty when nobody has announced them.
     */
    public Optional<String> locate(UUID uuid) {
        if (Bukkit.getPlayer(uuid) != null) return Optional.of(selfName);
        return Optional.ofNullable(locations.get(uuid));
    }

    /**
     * @param uuid The player.
     * @return Whether they are connected to any server of the network.
     */
    public boolean isOnlineSomewhere(UUID uuid) {
        return locate(uuid).isPresent();
    }

    /** @return The players known to be connected elsewhere. */
    public List<UUID> remotePlayers() {
        return new ArrayList<>(locations.keySet());
    }

    /**
     * Sends a message to one server rather than to all of them.
     *
     * @param serverName The target server, as it names itself.
     * @param channel    The channel the addon registered.
     * @param message    What to send.
     */
    public void send(String serverName, String channel, SyncMessage message) {
        if (main.getSyncManager() == null) return;
        main.getSyncManager().publish(channel, message, serverName);
    }

    /**
     * Announces a player who has just arrived here.
     *
     * @param player The player.
     */
    public void announceJoin(Player player) {
        locations.put(player.getUniqueId(), selfName);
        if (main.getSyncManager() == null || !main.getSyncManager().isRunning()) return;
        main.getSyncManager().publish(CHANNEL,
                new SyncMessage(PLAYER_JOIN, player.getUniqueId().toString(), selfName));
    }

    /**
     * Announces a player who has just left.
     *
     * @param player The player.
     */
    public void announceQuit(Player player) {
        locations.remove(player.getUniqueId());
        if (main.getSyncManager() == null || !main.getSyncManager().isRunning()) return;
        main.getSyncManager().publish(CHANNEL,
                new SyncMessage(PLAYER_QUIT, player.getUniqueId().toString(), selfName));
    }

    private void beat() {
        ServerInfo self = snapshot();
        servers.put(selfName, self);

        JsonObject payload = new JsonObject();
        payload.addProperty("online", self.online());
        payload.addProperty("max", self.maximum());
        payload.addProperty("tps", Math.round(self.tps() * 100) / 100.0);
        payload.addProperty("version", self.version());
        main.getSyncManager().publish(CHANNEL, new SyncMessage(HEARTBEAT, selfName, payload.toString()));
    }

    private ServerInfo snapshot() {
        double tps = 20.0;
        try {
            double[] recent = Bukkit.getTPS();
            if (recent.length > 0) tps = Math.min(20.0, recent[0]);
        } catch (Throwable ignored) {
            // Some forks do not expose it; twenty is a fine thing to claim in that case.
        }
        return new ServerInfo(selfName, Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(),
                tps, Bukkit.getVersion(), System.currentTimeMillis(), true);
    }

    private void onMessage(SyncMessage message) {
        switch (message.action()) {
            case HEARTBEAT -> {
                String name = message.key();
                if (name == null || name.isBlank() || name.equals(selfName)) return;
                try {
                    JsonObject json = JsonParser.parseString(message.payload()).getAsJsonObject();
                    servers.put(name, new ServerInfo(name,
                            json.has("online") ? json.get("online").getAsInt() : 0,
                            json.has("max") ? json.get("max").getAsInt() : 0,
                            json.has("tps") ? json.get("tps").getAsDouble() : 20.0,
                            json.has("version") ? json.get("version").getAsString() : "",
                            System.currentTimeMillis(), false));
                } catch (Exception ignored) {
                    // A heartbeat we cannot read tells us the server is alive and nothing else.
                    servers.put(name, new ServerInfo(name, 0, 0, 20.0, "", System.currentTimeMillis(), false));
                }
            }
            case PLAYER_JOIN -> {
                UUID uuid = parse(message.key());
                if (uuid != null) locations.put(uuid, message.payload());
            }
            case PLAYER_QUIT -> {
                UUID uuid = parse(message.key());
                // Only forget the player if they are still recorded on the server that just lost
                // them: on a proxy switch the arrival often lands before the departure.
                if (uuid != null) locations.remove(uuid, message.payload());
            }
            case GOODBYE -> {
                servers.remove(message.key());
                locations.values().removeIf(server -> server.equals(message.key()));
            }
            default -> {}
        }
    }

    /** Drops servers that stopped talking, and the players they were holding. */
    private void forget() {
        long stale = heartbeatSeconds * 3_000L;
        List<String> gone = new ArrayList<>();
        for (ServerInfo info : servers.values()) {
            if (info.isStale(stale)) gone.add(info.name());
        }
        for (String name : gone) {
            servers.remove(name);
            locations.values().removeIf(server -> server.equals(name));
        }
    }

    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
