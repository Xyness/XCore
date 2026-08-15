package fr.xyness.XCore.Sync;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.xyness.XCore.API.DatabaseType;
import redis.clients.jedis.JedisPool;

/**
 * Centralized cross-server sync manager.
 * <p>
 * Addons register named channels with listeners. The manager picks a single
 * transport (Redis Pub/Sub or database polling) based on Redis availability,
 * and multiplexes all channels over that transport.
 * </p>
 * <p>
 * Message wire format: a JSON object {@code {"s":serverId,"c":channel,"a":action,"k":key,"p":payload}}.
 * Values are escaped by the encoder, so a key or payload containing the delimiter of the previous
 * pipe-separated format no longer shifts the whole message. Only JSON is accepted on read, so a cluster can be restarted one server at a time.
 * </p>
 */
public class SyncManager {

    private final String serverId;
    private final JedisPool jedisPool;
    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final Executor executor;
    private final int pollIntervalTicks;
    private final int retentionSeconds;
    private final Map<String, SyncChannel> channels = new ConcurrentHashMap<>();

    private volatile SyncTransport transport;
    private final LogCallback logDebug;
    private final LogCallback logWarning;
    private final LogCallback logError;

    /**
     * Functional interface for log callbacks.
     */
    @FunctionalInterface
    public interface LogCallback {
        void log(String message);
    }

    /**
     * Internal transport interface. Implementations handle the actual message passing.
     */
    interface SyncTransport {
        void start();
        void stop();
        void publish(String formattedMessage);
    }

    /**
     * Callback used by transports to deliver a received message to the manager.
     */
    @FunctionalInterface
    interface TransportCallback {
        void onRawMessage(String rawMessage);
    }

    /**
     * Creates a new SyncManager.
     *
     * @param jedisPool          The Redis pool, or {@code null} if Redis is not available.
     * @param dataSource         The database DataSource for DB-based sync fallback.
     * @param databaseType       The database type (for SQL dialect).
     * @param executor           Async executor for message handling.
     * @param pollIntervalTicks  Poll interval in ticks for database transport (20 ticks = 1 second).
     * @param retentionSeconds   How long to keep DB sync rows before cleanup.
     * @param serverName         Stable identifier for this server ({@code server-name}).
     * @param logDebug           Debug log callback.
     * @param logWarning         Warning log callback.
     * @param logError           Error log callback.
     */
    public SyncManager(JedisPool jedisPool, DataSource dataSource, DatabaseType databaseType,
                       Executor executor, int pollIntervalTicks, int retentionSeconds,
                       String serverName,
                       LogCallback logDebug, LogCallback logWarning, LogCallback logError) {
        // Derived from server-name so the identity survives a restart and matches the
        // one already used for per-server column suffixes. A random id per boot meant a server could
        // not recognise its own pending messages after a restart.
        this.serverId = (serverName == null || serverName.isBlank())
            ? UUID.randomUUID().toString().substring(0, 8)
            : serverName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        this.jedisPool = jedisPool;
        this.dataSource = dataSource;
        this.databaseType = databaseType;
        this.executor = executor;
        this.pollIntervalTicks = pollIntervalTicks;
        this.retentionSeconds = retentionSeconds;
        this.logDebug = logDebug != null ? logDebug : msg -> {};
        this.logWarning = logWarning != null ? logWarning : msg -> {};
        this.logError = logError != null ? logError : msg -> {};
    }

    /**
     * Registers a named sync channel with a listener.
     *
     * @param name     The channel name (must be unique).
     * @param listener The listener that handles incoming messages.
     * @return The created {@link SyncChannel}.
     * @throws IllegalStateException if a channel with the same name already exists.
     */
    public SyncChannel registerChannel(String name, SyncListener listener) {
        if (channels.containsKey(name)) {
            throw new IllegalStateException("Sync channel '" + name + "' is already registered.");
        }
        SyncChannel channel = new SyncChannel(name, listener);
        channels.put(name, channel);
        logDebug.log("Sync channel '" + name + "' registered.");
        return channel;
    }

    /**
     * Publishes a message to a named channel. The message is sent to all other servers.
     *
     * @param channelName The target channel name.
     * @param message     The message to publish.
     */
    public void publish(String channelName, SyncMessage message) {
        if (transport == null) return;
        if (!channels.containsKey(channelName)) {
            logWarning.log("Attempted to publish to unregistered channel '" + channelName + "'.");
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("s", serverId);
        json.addProperty("c", channelName);
        json.addProperty("a", message.action());
        json.addProperty("k", message.key() == null ? "" : message.key());
        json.addProperty("p", message.payload() == null ? "" : message.payload());
        String formatted = json.toString();
        executor.execute(() -> {
            try {
                transport.publish(formatted);
            } catch (Exception e) {
                logError.log("Failed to publish sync message on '" + channelName + "' : " + e.getMessage());
            }
        });
    }

    /**
     * Starts the sync transport. Picks Redis if available, otherwise database polling.
     * If neither Redis nor a non-SQLite database is available, sync is disabled.
     */
    public void start() {
        TransportCallback callback = this::handleRawMessage;

        if (jedisPool != null) {
            transport = new RedisSyncTransport(jedisPool, callback, logDebug, logWarning);
            logDebug.log("Sync started with Redis transport (id=" + serverId + ").");
        } else if (dataSource != null && databaseType != DatabaseType.SQLITE) {
            transport = new DatabaseSyncTransport(dataSource, databaseType, callback, executor,
                    pollIntervalTicks, retentionSeconds, logDebug, logError);
            logDebug.log("Sync started with Database transport (id=" + serverId + ", poll=" + (pollIntervalTicks / 20) + "s).");
        } else {
            logWarning.log("No sync transport available (Redis not configured, SQLite not supported for DB sync).");
            return;
        }

        transport.start();
    }

    /**
     * Stops the sync transport and clears all channels.
     */
    public void stop() {
        if (transport != null) {
            transport.stop();
            transport = null;
        }
        channels.clear();
    }

    /** @return The unique server identifier for this instance. */
    public String getServerId() {
        return serverId;
    }

    /** @return {@code true} if a transport is active and running. */
    public boolean isRunning() {
        return transport != null;
    }

    /**
     * Handles a raw message from the transport layer.
     * Accepts the JSON format emitted by {@link #publish}; anything else is ignored.
     */
    private void handleRawMessage(String rawMessage) {
        try {
            if (rawMessage == null || rawMessage.isBlank()) return;

            String trimmed = rawMessage.trim();
            if (trimmed.charAt(0) != '{') return;

            JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
            String sourceServerId = json.has("s") ? json.get("s").getAsString() : "";
            String channelName = json.has("c") ? json.get("c").getAsString() : "";
            String action = json.has("a") ? json.get("a").getAsString() : "";
            String key = json.has("k") ? json.get("k").getAsString() : "";
            String payload = json.has("p") ? json.get("p").getAsString() : "";

            if (sourceServerId.equals(serverId)) return; // Ignore own messages

            SyncChannel channel = channels.get(channelName);
            if (channel == null) return; // Not subscribed to this channel

            SyncMessage message = new SyncMessage(action, key, payload);
            executor.execute(() -> {
                try {
                    channel.listener().onMessage(message);
                    logDebug.log("Sync received on '" + channelName + "' : " + action + " for " + key + ".");
                } catch (Exception e) {
                    logError.log("Error handling sync message on '" + channelName + "' : " + e.getMessage());
                }
            });
        } catch (Exception e) {
            logError.log("Failed to parse sync message : " + e.getMessage());
        }
    }
}
