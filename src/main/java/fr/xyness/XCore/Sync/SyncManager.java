package fr.xyness.XCore.Sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.gson.JsonArray;
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
 * Message wire format: a JSON object {@code {"s":serverId,"i":instance,"c":channel,"v":protocol,
 * "a":action,"k":key,"p":payload}}, plus {@code "t":target} for a message meant for one server.
 * Values are escaped by the encoder, so a key or payload containing the delimiter of the previous
 * pipe-separated format no longer shifts the whole message. Only JSON is accepted on read, so a cluster can be restarted one server at a time.
 * </p>
 * <p>
 * {@code "v"} is the protocol version of the channel the message was written for. A server reads
 * fields by name and ignores the ones it does not know, which is what lets an XCore 1.x server
 * take a message carrying it without noticing. A message without it comes from a server older than
 * versions, and counts as version 0.
 * </p>
 */
public class SyncManager {

    private final String serverId;

    /**
     * What this running server signs its messages with, besides its name.
     *
     * <p>A message whose sender is "us" is dropped, and "us" used to be the server name alone. Two
     * servers left on the default name therefore ignored everything the other one said, with no
     * warning anywhere. The name still addresses a message to one server; this tells our own from
     * a namesake's.</p>
     */
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private final JedisPool jedisPool;
    private final DataSource dataSource;
    private final DatabaseType databaseType;
    private final Executor executor;

    /**
     * Where batches are written to the transport: the database pool. The database transport opens a
     * connection for its INSERT, and on the work pool that connection was held by a thread every
     * other task of the installation waits for. A Redis publish is a connection too.
     */
    private final Executor publishExecutor;
    private final int pollIntervalTicks;
    private final int retentionSeconds;
    private final Map<String, SyncChannel> channels = new ConcurrentHashMap<>();

    /**
     * The protocol mismatches already reported, by channel and source server, with the version that
     * server was found speaking. One line per pair, not one per message: a server left on the old
     * version sends a few messages a second, and the console would hold nothing else.
     */
    private final Map<String, Integer> reportedMismatches = new ConcurrentHashMap<>();

    /** The most messages that travel together. */
    private static final int BATCH_LIMIT = 200;

    /** Messages waiting to leave, gathered so a burst costs one round trip instead of a thousand. */
    private final java.util.Queue<JsonObject> outgoing = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Sends whatever is queued, a few times a second. */
    private java.util.concurrent.ScheduledExecutorService flusher;

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
     * @param publishExecutor    Where outgoing batches are written to the transport: the database pool.
     * @param pollIntervalTicks  Poll interval in ticks for database transport (20 ticks = 1 second).
     * @param retentionSeconds   How long to keep DB sync rows before cleanup.
     * @param serverName         Stable identifier for this server ({@code server-name}).
     * @param logDebug           Debug log callback.
     * @param logWarning         Warning log callback.
     * @param logError           Error log callback.
     */
    public SyncManager(JedisPool jedisPool, DataSource dataSource, DatabaseType databaseType,
                       Executor executor, Executor publishExecutor, int pollIntervalTicks, int retentionSeconds,
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
        this.publishExecutor = publishExecutor != null ? publishExecutor : executor;
        this.pollIntervalTicks = pollIntervalTicks;
        this.retentionSeconds = retentionSeconds;
        this.logDebug = logDebug != null ? logDebug : msg -> {};
        this.logWarning = logWarning != null ? logWarning : msg -> {};
        this.logError = logError != null ? logError : msg -> {};
    }

    /**
     * Registers a named sync channel with a listener, at protocol version 0.
     *
     * <p>Version 0 is what every channel was before versions existed, and what an XCore 1.x server
     * sends: a channel registered this way keeps talking to those. Use
     * {@link #registerChannel(String, int, SyncListener)} the day its messages change.</p>
     *
     * @param name     The channel name (must be unique).
     * @param listener The listener that handles incoming messages.
     * @return The created {@link SyncChannel}.
     * @throws IllegalStateException if a channel with the same name already exists.
     */
    public SyncChannel registerChannel(String name, SyncListener listener) {
        return registerChannel(name, 0, listener);
    }

    /**
     * Registers a named sync channel whose messages are written in a given version of their format.
     *
     * <h2>Why</h2>
     * A network is updated one server at a time. While it is, servers on the new version and servers
     * on the old one read each other's messages, and when the meaning of an action, a key or a
     * payload changed between the two, each side acted on what the other never said. Every message
     * now carries the version of its channel, and a message of another version is not handed to the
     * listener: the console says which server sent it and which side is behind, once.
     *
     * <p>Raise the number whenever the messages of the channel change in a way an older listener
     * would misread — a renamed action, a key holding something else, a payload laid out
     * differently. Adding an action older servers ignore anyway does not need it. During the update,
     * the servers simply stop hearing the ones still on the other version, rather than misreading
     * them.</p>
     *
     * <pre>{@code
     * // Version 1: the payload of BAN became a JSON object.
     * core().getSyncManager().registerChannel("xbans", 1, message -> handle(message));
     * }</pre>
     *
     * @param name     The channel name (must be unique).
     * @param protocol The version of the channel's messages, 0 or more. Messages from an XCore 1.x
     *                 server, which carry none, count as version 0.
     * @param listener The listener that handles incoming messages.
     * @return The created {@link SyncChannel}.
     * @throws IllegalStateException    if a channel with the same name already exists.
     * @throws IllegalArgumentException if the version is negative.
     */
    public SyncChannel registerChannel(String name, int protocol, SyncListener listener) {
        if (protocol < 0) {
            throw new IllegalArgumentException("Sync channel '" + name + "': protocol version " + protocol + " is negative.");
        }
        SyncChannel channel = new SyncChannel(name, listener, protocol);
        if (channels.putIfAbsent(name, channel) != null) {
            throw new IllegalStateException("Sync channel '" + name + "' is already registered.");
        }
        logDebug.log("Sync channel '" + name + "' registered (protocol " + protocol + ").");
        return channel;
    }

    /**
     * Removes the channels whose listener was defined by the given class loader.
     *
     * <p>Called when a plugin built on XCore is disabled. Its channels used to stay behind: the
     * messages kept arriving at code that had stopped, and the same plugin started again could not
     * register its channel a second time. A listener written as a lambda or a method reference
     * belongs to the class loader of the class it was written in, which is the plugin's.</p>
     *
     * @param loader The class loader of the plugin going away.
     * @return The names of the channels removed.
     */
    public java.util.List<String> unregisterChannelsOf(ClassLoader loader) {
        java.util.List<String> removed = new java.util.ArrayList<>();
        if (loader == null) return removed;
        for (SyncChannel channel : channels.values()) {
            SyncListener listener = channel.listener();
            if (listener != null && listener.getClass().getClassLoader() == loader
                    && channels.remove(channel.name(), channel)) {
                removed.add(channel.name());
                logDebug.log("Sync channel '" + channel.name() + "' unregistered.");
            }
        }
        return removed;
    }

    /**
     * Publishes a message to a named channel. The message is sent to all other servers.
     *
     * @param channelName The target channel name.
     * @param message     The message to publish.
     */
    public void publish(String channelName, SyncMessage message) {
        publish(channelName, message, null);
    }

    /**
     * Publishes a message to one server rather than to all of them.
     *
     * @param channelName  The target channel name.
     * @param message      The message to publish.
     * @param targetServer The server that should act on it, or {@code null} to broadcast.
     */
    public void publish(String channelName, SyncMessage message, String targetServer) {
        if (transport == null) return;
        SyncChannel channel = channels.get(channelName);
        if (channel == null) {
            logWarning.log("Attempted to publish to unregistered channel '" + channelName + "'.");
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("s", serverId);
        json.addProperty("i", instanceId);
        json.addProperty("c", channelName);
        // Always written, 0 included: the absence of the field is what says "a server older than
        // versions", and the warning on the other side is worded after it.
        json.addProperty("v", channel.protocol());
        json.addProperty("a", message.action());
        json.addProperty("k", message.key() == null ? "" : message.key());
        json.addProperty("p", message.payload() == null ? "" : message.payload());
        if (targetServer != null && !targetServer.isBlank()) json.addProperty("t", targetServer);

        outgoing.add(json);
        // A purge or an import produces thousands of these in a row; letting them pile up for a
        // fraction of a second turns that into a handful of round trips instead of thousands.
        if (outgoing.size() >= BATCH_LIMIT) flushOutgoing();
    }

    /** Sends everything queued, as one message when there is more than one. */
    private void flushOutgoing() {
        if (transport == null || outgoing.isEmpty()) return;

        List<JsonObject> batch = new ArrayList<>();
        for (JsonObject json = outgoing.poll(); json != null && batch.size() < BATCH_LIMIT; json = outgoing.poll()) {
            batch.add(json);
        }
        if (batch.isEmpty()) return;

        String formatted;
        if (batch.size() == 1) {
            formatted = batch.get(0).toString();
        } else {
            JsonArray array = new JsonArray();
            batch.forEach(array::add);
            formatted = array.toString();
        }

        // The transport as it is now: the batch stop() flushes can be written after it cleared the field.
        SyncTransport target = transport;
        publishExecutor.execute(() -> {
            try {
                target.publish(formatted);
            } catch (Exception e) {
                logError.log("Failed to publish " + batch.size() + " sync message(s) : " + e.getMessage());
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

        flusher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "XCore-SyncFlush");
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(this::flushOutgoing, 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the sync transport and clears all channels.
     */
    public void stop() {
        // Whatever is still queued belongs on the wire, not in memory: the last thing published is
        // usually a shutdown notice other servers need.
        flushOutgoing();
        if (flusher != null) {
            flusher.shutdownNow();
            flusher = null;
        }
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
     * Accepts one object or an array of them; anything else is ignored.
     */
    private void handleRawMessage(String rawMessage) {
        try {
            if (rawMessage == null || rawMessage.isBlank()) return;

            String trimmed = rawMessage.trim();
            char first = trimmed.charAt(0);

            if (first == '[') {
                for (var element : JsonParser.parseString(trimmed).getAsJsonArray()) {
                    if (element.isJsonObject()) handleOne(element.getAsJsonObject());
                }
                return;
            }
            if (first != '{') return;
            handleOne(JsonParser.parseString(trimmed).getAsJsonObject());
        } catch (Exception e) {
            logError.log("Failed to parse sync message : " + e.getMessage());
        }
    }

    /** Delivers one decoded message to its channel. */
    private void handleOne(JsonObject json) {
        String sourceServerId = json.has("s") ? json.get("s").getAsString() : "";
        String channelName = json.has("c") ? json.get("c").getAsString() : "";
        String action = json.has("a") ? json.get("a").getAsString() : "";
        String key = json.has("k") ? json.get("k").getAsString() : "";
        String payload = json.has("p") ? json.get("p").getAsString() : "";
        String target = json.has("t") ? json.get("t").getAsString() : "";

        // Our own message. A sender from before the instance id carries none: for those the name
        // is all there is to go by, as it always was.
        String sourceInstance = json.has("i") ? json.get("i").getAsString() : "";
        if (sourceInstance.isEmpty() ? sourceServerId.equals(serverId) : sourceInstance.equals(instanceId)) return;
        if (!target.isEmpty() && !target.equals(serverId)) return;      // addressed to somebody else

        SyncChannel channel = channels.get(channelName);
        if (channel == null) return;                                    // not subscribed here

        // Written in the format this channel speaks here, or not delivered at all: during a rolling
        // update, a listener reading the other version's messages acted on what was never said.
        Integer version = protocolOf(json);
        if (version == null) {
            logDebug.log("Sync message on '" + channelName + "' from " + sourceServerId + " carries an unreadable protocol version; ignored.");
            return;
        }
        if (version != channel.protocol()) {
            reportMismatch(channelName, sourceServerId, version, channel.protocol(), !json.has("v"));
            return;
        }
        if (!reportedMismatches.isEmpty()) {
            // That server speaks our version again: if it ever goes back, it is worth saying again.
            reportedMismatches.remove(mismatchKey(channelName, sourceServerId));
        }

        SyncMessage message = new SyncMessage(action, key, payload);
        executor.execute(() -> {
            try {
                channel.listener().onMessage(message);
                logDebug.log("Sync received on '" + channelName + "' : " + action + " for " + key + ".");
            } catch (Exception e) {
                logError.log("Error handling sync message on '" + channelName + "' : " + e.getMessage());
            }
        });
    }

    /**
     * Reads the protocol version a message was written in.
     *
     * @param json The decoded message.
     * @return The version; 0 when the message has none (a server older than versions); {@code null}
     *         when the field is there but is not a number.
     */
    private static Integer protocolOf(JsonObject json) {
        if (!json.has("v")) return 0;
        try {
            return json.get("v").getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Says, once per channel and server, that a server speaks another version of a channel.
     *
     * @param channelName The channel.
     * @param source      The server the message came from.
     * @param remote      The version it was written in.
     * @param local       The version this server speaks.
     * @param legacy      Whether the message carried no version at all (an XCore 1.x server).
     */
    private void reportMismatch(String channelName, String source, int remote, int local, boolean legacy) {
        String server = serverOf(source);
        Integer previous = reportedMismatches.put(mismatchKey(channelName, source), remote);
        if (previous != null && previous == remote) return;

        boolean newer = remote > local;
        String text = "Server " + server + " runs " + (newer ? "a newer" : "an older") + " " + channelName
                + " protocol (v" + remote + (legacy ? ", XCore 1.x" : "") + ") than this one (v" + local + "): "
                + (newer ? "this server is the one behind" : "that server is the one behind")
                + ", and its " + channelName + " messages are ignored until both speak the same version."
                + " Update every server of the network.";
        // The server name comes off the wire: printed as text, never read as formatting.
        logWarning.log(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text));
    }

    private static String serverOf(String source) {
        return source == null || source.isBlank() ? "unknown" : source;
    }

    private static String mismatchKey(String channelName, String source) {
        return channelName + '\u0000' + serverOf(source);
    }
}
