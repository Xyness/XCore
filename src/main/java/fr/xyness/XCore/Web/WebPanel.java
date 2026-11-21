package fr.xyness.XCore.Web;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Utils.Logger;

/**
 * Core web dashboard server for XCore.
 * <p>
 * Hosts an HTTP server that serves the single-page application and provides
 * REST API endpoints. Other plugins can register {@link WebModule} instances
 * to extend the dashboard with their own pages and API routes.
 * </p>
 */
public class WebPanel {

    // **************************************************************************
    // *                              Constants                                 *
    // **************************************************************************

    /** Content type for JSON responses. */
    private static final String JSON_CONTENT_TYPE = "application/json; charset=UTF-8";

    // **************************************************************************
    // *                              Fields                                    *
    // **************************************************************************

    /** Logger instance for this class. */
    private final Logger logger;

    /** The HTTP server instance. */
    private HttpServer server;

    /** Port the server listens on. */
    private final int port;

    /** Whether the /api/metrics endpoint is public (no auth required). */
    private final boolean metricsPublic;

    /** Registered web modules from other plugins. */
    private final List<WebModule> modules = Collections.synchronizedList(new ArrayList<>());

    /** The routes each module created, so that taking a module down takes them down too. */
    private final java.util.Map<WebModule, List<String>> moduleContexts = new java.util.concurrent.ConcurrentHashMap<>();

    /** Path to the static web files directory. */
    private final Path webRoot;

    /** The server start time in millis, for uptime calculation. */
    private final long startTimeMillis;

    /**
     * Address the server listens on, or {@code null} for every interface.
     *
     * <p>Every interface was the only behaviour until this setting existed, so it stays the
     * default; a dashboard published by a reverse proxy on the same machine is better bound to
     * {@code 127.0.0.1}, where nothing but the proxy can reach it.</p>
     */
    private final String bindAddress;

    /** Allowed CORS origin, {@code "*"} to allow any. Used when a single value is configured. */
    private final String corsOrigin;

    /**
     * The allowed origins when {@code cors-origin} lists several, or {@code null} otherwise.
     *
     * <p>{@code Access-Control-Allow-Origin} carries one origin, never a list — a browser refuses
     * the response outright — so a list is answered request by request: the caller's own
     * {@code Origin} is sent back when it is on the list, and nothing at all when it is not.</p>
     */
    private final java.util.Set<String> corsOrigins;

    /** Maximum authentication attempts per IP within {@link #AUTH_WINDOW_MS}. */
    private static final int AUTH_LIMIT = 10;

    /** Maximum requests per IP per minute on the core endpoints. */
    private static final int REQUEST_LIMIT = 120;

    /** Sliding window used by both limiters. */
    private static final long AUTH_WINDOW_MS = 60_000L;

    /** Per-IP timestamps of recent failed authentications. */
    private final java.util.Map<String, java.util.Deque<Long>> authAttempts = new java.util.concurrent.ConcurrentHashMap<>();

    /** Per-IP timestamps of recent requests. */
    private final java.util.Map<String, java.util.Deque<Long>> requestHits = new java.util.concurrent.ConcurrentHashMap<>();

    /** The plugin, for the configured language. */
    private final XCore main;

    /** Dashboard UI strings for the active language, serialised once per (re)load. */
    private volatile String stringsJson = "{}";

    /**
     * What the dashboard needs to know about the running server.
     *
     * @param online How many players are connected.
     * @param max    The player limit.
     * @param tps    The last known tick rate, or 0 when the platform does not publish one.
     * @param worlds How many worlds are loaded.
     * @param names  The connected player names.
     */
    private record ServerSnapshot(int online, int max, double tps, int worlds, List<String> names) {}

    /** Refreshed on the server thread; read by the HTTP threads. */
    private volatile ServerSnapshot snapshot = new ServerSnapshot(0, 0, 0, 0, List.of());

    /** Handle of the snapshot task, cancelled on stop. */
    private Object snapshotTask;

    /** Clears the rate-limit maps of addresses that have gone quiet. */
    private Object pruneTask;

    /**
     * The HTTP thread pool, kept so it can be shut down.
     *
     * <p>{@code HttpServer#stop} does not touch an executor the caller supplied — the JDK is
     * explicit about that — so without this the four threads survived every start/stop cycle. They
     * are daemon threads, so the JVM still exits; a plugin reload simply left four more behind each
     * time.</p>
     */
    private java.util.concurrent.ExecutorService httpExecutor;

    /**
     * A static file as served: its bytes, its compressed form once a browser has asked for it, and
     * the validator browsers send back to learn whether it changed.
     *
     * @param data     The file's bytes.
     * @param gzip     The gzipped bytes, or {@code null} until first needed.
     * @param etag     A weak validator derived from the content, so it survives a restart that
     *                 rewrites the file unchanged.
     * @param modified The modification time the bytes were read at.
     */
    private record StaticFile(byte[] data, byte[] gzip, String etag, long modified) {}

    /** Static files already read from disk, keyed by path. */
    private final java.util.Map<String, StaticFile> staticCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Live sessions, as {@code SHA-256(token) -> session}.
     *
     * <p>Only the hash is ever kept, in memory and on disk: a leaked {@code web-tokens.json} lets
     * nobody log in. The plain token exists once, in the link handed to the player.</p>
     */
    private final java.util.Map<String, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    /** Source of session tokens. */
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    /** Where sessions survive a restart. */
    private final File sessionFile;

    /**
     * One dashboard session.
     *
     * @param owner    Name of the player it was issued to, for {@code /xcore dashboard}.
     * @param ownerId  UUID of that player, so their sessions can be revoked together.
     * @param expiresAt Epoch millis after which the session is refused.
     * @param locale   The client language of the player it was issued to, e.g. {@code fr_fr}, or
     *                 empty. Captured when the link is created, because the dashboard outlives the
     *                 session that opened it and the player may well be offline while reading it.
     * @param access   What its owner may open and do, as last read from the game.
     * @param readAt   Epoch millis of that reading. Past {@code web-dashboard.offline-trust-hours}
     *                 the session closes: a reading that old says little about a rank today.
     */
    private record Session(String owner, String ownerId, long expiresAt, String locale, Access access, long readAt) {

        /** The same session, with its owner's permissions just read again. */
        Session with(Access next, long at) {
            return new Session(owner, ownerId, expiresAt, locale, next, at);
        }
    }

    /** The longest a link from {@code /xcore dashboard} lasts, in hours: a week. */
    public static final long MAX_SESSION_HOURS = 168;

    /** How long a link lasts, in hours, from {@code web-dashboard.session-ttl-hours} held to the maximum. */
    private volatile long sessionHours = 24;

    /**
     * How long a session goes on its owner's last reading, from
     * {@code web-dashboard.offline-trust-hours}. Their permissions are read every minute while they
     * play here, so this only ever runs down while they are away.
     */
    private volatile long offlineTrustMillis = 2 * 3_600_000L;

    /**
     * What {@code offline-trust-hours: 0} stands for: only while the owner is on this server. Three
     * minutes, so the reading done every minute keeps a connected owner's session open even when a
     * round runs late.
     */
    private static final long ONLINE_ONLY_MILLIS = 3 * 60_000L;

    /** How often the readings alone, when nothing else changed, are written to {@code web-sessions.json}. */
    private static final long READINGS_SAVE_MILLIS = 10 * 60_000L;

    /** When the readings were last written. */
    private volatile long readingsSavedAt;

    // **************************************************************************
    // *                            Permissions                                 *
    // **************************************************************************

    /** Opens the dashboard at all: {@code /xcore dashboard} asks for it, and a session lasts as long as its owner holds it. */
    public static final String LOGIN_PERMISSION = "xcore.dashboard";

    /** The players list and every player's profile. */
    public static final String PLAYERS_PERMISSION = "xcore.dashboard.players";

    /** Reading the configuration editor of the modules the reader may open. */
    public static final String CONFIG_PERMISSION = "xcore.dashboard.config";

    /**
     * Saving a configuration, on top of the permission the module names for it. A configuration can
     * make a plugin run any console command or turn its protections off: saving one is trusted like
     * the console, so it has a node of its own, which {@code xcore.dashboard.<module>.manage} does
     * not stand in for.
     */
    public static final String CONFIG_SAVE_PERMISSION = "xcore.dashboard.config.save";

    /**
     * The permission that opens a module's pages: {@code xcore.dashboard.<name>}, the name in lower
     * case and reduced to letters, digits, dashes and underscores.
     *
     * @param moduleName The module's name, as {@link WebModule#getName()} returns it.
     * @return The permission node.
     */
    public static String modulePermission(String moduleName) {
        return "xcore.dashboard." + moduleKey(moduleName);
    }

    /**
     * What a module's actions need when its pages name no permission for them:
     * {@code xcore.dashboard.<name>.manage}. Third-party modules written before permissions existed
     * are all in that case.
     *
     * @param moduleName The module's name.
     * @return The permission node.
     */
    public static String managePermission(String moduleName) {
        return modulePermission(moduleName) + ".manage";
    }

    /** A module name as it appears in a permission node. */
    private static String moduleKey(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    /**
     * What a session may open and do: its owner's dashboard permissions, as they stood when last
     * read from the game.
     *
     * <p>They are read while the owner is connected — when the link is made, when they join, and
     * every minute while they play — because that is the only time a server can ask for them. In
     * between the dashboard goes by the last reading, which is what lets a moderator use it from a
     * phone, away from the game — for {@code web-dashboard.offline-trust-hours} at most.</p>
     *
     * @param nodes Each permission that was read, and whether the owner held it.
     * @param op    Whether the owner was an operator. It answers for a permission that did not exist
     *              when they were read — a module installed since — the way the server answers for
     *              a permission nobody set: yes for an operator, no for anyone else.
     */
    private record Access(java.util.Map<String, Boolean> nodes, boolean op) {

        /** @return Whether the owner holds the node, given in lower case. */
        boolean has(String node) {
            Boolean held = nodes.get(node);
            return held != null ? held : op;
        }
    }

    /**
     * What the modules declared: the permission each endpoint their pages post to needs, what
     * reading their pages and routes needs, and what saving each module's configuration needs.
     *
     * <p>Rebuilt whenever a module comes or goes, and each time the module list is served, so it
     * follows a module whose pages change while it runs.</p>
     *
     * @param endpoints Endpoint path, without its query string, to what posting there needs: a list
     *                  of requirements, each met by any one of its nodes, all of them to be met.
     * @param reads     Path, {@code *} standing for one segment, to what reading it needs.
     * @param saves     Module key to what saving its configuration needs, when it declared it.
     * @param nodes     Every permission a session's reading covers.
     */
    private record Rules(java.util.Map<String, List<List<String>>> endpoints,
                         java.util.Map<String, List<List<String>>> reads,
                         java.util.Map<String, List<List<String>>> saves,
                         java.util.Set<String> nodes) {

        static final Rules CORE = new Rules(java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of(LOGIN_PERMISSION, PLAYERS_PERMISSION, CONFIG_PERMISSION, CONFIG_SAVE_PERMISSION));
    }

    /** The current rules, replaced whole: an HTTP thread never sees one half rebuilt. */
    private volatile Rules rules = Rules.CORE;

    /**
     * The module that answers a request, and the route of its that does.
     *
     * @param module  The module.
     * @param context The path of the route that answers it, or {@code null} when it was not created
     *                through the panel and only the module's base path is known.
     */
    private record Target(WebModule module, String context) {}

    /** Reads the permissions of every connected session owner again, every minute. */
    private Object accessTask;

    /** Reads a session owner's permissions again the moment they join. */
    private WebSessionListener joinListener;

    // **************************************************************************
    // *                           Constructor                                  *
    // **************************************************************************

    /**
     * Creates a new WebPanel instance listening on every interface.
     *
     * @param main          The plugin instance.
     * @param dataFolder    The plugin data folder (used to resolve the {@code web/} directory).
     * @param port          The port to listen on.
     * @param metricsPublic Whether the metrics endpoint is publicly accessible.
     * @param corsOrigin    The allowed CORS origin, a comma-separated list of origins, or {@code "*"}.
     */
    public WebPanel(XCore main, File dataFolder, int port, boolean metricsPublic, String corsOrigin) {
        this(main, dataFolder, port, metricsPublic, corsOrigin, null);
    }

    /**
     * Creates a new WebPanel instance.
     *
     * @param main          The plugin instance, also used to resolve the configured language.
     * @param dataFolder    The plugin data folder (used to resolve the {@code web/} directory).
     * @param port          The port to listen on.
     * @param metricsPublic Whether the metrics endpoint is publicly accessible.
     * @param corsOrigin    The allowed CORS origin, a comma-separated list of origins, or {@code "*"}.
     * @param bindAddress   The address to listen on; blank, {@code "0.0.0.0"}, {@code "::"} or
     *                      {@code "*"} for every interface.
     */
    public WebPanel(XCore main, File dataFolder, int port, boolean metricsPublic, String corsOrigin,
                    String bindAddress) {
        this.main = main;
        this.port = port;
        this.metricsPublic = metricsPublic;
        this.bindAddress = listensEverywhere(bindAddress) ? null : bindAddress.trim();

        // One value — "*" or a single origin — is sent as it is, exactly as before lists existed.
        // A "*" anywhere in a list still means "anyone": that is what whoever wrote it allowed.
        List<String> origins = new ArrayList<>();
        if (corsOrigin != null) {
            for (String part : corsOrigin.split(",")) {
                String origin = part.trim();
                if (!origin.isEmpty()) origins.add(origin);
            }
        }
        if (origins.size() > 1 && !origins.contains("*")) {
            this.corsOrigin = null;
            this.corsOrigins = java.util.Set.copyOf(origins);
        } else {
            this.corsOrigin = origins.isEmpty() || origins.contains("*") ? "*" : origins.get(0);
            this.corsOrigins = null;
        }

        this.logger = new Logger("WebPanel");
        this.webRoot = new File(dataFolder, "web").toPath();
        this.sessionFile = new File(dataFolder, "web-sessions.json");
        this.startTimeMillis = System.currentTimeMillis();
        // Before the sessions: loading them drops those their settings no longer allow.
        reloadSettings();
        loadSessions();
        reloadStrings();
    }

    /**
     * Reads the session settings of {@code web-dashboard} again: how long a link lasts, and how
     * long a session goes on its owner's last reading while they are not on this server. Called
     * when the panel is made and by {@code /xcore reload}; a value out of range is said once, here.
     */
    public void reloadSettings() {
        long hours = main.getConfig().getLong("web-dashboard.session-ttl-hours", 24);
        if (hours <= 0 || hours > MAX_SESSION_HOURS) {
            // 0 used to mean a link that never ends: it outlived every rank its owner lost, and
            // the leak of a single link was forever.
            logger.sendWarning("web-dashboard.session-ttl-hours is " + hours + ": a dashboard link lasts "
                    + MAX_SESSION_HOURS + " hours at most, and lasts that long.");
            hours = MAX_SESSION_HOURS;
        }
        sessionHours = hours;

        double trust = main.getConfig().getDouble("web-dashboard.offline-trust-hours", 2);
        if (trust > MAX_SESSION_HOURS) {
            logger.sendWarning("web-dashboard.offline-trust-hours is " + trust + ": no link lasts more than "
                    + MAX_SESSION_HOURS + " hours, which is what it amounts to.");
            trust = MAX_SESSION_HOURS;
        }
        offlineTrustMillis = trust <= 0 ? ONLINE_ONLY_MILLIS : Math.max(ONLINE_ONLY_MILLIS, (long) (trust * 3_600_000L));
    }

    /** @return How long a link from {@code /xcore dashboard} lasts, in hours, as it is applied. */
    public long sessionHours() {
        return sessionHours;
    }

    // **************************************************************************
    // *                             Sessions                                   *
    // **************************************************************************

    /**
     * Opens a dashboard session and returns the token that unlocks it.
     *
     * <p>The returned string is the only copy in existence — this class stores its hash. Hand it
     * to the player as a one-click link and forget it.</p>
     *
     * <p>The player's dashboard permissions are read here, on their own thread — they are
     * connected, since they just asked for the link — and the session goes by them from then on,
     * read again while they are in game.</p>
     *
     * @param playerId   UUID of the player the session belongs to.
     * @param playerName Their name, shown when listing sessions.
     * @return The plain token to put in the link.
     */
    public String createSession(java.util.UUID playerId, String playerName) {
        org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);

        // Whoever asked for the link is the one who will read the dashboard: their game language is
        // a far better guess than the server's own setting.
        String locale = "";
        try {
            if (player != null && player.locale() != null) {
                locale = player.locale().toString().toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Throwable ignored) {
            // A platform without per-player locales falls back to the configured language.
        }

        // A player who is not connected has no permissions to read: the session starts from
        // whether they are an operator, and is read properly the next time they join.
        Access access = player != null ? accessOf(player) : new Access(java.util.Map.of(), isOperator(playerId));

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String plain = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        long now = System.currentTimeMillis();
        long expiresAt = now + sessionHours * 3_600_000L;

        sessions.put(hash(plain), new Session(playerName, playerId.toString(), expiresAt, locale, access, now));
        pruneSessions();
        saveSessions();
        return plain;
    }

    /**
     * Reads a connected player's dashboard permissions: every node of the current {@link Rules},
     * and whether they are an operator. Runs on that player's thread.
     *
     * @param player The player.
     * @return What their sessions may do.
     */
    private Access accessOf(org.bukkit.entity.Player player) {
        java.util.Map<String, Boolean> nodes = new java.util.HashMap<>();
        for (String node : rules.nodes()) nodes.put(node, player.hasPermission(node));
        return new Access(java.util.Map.copyOf(nodes), player.isOp());
    }

    /** Whether a player is an operator — the one thing the server knows of a player who is not connected. */
    private static boolean isOperator(java.util.UUID playerId) {
        try {
            return Bukkit.getOfflinePlayer(playerId).isOp();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Reads the permissions of every session owner who is connected, each on their own thread.
     * Runs on the global thread, every minute and after a module comes or goes.
     */
    private void refreshOwners() {
        java.util.Set<java.util.UUID> owners = new java.util.HashSet<>();
        for (Session session : sessions.values()) {
            if (session.ownerId().isBlank()) continue;
            try {
                owners.add(java.util.UUID.fromString(session.ownerId()));
            } catch (IllegalArgumentException ignored) {
                // Not a player: nothing to read.
            }
        }
        for (java.util.UUID id : owners) {
            org.bukkit.entity.Player player = Bukkit.getPlayer(id);
            if (player != null) main.schedulerAdapter().runEntityTask(player, () -> reread(player));
        }
    }

    /**
     * Reads a connected player's dashboard permissions again and applies them to every session they
     * own; the next request each of those sessions makes goes by them, and the reading starts the
     * {@code offline-trust-hours} count again. A player who no longer holds
     * {@value #LOGIN_PERMISSION} has their sessions closed.
     *
     * <p>Runs on the player's thread: from the join event, or scheduled there by
     * {@link #refreshOwners()}.</p>
     *
     * @param player The player.
     */
    void reread(org.bukkit.entity.Player player) {
        if (player == null || !player.isOnline()) return;
        String id = player.getUniqueId().toString();
        boolean owns = false;
        for (Session session : sessions.values()) {
            if (id.equals(session.ownerId())) {
                owns = true;
                break;
            }
        }
        if (!owns) return;

        Access access = accessOf(player);
        long now = System.currentTimeMillis();
        boolean changed = false;
        if (!access.has(LOGIN_PERMISSION)) {
            changed = sessions.values().removeIf(session -> id.equals(session.ownerId()));
            if (changed) {
                logger.sendInfo("Dashboard sessions of <aqua>" + player.getName() + "</aqua> closed: they no longer hold "
                        + LOGIN_PERMISSION + ".");
            }
        } else {
            for (var entry : sessions.entrySet()) {
                Session session = entry.getValue();
                if (!id.equals(session.ownerId())) continue;
                boolean different = !session.access().equals(access);
                if (sessions.replace(entry.getKey(), session, session.with(access, now)) && different) changed = true;
            }
        }
        // The file follows the readings a few minutes behind at most, so a restart goes on from
        // about where they were and never trusts one for longer than the setting allows. Written
        // off the player's thread: on Paper that thread is the server's own.
        if (changed || now - readingsSavedAt > READINGS_SAVE_MILLIS) {
            readingsSavedAt = now;
            main.schedulerAdapter().runAsyncTask(this::saveSessions);
        }
    }

    /**
     * Closes every session belonging to a player — the answer to a link that ended up in the wrong
     * hands.
     *
     * @param playerId The player whose sessions to drop.
     * @return How many sessions were closed.
     */
    public int revokeSessions(java.util.UUID playerId) {
        String id = playerId.toString();
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        sessions.values().removeIf(session -> {
            boolean match = id.equals(session.ownerId());
            if (match) removed.incrementAndGet();
            return match;
        });
        if (removed.get() > 0) saveSessions();
        return removed.get();
    }

    /**
     * Closes every session of the player who goes by a name: the name their sessions were issued
     * under, compared ignoring case, and the player connected under it now — whose sessions may
     * carry the name they had before. For an administrator, when a member of staff leaves the team
     * or a link went astray, whoever it belongs to.
     *
     * @param playerName The name.
     * @return How many sessions were closed.
     */
    public int revokeSessions(String playerName) {
        if (playerName == null || playerName.isBlank()) return 0;
        String name = playerName.trim();
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
        String id = online != null ? online.getUniqueId().toString() : null;
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        sessions.values().removeIf(session -> {
            boolean match = session.owner().equalsIgnoreCase(name) || (id != null && id.equals(session.ownerId()));
            if (match) removed.incrementAndGet();
            return match;
        });
        if (removed.get() > 0) saveSessions();
        return removed.get();
    }

    /** @return The names the open sessions were issued to, sorted, each once: what {@code /xcore dashboard revoke} completes. */
    public List<String> sessionOwners() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Session session : sessions.values()) {
            if (session.owner() != null && !session.owner().isBlank()) names.add(session.owner());
        }
        return new ArrayList<>(names);
    }

    /** @return How many sessions are currently open. */
    public int getSessionCount() {
        pruneSessions();
        return sessions.size();
    }

    /** SHA-256, hex — deterministic, and not reversible back to the token. */
    private String hash(String plain) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    /**
     * Drops the sessions that may no longer be used, so an old link cannot be replayed: those past
     * their expiry, and those whose owner's permissions were last read longer ago than
     * {@code web-dashboard.offline-trust-hours} — an owner away from this server all that time, whose
     * rank may well have changed since. They get a new link in game.
     */
    private void pruneSessions() {
        long now = System.currentTimeMillis();
        long trust = offlineTrustMillis;
        List<String> stale = new ArrayList<>();
        sessions.values().removeIf(session -> {
            if (session.expiresAt() < now) return true;
            if (now - session.readAt() <= trust) return false;
            stale.add(session.owner());
            return true;
        });
        if (!stale.isEmpty()) {
            logger.sendInfo("Dashboard session(s) of <aqua>" + String.join(", ", new java.util.TreeSet<>(stale))
                    + "</aqua> closed: their permissions were last read in game more than " + offlineTrustLabel()
                    + " ago (web-dashboard.offline-trust-hours). A new link comes with /xcore dashboard.");
        }
    }

    /** @return How long a session goes on without its owner on this server, as a player reads it: {@code 2 h}, {@code 30 min}. */
    public String offlineTrustLabel() {
        long minutes = offlineTrustMillis / 60_000L;
        return minutes % 60 == 0 ? (minutes / 60) + " h" : minutes + " min";
    }

    private void loadSessions() {
        if (!sessionFile.isFile()) return;
        int closed = 0;
        int unbounded = 0;
        long now = System.currentTimeMillis();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(sessionFile.toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (String key : root.keySet()) {
                JsonObject entry = root.getAsJsonObject(key);
                String ownerId = entry.has("ownerId") ? entry.get("ownerId").getAsString() : "";
                Access access = savedAccess(entry, ownerId);
                if (access == null) {
                    closed++;
                    continue;
                }
                long expiresAt = entry.has("expiresAt") ? entry.get("expiresAt").getAsLong() : 0L;
                if (expiresAt <= 0) {
                    // Opened while 0 meant "never": it ends like any link opened now.
                    expiresAt = now + MAX_SESSION_HOURS * 3_600_000L;
                    unbounded++;
                }
                // A session from before permissions were saved has just been read, from the
                // operators' list. One saved with permissions but no date is as old as can be: it
                // waits for its owner to join before it is used again.
                boolean estimated = !(entry.has("access") && entry.get("access").isJsonObject());
                long readAt = entry.has("readAt") ? entry.get("readAt").getAsLong() : estimated ? now : 0L;
                sessions.put(key, new Session(
                        entry.has("owner") ? entry.get("owner").getAsString() : "?",
                        ownerId,
                        expiresAt,
                        entry.has("locale") ? entry.get("locale").getAsString() : "",
                        access,
                        readAt));
            }
            pruneSessions();
        } catch (Exception e) {
            logger.sendWarning("Could not read web-sessions.json: " + e.getMessage());
        }
        if (closed > 0) {
            logger.sendInfo(closed + " dashboard session(s) opened before the dashboard followed permissions were closed:"
                    + " their owners are not operators. They get a new link with /xcore dashboard, once they hold "
                    + LOGIN_PERMISSION + ".");
        }
        if (unbounded > 0) {
            logger.sendInfo(unbounded + " dashboard session(s) opened to last forever now end within "
                    + MAX_SESSION_HOURS + " hours.");
        }
        if (closed > 0 || unbounded > 0) saveSessions();
    }

    /**
     * The permissions saved with a session, or the best that can be done without them.
     *
     * <p>A session opened before the dashboard followed permissions carries none. Its owner was an
     * administrator then, which says nothing of what they may do now. An operator keeps theirs — an
     * operator holds every dashboard permission unless one was taken away, and the next reading will
     * tell. Anyone else is asked for a new link, which reads their permissions as it is made. A
     * session tied to no player has nobody whose permissions could ever be read again: it closes.</p>
     *
     * @param entry   The saved session.
     * @param ownerId Its owner's UUID, empty for a session tied to no player.
     * @return What the session may do, or {@code null} when it has to close.
     */
    private Access savedAccess(JsonObject entry, String ownerId) {
        if (ownerId.isBlank()) return null;
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(ownerId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (entry.has("access") && entry.get("access").isJsonObject()) {
            JsonObject saved = entry.getAsJsonObject("access");
            java.util.Map<String, Boolean> nodes = new java.util.HashMap<>();
            if (saved.has("nodes") && saved.get("nodes").isJsonObject()) {
                for (var node : saved.getAsJsonObject("nodes").entrySet()) {
                    if (node.getValue().isJsonPrimitive()) {
                        nodes.put(node.getKey().toLowerCase(java.util.Locale.ROOT), node.getValue().getAsBoolean());
                    }
                }
            }
            boolean op = saved.has("op") && saved.get("op").isJsonPrimitive() && saved.get("op").getAsBoolean();
            return new Access(java.util.Map.copyOf(nodes), op);
        }
        return isOperator(id) ? new Access(java.util.Map.of(), true) : null;
    }

    /** Synchronised: the command thread, the HTTP threads and the refresh all write it. */
    private synchronized void saveSessions() {
        try {
            JsonObject root = new JsonObject();
            sessions.forEach((key, session) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("owner", session.owner());
                entry.addProperty("ownerId", session.ownerId());
                entry.addProperty("expiresAt", session.expiresAt());
                entry.addProperty("locale", session.locale() == null ? "" : session.locale());
                // Kept with the session, so a restart does not leave a reader who is not in game
                // with nothing until they next join — dated, so it is not trusted for longer
                // across a restart than it would have been without one.
                JsonObject access = new JsonObject();
                access.addProperty("op", session.access().op());
                JsonObject nodes = new JsonObject();
                session.access().nodes().forEach((node, held) -> nodes.addProperty(node, held));
                access.add("nodes", nodes);
                entry.add("access", access);
                entry.addProperty("readAt", session.readAt());
                root.add(key, entry);
            });
            Files.writeString(sessionFile.toPath(), root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.sendWarning("Could not write web-sessions.json: " + e.getMessage());
        }
    }

    /**
     * Loads the dashboard UI strings for the configured language.
     *
     * <p>Same layout and same fallback as every other language file: {@code lang/web_<code>.yml},
     * bundled translations extracted on first run, English when the chosen language has none.
     * Called again on reload so changing {@code language} does not need a restart.</p>
     */
    public void reloadStrings() {
        File folder = new File(main.getDataFolder(), "lang");
        if (!folder.exists()) folder.mkdirs();
        for (String code : new String[]{"en", "fr"}) {
            File f = new File(folder, "web_" + code + ".yml");
            if (!f.exists()) {
                try { main.saveResource("lang/web_" + code + ".yml", false); } catch (Exception ignored) {}
                continue;
            }
            // A file already on disk never saw the keys a later version added, and the dashboard
            // printed those keys raw. Missing keys are filled in; existing ones are left alone.
            mergeMissingKeys(f, "lang/web_" + code + ".yml");
        }

        String wanted = main.getLanguage();
        File target = new File(folder, "web_" + wanted + ".yml");
        if (!target.exists()) target = new File(folder, "web_en.yml");

        JsonObject json = new JsonObject();
        readStringsInto(json, target, "core");

        // Each addon owns the words its own pages use, and ships them beside its other language
        // files. The core only holds the shared vocabulary, so it wins on the rare overlap.
        synchronized (modules) {
            for (WebModule module : modules) {
                File addonFolder = new File(moduleFolder(module.getName()), "lang");
                File addonFile = new File(addonFolder, "web_" + wanted + ".yml");
                if (!addonFile.isFile()) addonFile = new File(addonFolder, "web_en.yml");
                if (addonFile.isFile()) readStringsInto(json, addonFile, module.getName());
            }
        }

        stringsJson = json.toString();
    }

    /**
     * The folder of the addon or plugin a module belongs to, where its {@code config.yml} and its
     * {@code lang/web_*.yml} are.
     *
     * <p>Asked of the extension rather than built from the name: a plugin built on XCore keeps its
     * files in {@code plugins/<name>/}, and the path this used to assume,
     * {@code plugins/XCore/addons/<name>/}, is only right for an addon. A module that belongs to
     * neither — the core's own — falls back to it and finds nothing, as before.</p>
     *
     * @param name The module name, which is the extension's name.
     * @return The folder.
     */
    private File moduleFolder(String name) {
        return main.extensions().find(name)
                .map(fr.xyness.XCore.Extension.XExtension::dataFolder)
                .orElseGet(() -> new File(main.getDataFolder(), "addons" + File.separator + name));
    }

    /**
     * Writes into {@code target} the keys the bundled version has and it does not.
     *
     * @param target       The file on disk.
     * @param resourcePath The bundled version inside XCore's jar.
     */
    private void mergeMissingKeys(File target, String resourcePath) {
        try (java.io.InputStream defaults = main.getResource(resourcePath)) {
            if (defaults == null) return;
            var bundled = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(defaults, java.nio.charset.StandardCharsets.UTF_8));
            var current = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(target);
            boolean changed = false;
            for (String key : bundled.getKeys(true)) {
                if (bundled.isConfigurationSection(key) || current.contains(key)) continue;
                current.set(key, bundled.get(key));
                changed = true;
            }
            if (changed) current.save(target);
        } catch (Exception e) {
            logger.sendDebug("Failed to merge " + resourcePath + ": " + e.getMessage());
        }
    }

    /**
     * Adds a file's keys to the payload without overwriting what is already there.
     *
     * @param into   The payload being assembled.
     * @param file   The YAML file to read.
     * @param source Who the strings belong to, for the debug line on a clash.
     */
    private void readStringsInto(JsonObject into, File file, String source) {
        try {
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(false)) {
                if (into.has(key)) {
                    logger.sendDebug("Dashboard string '" + key + "' from " + source
                        + " ignored: already defined.");
                    continue;
                }
                into.addProperty(key, yaml.getString(key, key));
            }
        } catch (Exception e) {
            logger.sendWarning("Could not load dashboard strings from " + file.getName() + ": " + e.getMessage());
        }
    }

    // **************************************************************************
    // *                        Lifecycle Methods                                *
    // **************************************************************************

    /**
     * Starts the HTTP server and registers all core endpoints.
     *
     * @throws IOException If the server cannot bind to the configured port.
     */
    public void start() throws IOException {
        // Every interface keeps the exact socket this always opened: the wildcard address, which
        // on a dual-stack machine accepts IPv6 as well as IPv4 — "0.0.0.0" taken literally would
        // quietly drop IPv6. A specific address that does not resolve fails the start rather than
        // falling back to every interface: whoever set it asked for less exposure, not more.
        InetSocketAddress address;
        if (bindAddress == null) {
            address = new InetSocketAddress(port);
        } else {
            try {
                address = new InetSocketAddress(java.net.InetAddress.getByName(bindAddress), port);
            } catch (java.net.UnknownHostException e) {
                throw new IOException("web-dashboard.bind-address \"" + bindAddress + "\" does not resolve", e);
            }
        }
        try {
            server = HttpServer.create(address, 0);
        } catch (IOException e) {
            // "Address already in use" alone does not say which address: name it.
            throw new IOException("cannot listen on " + (bindAddress == null ? "port " + port
                    : bindAddress + ":" + port) + " (" + e.getMessage() + ")", e);
        }
        httpExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "XCore-WebPanel-HTTP");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(httpExecutor);

        // Faces and item textures are fetched here, never on the four threads above: a full queue
        // answers without the image rather than making anyone wait.
        assetExecutor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(16), r -> {
                    Thread t = new Thread(r, "XCore-WebPanel-Assets");
                    t.setDaemon(true);
                    return t;
                });
        assetExecutor.allowCoreThreadTimeOut(true);

        // API endpoints
        server.createContext("/api/lang", this::handleLang);
        server.createContext("/api/sprite/", this::handleSprite);
        server.createContext("/api/head/", this::handleHead);
        server.createContext("/api/mclang", this::handleMinecraftLang);
        server.createContext("/api/auth", this::handleAuth);
        server.createContext("/api/modules", this::handleModules);
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/players", this::handlePlayers);

        // Static file handler (catch-all, must be last)
        server.createContext("/", this::handleStatic);

        server.start();

        // The server-state snapshot, refreshed on the server thread twice a second. The HTTP threads
        // then answer from memory instead of calling into Bukkit from a foreign thread.
        snapshotTask = main.schedulerAdapter().runGlobalTaskTimer(this::refreshSnapshot, 20L, 10L);

        // Every five minutes, forget the addresses that have gone quiet. Off the server thread:
        // it touches nothing but two maps.
        pruneTask = main.schedulerAdapter().runAsyncTaskTimer(this::pruneRateLimits, 6000L, 6000L);

        // Each session goes by its owner's permissions, which the server can only read while they
        // are connected: on joining, and every minute after that. A change applies at the next
        // request; losing xcore.dashboard closes the session.
        accessTask = main.schedulerAdapter().runGlobalTaskTimer(this::refreshOwners, 1200L, 1200L);
        joinListener = new WebSessionListener(this);
        Bukkit.getPluginManager().registerEvents(joinListener, main);

        // Warmed in the background: the first reader would otherwise wait on a half-megabyte fetch
        // from the asset mirror, and see English item names until it lands.
        logger.sendInfo("Dashboard assets follow Minecraft <aqua>" + assetVersion() + "</aqua>.");

        Thread warm = new Thread(() -> {
            String locale = switch (main.getLanguage() == null ? "en" : main.getLanguage().toLowerCase(java.util.Locale.ROOT)) {
                case "fr" -> "fr_fr";
                case "es" -> "es_es";
                case "de" -> "de_de";
                case "it" -> "it_it";
                case "pt" -> "pt_br";
                default -> "en_us";
            };
            String base = "https://assets.mcasset.cloud/" + assetVersion() + "/assets/minecraft/lang/";
            String json = fetchText(base + locale + ".json");
            if (json != null) {
                minecraftLangCache.put(locale, json);
                logger.sendDebug("Minecraft translations cached for " + locale + " (" + json.length() + " bytes).");
            }
        }, "XCore-lang-warmup");
        warm.setDaemon(true);
        warm.start();
        if (bindAddress == null) {
            logger.sendInfo("Web dashboard started on port <aqua>" + port + "</aqua>.");
        } else {
            logger.sendInfo("Web dashboard started on <aqua>" + bindAddress + ":" + port + "</aqua> only.");
        }
    }

    /**
     * Whether a configured bind address means "every interface".
     *
     * <p>Shared with the command that builds login links, so both read the setting the same way.</p>
     *
     * @param address The {@code web-dashboard.bind-address} value.
     * @return {@code true} for a blank value, {@code 0.0.0.0}, {@code ::} or {@code *}.
     */
    public static boolean listensEverywhere(String address) {
        if (address == null) return true;
        String value = address.trim();
        return value.isEmpty() || value.equals("0.0.0.0") || value.equals("::")
                || value.equals("[::]") || value.equals("*");
    }

    /**
     * Stops the HTTP server gracefully.
     */
    public void stop() {
        if (snapshotTask != null) {
            main.schedulerAdapter().cancelTask(snapshotTask);
            snapshotTask = null;
        }
        if (pruneTask != null) {
            main.schedulerAdapter().cancelTask(pruneTask);
            pruneTask = null;
        }
        if (accessTask != null) {
            main.schedulerAdapter().cancelTask(accessTask);
            accessTask = null;
        }
        if (joinListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(joinListener);
            joinListener = null;
        }
        if (server != null) {
            server.stop(2);
            logger.sendInfo("Web dashboard stopped.");
        }
        // After the server, so in-flight requests get their two seconds first.
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        if (assetExecutor != null) {
            assetExecutor.shutdownNow();
            assetExecutor = null;
        }
        assetFetches.clear();
        authAttempts.clear();
        requestHits.clear();
        imageHits.clear();
        imageFetches.clear();
    }

    /** Reads the live server state. Runs on the server thread, never on an HTTP one. */
    private void refreshSnapshot() {
        double tps = 0;
        try {
            tps = Bukkit.getTPS()[0];
        } catch (Throwable ignored) {
            // Folia does not publish a global TPS.
        }
        List<String> names = new ArrayList<>();
        for (var player : Bukkit.getOnlinePlayers()) names.add(player.getName());
        snapshot = new ServerSnapshot(names.size(), Bukkit.getMaxPlayers(), tps, Bukkit.getWorlds().size(), names);
    }

    // **************************************************************************
    // *                       Module Registration                              *
    // **************************************************************************

    /**
     * Registers a web module and its API routes on the server.
     * <p>
     * The module's routes are registered under {@code /api/<moduleName>/...},
     * where the module name is lowercased.
     * </p>
     *
     * @param module The web module to register.
     */
    public void registerModule(WebModule module) {
        if (server == null) {
            logger.sendWarning("Cannot register web module '" + module.getName() + "': server not started.");
            return;
        }
        // Same name already here. Replaced when it is the same plugin's, registered again, or when
        // whoever registered it no longer runs — the previous instance of a plugin disabled and
        // started again without its module being taken down, whose routes would make creating them
        // a second time throw. Another running extension's module is never taken down for it: the
        // newcomer goes without, and the console says why.
        WebModule existing = findModule(module.getName());
        if (existing != null) {
            ClassLoader owner = existing.getClass().getClassLoader();
            if (owner != module.getClass().getClassLoader() && ownerRunning(owner)) {
                logger.sendError("Web module '" + module.getName() + "' of " + ownerName(module.getClass().getClassLoader())
                        + " was not registered: " + ownerName(owner) + " is running and already has a module of that"
                        + " name. Its pages stay; the newcomer's are not on the dashboard.");
                return;
            }
            removeModule(existing);
            logger.sendDebug("Web module '" + module.getName() + "' replaced by a new instance.");
        }
        String basePath = "/api/" + module.getName().toLowerCase();
        java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        RecordingServer routes = new RecordingServer(server, paths);
        moduleContexts.put(module, paths);
        modules.add(module);
        module.registerRoutes(routes, basePath);
        // Every addon has a config.yml, so every module gets the editor without asking for it.
        routes.createContext(basePath + "/config/raw", ex -> handleAddonConfig(ex, module.getName()));
        // The module may carry its own dashboard strings; fold them in now that it is known.
        reloadStrings();
        // Its permissions join what a session's reading covers: read again now for whoever is in
        // game, rather than at the next round.
        rebuildRules();
        main.schedulerAdapter().runGlobalTask(this::refreshOwners);
        logger.sendInfo("Registered web module: <aqua>" + module.getName() + "</aqua>.");
    }

    /**
     * Takes a module down: out of the sidebar, and every route it created removed.
     *
     * @param name The module name, compared ignoring case.
     * @return {@code true} when a module of that name was registered.
     */
    public boolean unregisterModule(String name) {
        WebModule found = findModule(name);
        if (found == null) return false;
        removeModule(found);
        return true;
    }

    /** The module registered under a name, compared ignoring case, or {@code null}. */
    private WebModule findModule(String name) {
        synchronized (modules) {
            for (WebModule module : modules) {
                if (module.getName().equalsIgnoreCase(name)) return module;
            }
        }
        return null;
    }

    /**
     * Whether the plugin or addon whose class loader defined a module is still running.
     *
     * @param loader The module's class loader.
     * @return {@code false} when its owner is disabled, or no longer loaded at all.
     */
    private boolean ownerRunning(ClassLoader loader) {
        if (loader == WebPanel.class.getClassLoader()) return main.isEnabled();
        if (loader instanceof fr.xyness.XCore.Addon.AddonClassLoader addonLoader) {
            fr.xyness.XCore.Addon.AddonManager addons = main.getAddonManager();
            fr.xyness.XCore.Addon.AddonState state = addons == null ? null
                    : addons.getState(addonLoader.getDescriptor().getName());
            return state == fr.xyness.XCore.Addon.AddonState.ENABLED
                    || state == fr.xyness.XCore.Addon.AddonState.ENABLING;
        }
        for (org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getClass().getClassLoader() == loader) return plugin.isEnabled();
        }
        return false;
    }

    /** Who a module's class loader belongs to, for the console. */
    private String ownerName(ClassLoader loader) {
        if (loader == WebPanel.class.getClassLoader()) return "XCore";
        if (loader instanceof fr.xyness.XCore.Addon.AddonClassLoader addonLoader) {
            return "the addon " + addonLoader.getDescriptor().getName();
        }
        for (org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getClass().getClassLoader() == loader) return plugin.getName();
        }
        return "a plugin no longer loaded";
    }

    /**
     * Takes down every module defined by the given class loader.
     *
     * <p>Called when a plugin built on XCore is disabled: its pages left the sidebar with it, but
     * its routes kept answering from a plugin that had stopped.</p>
     *
     * @param loader The class loader of the plugin going away.
     * @return The names of the modules removed.
     */
    public List<String> unregisterModulesOf(ClassLoader loader) {
        List<WebModule> owned = new ArrayList<>();
        synchronized (modules) {
            for (WebModule module : modules) {
                if (module.getClass().getClassLoader() == loader) owned.add(module);
            }
        }
        List<String> names = new ArrayList<>();
        for (WebModule module : owned) {
            removeModule(module);
            names.add(module.getName());
        }
        return names;
    }

    private void removeModule(WebModule module) {
        modules.remove(module);
        List<String> paths = moduleContexts.remove(module);
        if (paths != null && server != null) {
            for (String path : paths) {
                try {
                    server.removeContext(path);
                } catch (IllegalArgumentException alreadyGone) {
                    // Removed by the module itself.
                }
            }
        }
        reloadStrings();
        rebuildRules();
        logger.sendDebug("Unregistered web module: " + module.getName() + ".");
    }

    // **************************************************************************
    // *                        Who may call what                               *
    // **************************************************************************

    /** The registered modules, copied: their pages are read without holding the list. */
    private List<WebModule> modulesSnapshot() {
        synchronized (modules) {
            return new ArrayList<>(modules);
        }
    }

    /** A module's pages, or none when asking for them fails: one broken module does not take the panel down. */
    private List<WebPage> pagesOf(WebModule module) {
        try {
            List<WebPage> pages = module.getPages();
            return pages == null ? List.of() : pages;
        } catch (Throwable e) {
            logger.sendDebug("Web module '" + module.getName() + "' failed to list its pages: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Whether a page is the configuration editor: one described as such, or a page that describes
     * nothing at the path the dashboard has always given the editor to.
     */
    private static boolean isConfigPage(WebPage page) {
        if (page.spec() != null) return page.spec().type() == WebPageSpec.Type.CONFIG;
        return "config".equals(page.path());
    }

    /** An endpoint as a request path: without its query string, or {@code null} when it is not a path. */
    private static String endpointPath(String endpoint) {
        if (endpoint == null) return null;
        String path = endpoint.trim();
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        return path.startsWith("/") ? path : null;
    }

    /** Adds a requirement to a list unless an equal one is already there. */
    private static void addRequirement(List<List<String>> into, List<String> anyOf) {
        if (anyOf.isEmpty() || into.contains(anyOf)) return;
        into.add(List.copyOf(anyOf));
    }

    /** Reads every module's pages and routes again for the permissions they declare. */
    private void rebuildRules() {
        java.util.Map<String, List<List<String>>> endpoints = new java.util.HashMap<>();
        java.util.Map<String, List<List<String>>> reads = new java.util.HashMap<>();
        java.util.Map<String, List<List<String>>> saves = new java.util.HashMap<>();
        // In a stable order: /api/auth lists them as they come.
        java.util.Set<String> nodes = new java.util.LinkedHashSet<>(
                List.of(LOGIN_PERMISSION, PLAYERS_PERMISSION, CONFIG_PERMISSION, CONFIG_SAVE_PERMISSION));
        for (WebModule module : modulesSnapshot()) {
            nodes.add(modulePermission(module.getName()));
            nodes.add(managePermission(module.getName()));
            for (WebPage page : pagesOf(module)) {
                WebPageSpec spec = page.spec();
                if (spec == null) continue;
                spec.declaredPermissions((endpoint, anyOf) -> {
                    String path = endpointPath(endpoint);
                    if (path == null) return;
                    addRequirement(endpoints.computeIfAbsent(path, key -> new ArrayList<>()), anyOf);
                    nodes.addAll(anyOf);
                });
                List<String> read = spec.readPermissions();
                if (!read.isEmpty()) {
                    for (String endpoint : spec.readEndpoints()) {
                        String path = endpointPath(endpoint);
                        if (path == null) continue;
                        addRequirement(reads.computeIfAbsent(trimmed(path), key -> new ArrayList<>()), read);
                    }
                    nodes.addAll(read);
                }
                List<String> save = spec.savePermissions();
                if (!save.isEmpty()) {
                    addRequirement(saves.computeIfAbsent(moduleKey(module.getName()), key -> new ArrayList<>()), save);
                    nodes.addAll(save);
                }
            }
            for (WebRoute route : routesOf(module)) {
                String path = endpointPath(route.path());
                if (path == null) continue;
                List<List<String>> needed = reads.computeIfAbsent(trimmed(path), key -> new ArrayList<>());
                for (List<String> anyOf : route.requirements()) {
                    addRequirement(needed, anyOf);
                    nodes.addAll(anyOf);
                }
            }
            List<String> save = configSaveOf(module);
            if (!save.isEmpty()) {
                addRequirement(saves.computeIfAbsent(moduleKey(module.getName()), key -> new ArrayList<>()), save);
                nodes.addAll(save);
            }
        }
        rules = new Rules(frozen(endpoints), frozen(reads), frozen(saves), java.util.Collections.unmodifiableSet(nodes));
    }

    /** A map of requirements, made unmodifiable all the way down. */
    private static java.util.Map<String, List<List<String>>> frozen(java.util.Map<String, List<List<String>>> map) {
        java.util.Map<String, List<List<String>>> out = new java.util.HashMap<>();
        map.forEach((key, needed) -> {
            if (!needed.isEmpty()) out.put(key, List.copyOf(needed));
        });
        return java.util.Map.copyOf(out);
    }

    /** A module's route declarations, or none when asking for them fails. */
    private List<WebRoute> routesOf(WebModule module) {
        try {
            List<WebRoute> routes = module.getRoutePermissions();
            return routes == null ? List.of() : routes;
        } catch (Throwable e) {
            logger.sendDebug("Web module '" + module.getName() + "' failed to list its route permissions: " + e.getMessage());
            return List.of();
        }
    }

    /** What a module names for saving its configuration, cleaned like a page's declarations; none when asking fails. */
    private List<String> configSaveOf(WebModule module) {
        try {
            List<String> declared = module.getConfigSavePermission();
            if (declared == null) return List.of();
            List<String> out = new ArrayList<>();
            for (String node : declared) {
                if (node == null) continue;
                String clean = node.trim().toLowerCase(java.util.Locale.ROOT);
                if (!clean.isEmpty() && !out.contains(clean)) out.add(clean);
            }
            return out;
        } catch (Throwable e) {
            logger.sendDebug("Web module '" + module.getName() + "' failed to name its save permission: " + e.getMessage());
            return List.of();
        }
    }

    /** A path without the slashes it may end with: {@code /api/x/players/} reaches what {@code /api/x/players} does. */
    private static String trimmed(String path) {
        String out = path;
        while (out.length() > 1 && out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /** Whether a request path is the one declared, a {@code *} standing for any one segment. */
    private static boolean matches(String declared, String path) {
        if (declared.indexOf('*') < 0) return declared.equals(path);
        String[] want = declared.split("/", -1);
        String[] got = path.split("/", -1);
        if (want.length != got.length) return false;
        for (int i = 0; i < want.length; i++) {
            if (want[i].equals("*")) {
                if (got[i].isEmpty()) return false;
            } else if (!want[i].equals(got[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * What reading a path needs, from what the pages and the modules declared: the declarations it
     * matches; or, when it matches none and the route that answers it serves declared paths, all of
     * them at once — another spelling reaches the same handler, and must not ask for less than the
     * spelling that was declared.
     */
    private static List<List<String>> readRequirements(Rules rules, Target target, String path) {
        List<List<String>> needed = new ArrayList<>();
        if (rules.reads().isEmpty()) return needed;
        String clean = trimmed(path);
        if (rules.endpoints().containsKey(path) || rules.endpoints().containsKey(clean)) {
            // A path an action declared is held to what the action declared, whatever the method;
            // a profile's "*" does not reach it. Only what reading its own route needs is added: a
            // list whose handler also takes the action — the watchlist and watchlist/add.
            List<List<String>> exact = rules.reads().get(clean);
            if (exact != null) exact.forEach(anyOf -> addRequirement(needed, anyOf));
            List<List<String>> route = target.context() == null ? null : rules.reads().get(trimmed(target.context()));
            if (route != null) route.forEach(anyOf -> addRequirement(needed, anyOf));
            return needed;
        }
        boolean matched = false;
        for (var entry : rules.reads().entrySet()) {
            if (matches(entry.getKey(), clean)) {
                matched = true;
                entry.getValue().forEach(anyOf -> addRequirement(needed, anyOf));
            }
        }
        if (!matched && target.context() != null) {
            String context = trimmed(target.context());
            for (var entry : rules.reads().entrySet()) {
                if (entry.getKey().startsWith(context)) entry.getValue().forEach(anyOf -> addRequirement(needed, anyOf));
            }
        }
        return needed;
    }

    /**
     * The module that answers a path.
     *
     * <p>The route is found the way the HTTP server finds it — the longest path a module created
     * that the request starts with — so the module that answers is the one whose permission is
     * asked for, wherever its routes live. A route a module created on the server directly is
     * known only by the module's base path, {@code /api/<name>}.</p>
     *
     * @param path The request path.
     * @return The module and its route, or {@code null} when no module answers there.
     */
    private Target targetOf(String path) {
        WebModule found = null;
        String context = null;
        for (var entry : moduleContexts.entrySet()) {
            for (String registered : entry.getValue()) {
                if (path.startsWith(registered) && (context == null || registered.length() > context.length())) {
                    context = registered;
                    found = entry.getKey();
                }
            }
        }
        if (found != null) return new Target(found, context);
        for (WebModule module : modulesSnapshot()) {
            String base = "/api/" + module.getName().toLowerCase();
            if (path.equals(base) || path.startsWith(base + "/")) return new Target(module, null);
        }
        return null;
    }

    /**
     * What a request to a module's route needs.
     *
     * <ul>
     *   <li>Always the module's own permission, {@code xcore.dashboard.<module>}.</li>
     *   <li>At a path a page or the module declared a read permission for — the page's endpoint, a
     *       player's profile — that permission, whatever the method: a handler that answers a GET
     *       answers a POST the same way more often than not. A path an action declared is held to
     *       the action instead.</li>
     *   <li>At an endpoint a page declared a permission for, that permission — whatever the
     *       method, so a handler that would also act on a GET is no open door.</li>
     *   <li>For any other change — a method other than GET or HEAD, or a request carrying a body —
     *       {@code xcore.dashboard.<module>.manage}; and when the route that answers it also serves
     *       declared endpoints, what they need as well: another spelling of a path reaches the same
     *       handler, and must not ask for less than the spelling the page uses.</li>
     * </ul>
     *
     * @param target The module and route that answer.
     * @param path   The request path.
     * @param change Whether the request may change something.
     * @return The requirements, each met by any one of its nodes.
     */
    private List<List<String>> requirementsOf(Target target, String path, boolean change) {
        Rules current = rules;
        List<List<String>> needed = new ArrayList<>();
        needed.add(List.of(modulePermission(target.module().getName())));
        readRequirements(current, target, path).forEach(anyOf -> addRequirement(needed, anyOf));
        List<List<String>> declared = current.endpoints().get(path);
        if (declared == null && target.context() != null) declared = current.endpoints().get(target.context());
        if (declared != null) {
            declared.forEach(anyOf -> addRequirement(needed, anyOf));
            return needed;
        }
        if (change) {
            addRequirement(needed, List.of(managePermission(target.module().getName())));
            if (target.context() != null) {
                String context = target.context();
                current.endpoints().forEach((endpoint, requirements) -> {
                    if (endpoint.startsWith(context)) requirements.forEach(anyOf -> addRequirement(needed, anyOf));
                });
            }
        }
        return needed;
    }

    /**
     * What saving a module's configuration needs.
     *
     * @param requirements The permissions, each met by any one of its nodes.
     * @param operator     Whether only an operator may save it: the module named no permission for it.
     */
    private record SaveRule(List<List<String>> requirements, boolean operator) {}

    /**
     * What saving a module's configuration needs: the module's permission, both of the editor's —
     * {@value #CONFIG_PERMISSION} and {@value #CONFIG_SAVE_PERMISSION} — and the one the module named
     * for it. One that named nothing is saved by an operator or nobody: it used to fall back on
     * {@code xcore.dashboard.<module>.manage}, which {@code xcore.dashboard.*} grants, and a
     * configuration is trusted like the console.
     */
    private SaveRule saveRequirements(String moduleName) {
        List<List<String>> needed = new ArrayList<>();
        needed.add(List.of(modulePermission(moduleName)));
        needed.add(List.of(CONFIG_PERMISSION));
        needed.add(List.of(CONFIG_SAVE_PERMISSION));
        List<List<String>> declared = rules.saves().get(moduleKey(moduleName));
        if (declared == null || declared.isEmpty()) return new SaveRule(needed, true);
        declared.forEach(anyOf -> addRequirement(needed, anyOf));
        return new SaveRule(needed, false);
    }

    /**
     * Whether a session may save a module's configuration.
     *
     * @return {@code null} when it may; otherwise what it misses, as the dashboard shows it.
     */
    private JsonObject saveRefusal(Access access, String moduleName) {
        SaveRule rule = saveRequirements(moduleName);
        List<List<String>> missing = unmetAll(access, rule.requirements());
        boolean operator = rule.operator() && !access.op();
        if (missing.isEmpty() && !operator) return null;
        JsonObject refusal = new JsonObject();
        if (!missing.isEmpty()) {
            refusal.add("needs", strings(missing.get(0)));
            refusal.add("needsAll", requirementsJson(missing));
        }
        if (operator) refusal.addProperty("operator", true);
        return refusal;
    }

    /** @return The first requirement the session does not meet, or {@code null} when it meets them all. */
    private static List<String> unmet(Access access, List<List<String>> requirements) {
        List<List<String>> missing = unmetAll(access, requirements);
        return missing.isEmpty() ? null : missing.get(0);
    }

    /** @return Every requirement the session does not meet, in order; empty when it meets them all. */
    private static List<List<String>> unmetAll(Access access, List<List<String>> requirements) {
        List<List<String>> missing = new ArrayList<>();
        for (List<String> anyOf : requirements) {
            if (anyOf.isEmpty()) continue;
            boolean met = false;
            for (String node : anyOf) {
                if (access.has(node)) {
                    met = true;
                    break;
                }
            }
            if (!met) missing.add(anyOf);
        }
        return missing;
    }

    /** Requirements as JSON: an array of arrays, each met by any one of its nodes. */
    private static JsonArray requirementsJson(List<List<String>> requirements) {
        JsonArray out = new JsonArray();
        for (List<String> anyOf : requirements) out.add(strings(anyOf));
        return out;
    }

    /**
     * Whether a request may change something: any method but GET and HEAD, and a GET that carries
     * a body as well — a handler reading its orders from the body does not look at the method.
     */
    private static boolean isChange(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return true;
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("Transfer-Encoding")) return true;
        String length = headers.getFirst("Content-Length");
        if (length == null) return false;
        try {
            return Long.parseLong(length.trim()) > 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Whether a session may post to an endpoint, by the rules the request itself will meet. */
    private boolean mayPost(Access access, String endpoint) {
        String path = endpointPath(endpoint);
        if (path == null) return true;
        Target target = targetOf(path);
        return target == null || unmet(access, requirementsOf(target, path, true)) == null;
    }

    /**
     * What a session misses to read an endpoint, by the rules the request itself will meet.
     *
     * @return The requirements it does not meet; empty when it may read it.
     */
    private List<List<String>> readMissing(Access access, String endpoint) {
        String path = endpointPath(endpoint);
        if (path == null) return List.of();
        Target target = targetOf(path);
        return target == null ? List.of() : unmetAll(access, requirementsOf(target, path, false));
    }

    /**
     * What a session misses to open a page: the configuration editor's permission, or what reading
     * each endpoint the page is built from needs.
     *
     * @return The requirements it does not meet; empty when it may open the page.
     */
    private List<List<String>> pageMissing(Access access, WebPage page) {
        if (isConfigPage(page)) return access.has(CONFIG_PERMISSION) ? List.of() : List.of(List.of(CONFIG_PERMISSION));
        if (page.spec() == null) return List.of();
        return readMissing(access, page.spec().endpoint());
    }

    /**
     * A page's description as one reader may use it: the row actions, the form and the page-wide
     * button they could not use are left out, and so are the charts and the heat map reading an
     * endpoint they may not read, so the page offers what it can do for them and nothing that
     * would only be refused.
     */
    private JsonObject visibleSpec(WebPageSpec spec, Access access) {
        JsonObject json = spec.toJson();
        if (json.has("actions") && json.get("actions").isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (var action : json.getAsJsonArray("actions")) {
                if (action.isJsonObject() && mayPost(access, stringOf(action.getAsJsonObject(), "endpoint"))) kept.add(action);
            }
            if (kept.size() == 0) json.remove("actions");
            else json.add("actions", kept);
        }
        for (String part : new String[]{"form", "bulk"}) {
            if (json.has(part) && json.get(part).isJsonObject()
                    && !mayPost(access, stringOf(json.getAsJsonObject(part), "endpoint"))) {
                json.remove(part);
            }
        }
        if (json.has("charts") && json.get("charts").isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (var chart : json.getAsJsonArray("charts")) {
                if (chart.isJsonObject() && readable(access, stringOf(chart.getAsJsonObject(), "endpoint"))) kept.add(chart);
            }
            json.remove("chart");
            if (kept.size() == 0) json.remove("charts");
            else {
                json.add("charts", kept);
                json.add("chart", kept.get(0));
            }
        }
        if (json.has("heatmap") && json.get("heatmap").isJsonObject()
                && !readable(access, stringOf(json.getAsJsonObject("heatmap"), "endpoint"))) {
            json.remove("heatmap");
        }
        return json;
    }

    /** Whether a chart's or a heat map's endpoint may be read; an empty one is the page's own. */
    private boolean readable(Access access, String endpoint) {
        return endpoint == null || endpoint.isBlank() || readMissing(access, endpoint).isEmpty();
    }

    private static String stringOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    /**
     * The server as a module sees it: every call goes to the real one, and the paths of the
     * contexts it creates are written down, so that taking the module away takes its routes too.
     * The JDK server has no way to list its contexts.
     */
    private static final class RecordingServer extends HttpServer {

        private final HttpServer delegate;
        private final List<String> paths;

        private RecordingServer(HttpServer delegate, List<String> paths) {
            this.delegate = delegate;
            this.paths = paths;
        }

        @Override
        public void bind(InetSocketAddress address, int backlog) throws IOException {
            delegate.bind(address, backlog);
        }

        @Override public void start() { delegate.start(); }
        @Override public void setExecutor(java.util.concurrent.Executor executor) { delegate.setExecutor(executor); }
        @Override public java.util.concurrent.Executor getExecutor() { return delegate.getExecutor(); }
        @Override public void stop(int delay) { delegate.stop(delay); }
        @Override public InetSocketAddress getAddress() { return delegate.getAddress(); }

        @Override
        public com.sun.net.httpserver.HttpContext createContext(String path, com.sun.net.httpserver.HttpHandler handler) {
            com.sun.net.httpserver.HttpContext context = delegate.createContext(path, handler);
            paths.add(path);
            return context;
        }

        @Override
        public com.sun.net.httpserver.HttpContext createContext(String path) {
            com.sun.net.httpserver.HttpContext context = delegate.createContext(path);
            paths.add(path);
            return context;
        }

        @Override
        public void removeContext(String path) {
            delegate.removeContext(path);
            paths.remove(path);
        }

        @Override
        public void removeContext(com.sun.net.httpserver.HttpContext context) {
            delegate.removeContext(context);
            paths.remove(context.getPath());
        }
    }

    /**
     * Returns an unmodifiable view of the registered web modules.
     *
     * @return The list of registered {@link WebModule} instances.
     */
    public List<WebModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * Returns the underlying HTTP server instance.
     * <p>
     * Useful for modules that need to register additional top-level contexts.
     * </p>
     *
     * @return The HTTP server, or {@code null} if not started.
     */
    public HttpServer getServer() {
        return server;
    }

    // **************************************************************************
    // *                        API Handlers                                    *
    // **************************************************************************

    /**
     * Handles {@code GET /api/modules} — the modules and pages the session may open, as JSON.
     *
     * <p>Each reader gets their own list. A module whose permission they lack is not in it at all;
     * a page whose reading needs a permission they lack is left out — the configuration editor
     * without {@value #CONFIG_PERMISSION} — and the editor is marked {@code readOnly} when saving
     * needs what they lack; a button whose endpoint would refuse them is taken off its page; a
     * module whose player profile they may not read says {@code "profile": false}, so the profile
     * page does not ask it. {@code /api/auth} names what was left out, so a link to it can say "no
     * access" rather than "not found".</p>
     *
     * @param exchange The HTTP exchange.
     * @throws IOException If an I/O error occurs.
     */
    private void handleModules(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        Session session = authenticated(exchange);
        if (session == null) return;

        // Read again here, so a module whose pages changed since it registered is judged by the
        // pages it now has.
        rebuildRules();
        Access access = session.access();
        JsonArray arr = new JsonArray();

        for (WebModule module : modulesSnapshot()) {
            if (!access.has(modulePermission(module.getName()))) continue;
            JsonObject obj = new JsonObject();
            obj.addProperty("name", module.getName());
            obj.addProperty("icon", module.getIcon());
            if (!readMissing(access, profilePath(module)).isEmpty()) obj.addProperty("profile", false);
            JsonArray pages = new JsonArray();
            for (WebPage page : pagesOf(module)) {
                if (!pageMissing(access, page).isEmpty()) continue;
                JsonObject pageObj = new JsonObject();
                pageObj.addProperty("name", page.name());
                pageObj.addProperty("path", page.path());
                pageObj.addProperty("icon", page.icon());
                // The descriptor is what lets the browser build the page without knowing
                // anything about this addon. Absent means "render it generically".
                if (page.spec() != null) pageObj.add("spec", visibleSpec(page.spec(), access));
                if (isConfigPage(page)) {
                    JsonObject refusal = saveRefusal(access, module.getName());
                    if (refusal != null) {
                        pageObj.addProperty("readOnly", true);
                        for (String key : refusal.keySet()) pageObj.add(key, refusal.get(key));
                    }
                }
                pages.add(pageObj);
            }
            obj.add("pages", pages);
            arr.add(obj);
        }

        sendJson(exchange, 200, arr.toString());
    }

    /**
     * Where the player profile asks a module what it knows about a player: a name stands in, any
     * name, since what reading needs does not depend on whose profile it is.
     */
    private static String profilePath(WebModule module) {
        return "/api/" + module.getName().toLowerCase() + "/player/Steve";
    }

    /** A list of strings as a JSON array. */
    private static JsonArray strings(java.util.Collection<String> values) {
        JsonArray arr = new JsonArray();
        for (String value : values) arr.add(value);
        return arr;
    }

    /**
     * Who a session is, as the dashboard needs to know it: whose it is, which of the dashboard's
     * permissions it holds, and what is out of its reach — the modules it may not open, and the
     * pages of the others it may not — so a link there explains itself.
     */
    private JsonObject describe(Session session) {
        Access access = session.access();
        JsonObject me = new JsonObject();
        me.addProperty("status", "ok");
        if (session.ownerId().isBlank()) me.add("player", com.google.gson.JsonNull.INSTANCE);
        else me.addProperty("player", session.owner());

        List<String> held = new ArrayList<>();
        for (String node : rules.nodes()) if (access.has(node)) held.add(node);
        me.add("permissions", strings(held));

        JsonObject core = new JsonObject();
        core.addProperty("players", access.has(PLAYERS_PERMISSION));
        core.addProperty("config", access.has(CONFIG_PERMISSION));
        me.add("access", core);

        JsonArray lockedModules = new JsonArray();
        JsonObject lockedPages = new JsonObject();
        JsonObject lockedNeeds = new JsonObject();
        for (WebModule module : modulesSnapshot()) {
            if (!access.has(modulePermission(module.getName()))) {
                lockedModules.add(module.getName());
                continue;
            }
            JsonArray pages = new JsonArray();
            JsonObject needs = new JsonObject();
            for (WebPage page : pagesOf(module)) {
                List<List<String>> missing = pageMissing(access, page);
                if (missing.isEmpty()) continue;
                pages.add(page.path());
                needs.add(page.path(), requirementsJson(missing));
            }
            if (pages.size() > 0) {
                lockedPages.add(module.getName(), pages);
                lockedNeeds.add(module.getName(), needs);
            }
        }
        JsonObject locked = new JsonObject();
        locked.add("modules", lockedModules);
        locked.add("pages", lockedPages);
        // What each of those pages needs, every requirement it misses: "a and b", each "x or y".
        locked.add("needs", lockedNeeds);
        me.add("locked", locked);
        return me;
    }

    /**
     * Handles {@code GET /api/metrics} — returns server and plugin metrics.
     * Authentication is optional and controlled by the metrics-public setting.
     *
     * @param exchange The HTTP exchange.
     * @throws IOException If an I/O error occurs.
     */
    /** Item textures already fetched, kept so a mirror is asked once per material per boot. */
    private final java.util.Map<String, byte[]> spriteCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How many names a cache of misses remembers. The names come from anonymous requests — an
     * {@code <img>} carries no token — so the set they used to go into grew with every one ever
     * asked for.
     */
    private static final int MISS_CACHE_SIZE = 1024;

    /** Materials no mirror had a texture for, and until when they are not asked for again. */
    private final java.util.Map<String, Long> spriteMisses = missCache();

    /** Players the face source had nothing for, and until when they are not asked for again. */
    private final java.util.Map<String, Long> headMisses = missCache();

    /** A map of misses that forgets the oldest past {@link #MISS_CACHE_SIZE}. */
    private static java.util.Map<String, Long> missCache() {
        return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Long>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                return size() > MISS_CACHE_SIZE;
            }
        });
    }

    /** Whether a name missed recently enough not to be asked for again. */
    private static boolean missed(java.util.Map<String, Long> misses, String key) {
        Long until = misses.get(key);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        misses.remove(key);
        return false;
    }

    /** Requests one address may make for faces and textures in a minute: a page shows a few dozen, which the browser then keeps. */
    private static final int IMAGE_LIMIT = 300;

    /** Of those, how many may send the server out for something it does not hold yet. */
    private static final int IMAGE_FETCH_LIMIT = 60;

    /** Per-IP timestamps of recent image requests. */
    private final java.util.Map<String, java.util.Deque<Long>> imageHits = new java.util.concurrent.ConcurrentHashMap<>();

    /** Per-IP timestamps of recent image requests that started a fetch. */
    private final java.util.Map<String, java.util.Deque<Long>> imageFetches = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Where faces and textures are fetched from the internet: two at a time, sixteen waiting at
     * most, so no number of requests ties up more than that. They used to be fetched on the
     * panel's own four HTTP threads, each waiting up to eight seconds on a slow source while the
     * dashboard's pages queued behind it.
     */
    private java.util.concurrent.ThreadPoolExecutor assetExecutor;

    /** How long a request waits for its image before being answered without it; the fetch goes on, for the next view. */
    private static final long ASSET_WAIT_MS = 750;

    /** Fetches under way, so a page asking ten times for one face starts one fetch. */
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<byte[]>> assetFetches =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Until when no texture is fetched at all: the mirror could not be reached, or asked to slow down. */
    private volatile long spritesOfflineUntil;

    /** How long a source that could not be reached, or asked to slow down, is left alone. */
    private static final long SOURCE_BACKOFF_MS = 60_000L;

    /**
     * Counts an image request against its address.
     *
     * @return {@code true} when it may go on; a 429 has been sent otherwise.
     */
    private boolean imageLimit(HttpExchange exchange) throws IOException {
        String ip = clientIp(exchange);
        if (!allow(imageHits, ip, IMAGE_LIMIT)) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
            return false;
        }
        record(imageHits, ip);
        return true;
    }

    /** Whether the request's address may still send the server out for an image this minute; counts it when it may. */
    private boolean mayFetch(HttpExchange exchange) {
        String ip = clientIp(exchange);
        if (!allow(imageFetches, ip, IMAGE_FETCH_LIMIT)) return false;
        record(imageFetches, ip);
        return true;
    }

    /**
     * Fetches an image off the HTTP thread, sharing a fetch already under way for the same key,
     * and waits for it {@value #ASSET_WAIT_MS} ms at most.
     *
     * @param key   What is fetched, for sharing.
     * @param fetch The fetch; it fills the caches itself, so one that finishes after the wait still
     *              serves the next request.
     * @return The image, or {@code null} when it is not there yet, there is none, or the queue is full.
     */
    private byte[] awaitAsset(String key, java.util.function.Supplier<byte[]> fetch) {
        java.util.concurrent.ThreadPoolExecutor executor = assetExecutor;
        if (executor == null) return null;
        java.util.concurrent.CompletableFuture<byte[]> created = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<byte[]> pending = assetFetches.putIfAbsent(key, created);
        if (pending == null) {
            pending = created;
            try {
                executor.execute(() -> {
                    try {
                        created.complete(fetch.get());
                    } catch (Throwable e) {
                        created.complete(null);
                    } finally {
                        assetFetches.remove(key, created);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException full) {
                assetFetches.remove(key, created);
                return null;
            }
        }
        try {
            return pending.get(ASSET_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Answers an image request without the image: the dashboard draws its stand-in. Never cached,
     * so the browser asks again on the next view — by then the fetch may have landed.
     */
    private static void sendNoImage(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    /** A remote file as fetched: its status, -1 when the source could not be reached, and its bytes on a 200. */
    private record Fetched(int status, byte[] data) {}

    /** Reads a remote image. */
    private Fetched fetchImage(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "XCore-Dashboard");
            int status = conn.getResponseCode();
            if (status != 200) return new Fetched(status, null);
            try (java.io.InputStream in = conn.getInputStream()) {
                // A face or a texture is a few kilobytes; a source sending more is not sending one.
                byte[] data = in.readNBytes(1 << 20);
                return new Fetched(200, data.length > 0 ? data : null);
            }
        } catch (Exception e) {
            logger.sendDebug("Could not fetch " + url + ": " + e.getMessage());
            return new Fetched(-1, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Whether a source's answer means it is struggling or unreachable rather than missing one file. */
    private static boolean sourceTrouble(int status) {
        return status == -1 || status == 429 || status >= 500;
    }

    /**
     * Where item textures are fetched from. Items and blocks live apart on the vanilla mirror, so
     * both folders are tried.
     */
    private static final String[] SPRITE_SOURCES = {
        "https://assets.mcasset.cloud/%1$s/assets/minecraft/textures/item/%2$s.png",
        "https://assets.mcasset.cloud/%1$s/assets/minecraft/textures/block/%2$s.png"
    };

    /**
     * The Minecraft version whose assets match this server.
     *
     * <p>Pinning a version is how a texture goes missing: an item added after it simply is not
     * there, and the dashboard shows a name with no icon while the mirror answers 404 for a file
     * that exists one version later.</p>
     */
    private String assetVersion() {
        try {
            String version = Bukkit.getMinecraftVersion();
            if (version != null && version.matches("[0-9][0-9a-zA-Z.\\-]{0,15}")) return version;
        } catch (Throwable ignored) {
            // Older API: fall through.
        }
        try {
            String bukkit = Bukkit.getBukkitVersion();
            if (bukkit != null && bukkit.contains("-")) {
                String version = bukkit.substring(0, bukkit.indexOf('-'));
                if (version.matches("[0-9][0-9a-zA-Z.]{0,15}")) return version;
            }
        } catch (Throwable ignored) {
        }
        return "1.21.4";
    }

    /**
     * Handles {@code GET /api/sprite/<material>.png} — an item texture, served from here.
     *
     * <p>The dashboard used to point its images straight at a public mirror. That works until it
     * does not: a mirror answers a command-line request and refuses the browser's, an extension
     * blocks the domain, a DNS filter swallows it — and the page shows nothing with no way to tell
     * why. Fetching server-side removes every one of those failure modes at once: the browser only
     * ever talks to the server it is already talking to, and the texture is fetched once for the
     * whole dashboard rather than once per visitor.</p>
     */
    private void handleSprite(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!imageLimit(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);

        // The name is pasted into a URL: anything but a material name is refused outright.
        if (!name.matches("[a-z0-9_]{1,64}")) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        String material = name;
        byte[] data = spriteCache.get(material);
        if (data == null && !missed(spriteMisses, material)
                && System.currentTimeMillis() >= spritesOfflineUntil && mayFetch(exchange)) {
            data = awaitAsset("sprite:" + material, () -> fetchSprite(material));
        }

        if (data == null) {
            sendNoImage(exchange);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        // A day rather than a week: a texture is stable under a given name, but a Minecraft
        // upgrade redraws some of them and a week-old icon would outlive the server it describes.
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /** How many faces are kept in memory: the players a dashboard shows, not every one ever seen. */
    private static final int HEAD_CACHE_SIZE = 512;

    /** Player faces already fetched, the least recently shown dropped first. */
    private final java.util.Map<String, byte[]> headCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, byte[]>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, byte[]> eldest) {
                    return size() > HEAD_CACHE_SIZE;
                }
            });

    /**
     * Until when faces are not fetched at all: the source could not be reached, or asked to slow
     * down.
     *
     * <p>A server with no way out to the internet would otherwise spend a connection timeout on
     * every face of every table. The dashboard draws a monogram in the meantime. A face the source
     * simply does not have is remembered for that name alone: it used to turn every face off, for
     * everyone, for five minutes.</p>
     */
    private volatile long headsOfflineUntil;

    /**
     * Handles {@code GET /api/head/<name>.png} — a player's face, served from here.
     *
     * <p>The dashboard used to point its images straight at a public site: every reader's browser
     * told a third party which players were being looked at, and a blocked or offline site left
     * broken images. As with {@link #handleSprite}, the server fetches each face once and the
     * browser only ever talks to the server it is already talking to.</p>
     *
     * <p>Open without a session — an {@code <img>} sends no token — so it is held to a limit per
     * address, and of its requests only some may send the server out for a face it does not have,
     * on {@link #assetExecutor}, never on the panel's own threads.</p>
     */
    private void handleHead(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!imageLimit(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);

        // The name is pasted into a URL: anything but a player name is refused outright.
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        String player = name;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        byte[] data = headCache.get(key);
        if (data == null && !missed(headMisses, key)
                && System.currentTimeMillis() >= headsOfflineUntil && mayFetch(exchange)) {
            data = awaitAsset("head:" + key, () -> fetchHead(player, key));
        }

        if (data == null) {
            sendNoImage(exchange);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        // An hour: a skin changes now and then, and the face should follow without a restart.
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /**
     * Fetches a player's face into the cache. Runs on {@link #assetExecutor}.
     *
     * @param name The name as asked for.
     * @param key  The same in lower case, as it is cached.
     * @return The face, or {@code null} when there is none to be had now.
     */
    private byte[] fetchHead(String name, String key) {
        Fetched fetched = fetchImage("https://mc-heads.net/avatar/" + name + "/64");
        if (fetched.data() != null) {
            headCache.put(key, fetched.data());
            return fetched.data();
        }
        long now = System.currentTimeMillis();
        if (sourceTrouble(fetched.status())) {
            headsOfflineUntil = now + SOURCE_BACKOFF_MS;
            headMisses.put(key, now + 5 * 60_000L);
        } else {
            // The source answered, and has nothing for this name.
            headMisses.put(key, now + 30 * 60_000L);
        }
        return null;
    }

    /** Vanilla translations, fetched once per language and kept for the process. */
    private final java.util.Map<String, String> minecraftLangCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Handles {@code GET /api/mclang} — Minecraft's own translations, in the dashboard's language.
     *
     * <p>Items carry a translation key rather than a name, which is what lets one server show
     * "Diamond Sword" to one reader and "Épée en diamant" to another. Resolving it needs the game's
     * language file, which the dashboard has no business bundling — it would go stale at every
     * Minecraft release. Fetched once, cached, and served from here.</p>
     */
    private void handleMinecraftLang(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;

        // The reader's own game language first — the token remembers whose link this is — and the
        // server's configured language only when there is nothing better.
        Session session = sessionOf(exchange);
        String locale = (session != null && session.locale() != null && !session.locale().isBlank())
                ? session.locale().replace('-', '_')
                : null;

        String code = main.getLanguage() == null ? "en" : main.getLanguage().toLowerCase(java.util.Locale.ROOT);
        if (locale == null) locale = switch (code) {
            case "fr" -> "fr_fr";
            case "es" -> "es_es";
            case "de" -> "de_de";
            case "it" -> "it_it";
            case "pt" -> "pt_br";
            case "nl" -> "nl_nl";
            case "pl" -> "pl_pl";
            case "ru" -> "ru_ru";
            default -> "en_us";
        };

        if (!locale.matches("[a-z]{2,3}_[a-z]{2,3}")) locale = "en_us";

        String json = minecraftLangCache.get(locale);
        if (json == null) {
            String base = "https://assets.mcasset.cloud/" + assetVersion() + "/assets/minecraft/lang/";
            json = fetchText(base + locale + ".json");
            if (json == null && !"en_us".equals(locale)) json = fetchText(base + "en_us.json");
            if (json == null) json = "{}";
            minecraftLangCache.put(locale, json);
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // Short on purpose: this file changes when the server changes Minecraft version and when
        // the reader changes language. Cached for a week, a dashboard kept showing the previous
        // version's names long after the server had moved on — served from memory, so cheap.
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
        byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /** Reads a remote text resource, or {@code null} when it cannot be had. */
    private String fetchText(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "XCore-Dashboard");
            if (conn.getResponseCode() != 200) return null;
            try (java.io.InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            logger.sendDebug("Could not fetch " + url + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Tries each mirror in turn and caches what it finds; {@code null} when none has that texture
     * now. Runs on {@link #assetExecutor}.
     *
     * <p>A material no mirror has is not asked for again for six hours; one that could not be
     * fetched because the mirror was unreachable, for five minutes — it used to be remembered as
     * missing until the next restart.</p>
     */
    private byte[] fetchSprite(String name) {
        long now = System.currentTimeMillis();
        for (String source : SPRITE_SOURCES) {
            Fetched fetched = fetchImage(String.format(source, assetVersion(), name));
            if (fetched.data() != null) {
                spriteCache.put(name, fetched.data());
                return fetched.data();
            }
            if (sourceTrouble(fetched.status())) {
                spritesOfflineUntil = now + SOURCE_BACKOFF_MS;
                spriteMisses.put(name, now + 5 * 60_000L);
                return null;
            }
        }
        logger.sendDebug("No mirror had a texture for '" + name + "'.");
        spriteMisses.put(name, now + 6 * 3_600_000L);
        return null;
    }

    /**
     * Handles {@code GET /api/lang} — the dashboard's UI strings.
     *
     * <p>Deliberately unauthenticated: the login screen needs its own labels before there is any
     * token to check. The payload is a map of interface labels — it discloses nothing about the
     * server — and it is still rate limited like every other endpoint.</p>
     */
    private void handleLang(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        sendJson(exchange, 200, stringsJson);
    }

    /**
     * Handles {@code GET /api/auth} — validates the token, and says what the session may do.
     *
     * <p>{@code {"status":"ok", "player", "permissions", "access":{"players","config"},
     * "locked":{"modules":[…], "pages":{"<module>":[…]}, "needs":{"<module>":{"<page>":[[…]]}}}}}.
     * Always requires authentication.</p>
     */
    private void handleAuth(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        Session session = authenticated(exchange);
        if (session == null) return;
        sendJson(exchange, 200, describe(session).toString());
    }

    /**
     * Handles {@code GET /api/players} — returns player list from database.
     */
    /** Whether the players table carries last_login, resolved once rather than per request. */
    private volatile Boolean hasLastLoginColumn;

    /**
     * Looks for the {@code last_login} column, which an addon registers rather than the core.
     *
     * <p>Reading the database metadata is a query of its own, and this was doing it on every
     * dashboard refresh. A column that appears while the server runs is picked up on the next
     * restart, which is when it was added anyway.</p>
     *
     * @param conn An open connection.
     * @return Whether the column exists.
     */
    private boolean hasLastLoginColumn(java.sql.Connection conn) {
        Boolean cached = hasLastLoginColumn;
        if (cached != null) return cached;
        boolean found = false;
        try (var rs = conn.getMetaData().getColumns(null, null, "players", "last_login")) {
            found = rs.next();
        } catch (Exception ignored) {
            // Metadata unavailable: fall back to the join date, as before.
        }
        hasLastLoginColumn = found;
        return found;
    }

    private void handlePlayers(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        if (!authorize(exchange, List.of(List.of(PLAYERS_PERMISSION)))) return;

        String query = exchange.getRequestURI().getQuery();
        int offset = 0, limit = 100, page = 0;
        String search = null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length != 2) continue;
                switch (kv[0]) {
                    case "offset" -> { try { offset = Math.max(0, Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) {} }
                    case "page" -> { try { page = Math.max(1, Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) {} }
                    case "limit" -> { try { limit = Math.clamp(Integer.parseInt(kv[1]), 1, 500); } catch (NumberFormatException ignored) {} }
                    case "search" -> search = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
        }
        if (page > 0) offset = (page - 1) * limit;

        boolean filtered = search != null && !search.isBlank();
        String where = filtered ? " WHERE player_name LIKE ?" : "";
        JsonArray players = new JsonArray();
        JsonObject result = new JsonObject();

        // The total is only needed to draw a pager. The overview widget asks for five rows every
        // five seconds and has no pager, so counting there means a full scan of the table twice a
        // second for a number nobody reads.
        boolean wantsTotal = page > 0 || limit >= 25;

        try (var conn = fr.xyness.XCore.API.XCoreApiProvider.get().getDataSource().getConnection()) {
            if (wantsTotal) {
                try (var count = conn.prepareStatement("SELECT COUNT(*) FROM players" + where)) {
                    if (filtered) count.setString(1, "%" + search + "%");
                    try (var rs = count.executeQuery()) {
                        if (rs.next()) result.addProperty("total", rs.getInt(1));
                    }
                }
            }
            boolean hasLastLogin = hasLastLoginColumn(conn);

            String columns = "player_name, server_uuid, mojang_uuid, created_at"
                    + (hasLastLogin ? ", last_login" : "");
            String order = hasLastLogin ? " ORDER BY last_login DESC" : " ORDER BY created_at DESC";
            try (var ps = conn.prepareStatement("SELECT " + columns + " FROM players" + where + order
                    + " LIMIT ? OFFSET ?")) {
                int idx = 1;
                if (filtered) ps.setString(idx++, "%" + search + "%");
                ps.setInt(idx++, limit);
                ps.setInt(idx, offset);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject p = new JsonObject();
                        p.addProperty("player_name", rs.getString("player_name"));
                        p.addProperty("server_uuid", rs.getString("server_uuid"));
                        p.addProperty("mojang_uuid", rs.getString("mojang_uuid"));
                        p.addProperty("registered", rs.getString("created_at"));
                        if (hasLastLogin) p.addProperty("last_login", rs.getString("last_login"));
                        players.add(p);
                    }
                }
            }
        } catch (Exception e) {
            logger.sendWarning("Failed to load players for the dashboard: " + e.getMessage());
        }

        result.add("players", players);
        sendJson(exchange, 200, result.toString());
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        if (!metricsPublic && !authenticate(exchange)) return;

        // Everything that comes from Bukkit is read from a snapshot refreshed on the server thread:
        // this handler runs on an HTTP thread, where touching the world or the player list is at
        // best undefined and, under Folia, an outright thread-check failure.
        ServerSnapshot snapshot = this.snapshot;

        JsonObject metrics = new JsonObject();
        metrics.addProperty("uptime_seconds", (System.currentTimeMillis() - startTimeMillis) / 1000);
        metrics.addProperty("players_online", snapshot.online());
        metrics.addProperty("players_max", snapshot.max());
        metrics.addProperty("server_name", main.getServerName());

        int modulesCount;
        synchronized (modules) {
            modulesCount = modules.size();
        }
        metrics.addProperty("modules_count", modulesCount);

        if (snapshot.tps() > 0) {
            metrics.addProperty("tps", Math.round(snapshot.tps() * 100.0) / 100.0);
        }

        // Everything above is safe to publish; what follows describes the installation, so it is
        // only served to a caller that authenticated — even when metrics-public is on.
        if (isAuthenticated(exchange)) {
            metrics.addProperty("xcore_version", main.getPluginMeta().getVersion());
            metrics.addProperty("server_software", Bukkit.getVersion());
            metrics.addProperty("java_version", Runtime.version().toString());
            metrics.addProperty("database", main.getDatabaseType().name());

            Runtime runtime = Runtime.getRuntime();
            metrics.addProperty("memory_used", runtime.totalMemory() - runtime.freeMemory());
            metrics.addProperty("memory_max", runtime.maxMemory());

            // Addons and plugins built on XCore alike: the dashboard counts what runs on the core.
            List<fr.xyness.XCore.Extension.XExtension> extensions = main.extensions().all();
            long enabled = extensions.stream()
                    .filter(extension -> extension.state() == fr.xyness.XCore.Addon.AddonState.ENABLED)
                    .count();
            metrics.addProperty("addons_total", extensions.size());
            metrics.addProperty("addons_enabled", enabled);

            metrics.addProperty("sync_running", main.getSyncManager() != null && main.getSyncManager().isRunning());
            metrics.addProperty("redis", main.getJedisPool() != null);
            metrics.addProperty("cache_hit_rate", Math.round(main.playerCache().getL1HitRate() * 1000.0) / 10.0);
            metrics.addProperty("cache_size", main.playerCache().getL1Size());
            metrics.addProperty("worlds", snapshot.worlds());

            JsonArray online = new JsonArray();
            for (String name : snapshot.names()) online.add(name);
            metrics.add("online_players", online);
            metrics.addProperty("sessions", getSessionCount());
        }

        sendJson(exchange, 200, metrics.toString());
    }

    /**
     * Whether the request carries a valid credential, without answering it.
     *
     * <p>{@link #authenticate(HttpExchange)} refuses the request on failure, which is wrong for an
     * endpoint that has something to say to anonymous callers too.</p>
     */
    private boolean isAuthenticated(HttpExchange exchange) {
        return liveSession(exchange) != null;
    }

    // **************************************************************************
    // *                       Static File Handler                              *
    // **************************************************************************

    /**
     * Serves static files from the {@code plugins/XCore/web/} directory.
     * Falls back to {@code index.html} for SPA routing when no matching file is found.
     *
     * @param exchange The HTTP exchange.
     * @throws IOException If an I/O error occurs.
     */
    private void handleStatic(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath.equals("/")) requestPath = "/index.html";

        // Resolve file path safely (prevent directory traversal)
        Path filePath = webRoot.resolve(requestPath.substring(1)).normalize();
        if (!filePath.startsWith(webRoot)) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        // If file does not exist, fall back to index.html for SPA routing
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            filePath = webRoot.resolve("index.html");
        }

        if (!Files.exists(filePath)) {
            String notFound = "404 Not Found";
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(404, notFound.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFound.getBytes());
            }
            return;
        }

        String contentType = guessContentType(filePath.toString());
        exchange.getResponseHeaders().set("Content-Type", contentType);

        // Held in memory, keyed by modification time, so a page load reads nothing from disk; an
        // edited file is picked up on its next request.
        String key = filePath.toString();
        long modified = Files.getLastModifiedTime(filePath).toMillis();
        StaticFile file = staticCache.get(key);
        if (file == null || file.modified() != modified) {
            byte[] bytes = Files.readAllBytes(filePath);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes);
            file = new StaticFile(bytes, null,
                    "W/\"" + Long.toHexString(crc.getValue()) + "-" + Integer.toHexString(bytes.length) + "\"", modified);
            staticCache.put(key, file);
        }

        // The dashboard is often reached across the internet, and is a dozen files: downloading all
        // of them on every visit is what made it slow to open, and caching them for a while is how
        // an updated dashboard used to look unchanged after a restart. So the browser keeps them
        // but asks every time whether they changed, and an unchanged file costs a bodiless 304.
        // A font never changes under its name — a new one gets a new file — so it is not even asked.
        boolean immutable = filePath.startsWith(webRoot.resolve("fonts"));
        exchange.getResponseHeaders().set("Cache-Control", immutable ? "public, max-age=31536000, immutable" : "no-cache");
        exchange.getResponseHeaders().set("ETag", file.etag());
        String presented = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (presented != null && etagMatches(presented, file.etag())) {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }

        byte[] data = file.data();
        boolean compress = data.length > 1024 && acceptsGzip(exchange) && isCompressible(contentType);
        if (compress) {
            byte[] gzipped = file.gzip();
            if (gzipped == null) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(data.length / 4);
                try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(buffer)) {
                    gzip.write(data);
                }
                gzipped = buffer.toByteArray();
                staticCache.put(key, new StaticFile(file.data(), gzipped, file.etag(), file.modified()));
            }
            data = gzipped;
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
        }

        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /**
     * Whether an {@code If-None-Match} header names this validator. The comparison is the weak
     * one the header calls for: the same file is sent gzipped or not under one validator.
     */
    private static boolean etagMatches(String header, String etag) {
        String bare = etag.startsWith("W/") ? etag.substring(2) : etag;
        for (String candidate : header.split(",")) {
            String value = candidate.trim();
            if (value.equals("*")) return true;
            if (value.startsWith("W/")) value = value.substring(2);
            if (value.equals(bare)) return true;
        }
        return false;
    }

    /** @return Whether gzip is worth applying to this content type (images already are compressed). */
    private static boolean isCompressible(String contentType) {
        return contentType.startsWith("text/")
                || contentType.startsWith("application/javascript")
                || contentType.startsWith("application/json")
                || contentType.startsWith("image/svg");
    }

    // **************************************************************************
    // *                          Helper Methods                                *
    // **************************************************************************

    /**
     * Checks the {@code Authorization} header for a valid Bearer token, and that the session may
     * call the route. Sends a 401 response and returns {@code false} if authentication fails, a 403
     * if the session's owner lacks a permission the route needs.
     * <p>
     * This method is public so that registered {@link WebModule} implementations
     * can reuse the same authentication logic on their own route handlers — and so that every
     * route of every module is held to the reader's permissions, modules written before they
     * existed included: a route of a module needs {@code xcore.dashboard.<module>}, and a change
     * needs what the page declared for it, or {@code xcore.dashboard.<module>.manage}.
     * </p>
     *
     * @param exchange The HTTP exchange.
     * @return {@code true} if authenticated and allowed, {@code false} otherwise.
     * @throws IOException If an I/O error occurs while sending the error response.
     */
    public boolean authenticate(HttpExchange exchange) throws IOException {
        Session session = authenticated(exchange);
        if (session == null) return false;
        String path = exchange.getRequestURI().getPath();
        Target target = path == null ? null : targetOf(path);
        // A route no module answers for needs no more than a session.
        if (target == null) return true;
        return permitted(exchange, session, requirementsOf(target, path, isChange(exchange)));
    }

    /**
     * For the core's own routes: a session, holding what the route needs.
     *
     * @param exchange     The HTTP exchange.
     * @param requirements What it needs, each met by any one of its nodes.
     * @return {@code true} when the request may go on; a 401, 403 or 429 has been sent otherwise.
     */
    private boolean authorize(HttpExchange exchange, List<List<String>> requirements) throws IOException {
        Session session = authenticated(exchange);
        return session != null && permitted(exchange, session, requirements);
    }

    /**
     * The session the request carries, or {@code null} once a 401 or a 429 has been sent.
     */
    private Session authenticated(HttpExchange exchange) throws IOException {
        String ip = clientIp(exchange);

        // A live session is let in whatever its address has been doing. The count below is kept per
        // address, and behind a reverse proxy every reader has the proxy's: ten bad requests a
        // minute from anybody used to shut every administrator out of the dashboard.
        Session session = liveSession(exchange);
        if (session != null) {
            authAttempts.remove(ip);
            return session;
        }

        // A wrong token costs an attempt: ten failures inside a minute and the source is told to
        // slow down instead of being answered.
        if (!allow(authAttempts, ip, AUTH_LIMIT)) {
            sendJson(exchange, 429, "{\"error\":\"Too many authentication attempts\"}");
            return null;
        }
        record(authAttempts, ip);
        sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
        return null;
    }

    /**
     * Lets the request through when the session meets every requirement; answers 403 otherwise,
     * naming the permission that is missing — the dashboard shows it to the reader, who can then
     * ask for it by name.
     */
    private boolean permitted(HttpExchange exchange, Session session, List<List<String>> requirements) throws IOException {
        List<String> missing = unmet(session.access(), requirements);
        if (missing == null) return true;
        JsonObject body = new JsonObject();
        body.addProperty("error", "Missing permission: " + String.join(" or ", missing));
        body.addProperty("permission", missing.get(0));
        body.add("anyOf", strings(missing));
        sendJson(exchange, 403, body.toString());
        return false;
    }

    /**
     * The player behind a request: whoever opened the session it carries.
     *
     * <p>For a module that records who did something. An action taken from the dashboard used to be
     * written down as done by "WebPanel", whoever was sitting at it.</p>
     *
     * @param exchange The HTTP exchange, already authenticated.
     * @return The player's name, or {@code null} when the request carries no live session.
     */
    public String sessionOwner(HttpExchange exchange) {
        Session session = liveSession(exchange);
        return session == null ? null : session.owner();
    }

    /**
     * The session behind a request, or {@code null} when there is none.
     *
     * <p>Used to answer in the reader's own language rather than the server's.</p>
     */
    private Session sessionOf(HttpExchange exchange) {
        return liveSession(exchange);
    }

    /**
     * The live session a request carries: known, not expired, its owner's permissions read in game
     * recently enough, and owned by someone who still holds {@value #LOGIN_PERMISSION}. One that
     * fails the first two is dropped on the way.
     */
    private Session liveSession(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String presented = auth.substring(7);
        if (presented.isEmpty()) return null;
        Session session = sessions.get(hash(presented));
        if (session == null) return null;
        long now = System.currentTimeMillis();
        if (session.expiresAt() < now || now - session.readAt() > offlineTrustMillis) {
            pruneSessions();
            saveSessions();
            return null;
        }
        return session.access().has(LOGIN_PERMISSION) ? session : null;
    }

    /**
     * Applies the shared per-IP request limit. Modules may call it before their own handling.
     *
     * @param exchange The HTTP exchange.
     * @return {@code true} when the request may proceed; a 429 has already been sent otherwise.
     * @throws IOException If an I/O error occurs.
     */
    public boolean rateLimit(HttpExchange exchange) throws IOException {
        String ip = clientIp(exchange);
        if (!allow(requestHits, ip, REQUEST_LIMIT)) {
            sendJson(exchange, 429, "{\"error\":\"Rate limit exceeded\"}");
            return false;
        }
        record(requestHits, ip);
        return true;
    }

    private static String clientIp(HttpExchange exchange) {
        var addr = exchange.getRemoteAddress();
        return (addr == null || addr.getAddress() == null) ? "unknown" : addr.getAddress().getHostAddress();
    }

    /**
     * Whether the source is under its quota, dropping anything older than the window.
     *
     * <p>The empty deque is left in the map rather than removed. Removing it here raced with
     * {@link #record}: that method takes the deque out of the map, then locks it — so a removal in
     * between left it adding to a deque nobody would read again, and the hit vanished. Under
     * parallel requests the limiter therefore counted fewer attempts than were actually made, which
     * is the one direction a rate limiter must never be wrong in. {@link #pruneRateLimits} empties
     * the map instead, on a timer, where nothing is holding a reference.</p>
     */
    private static boolean allow(java.util.Map<String, java.util.Deque<Long>> map, String ip, int limit) {
        java.util.Deque<Long> hits = map.get(ip);
        if (hits == null) return true;
        long cutoff = System.currentTimeMillis() - AUTH_WINDOW_MS;
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst() < cutoff) hits.pollFirst();
            return hits.size() < limit;
        }
    }

    private static void record(java.util.Map<String, java.util.Deque<Long>> map, String ip) {
        java.util.Deque<Long> hits = map.computeIfAbsent(ip, k -> new java.util.ArrayDeque<>());
        synchronized (hits) { hits.addLast(System.currentTimeMillis()); }
    }

    /**
     * Forgets the addresses that have gone quiet.
     *
     * <p>Without this the two maps only ever grew: an entry was created for every address that ever
     * reached the panel, and dropped only if that same address came back after its window had
     * expired. A dashboard reachable from the internet meets a steady supply of addresses that call
     * once and never return — scanners — so the map was an unbounded record of everyone who had
     * ever knocked.</p>
     */
    private void pruneRateLimits() {
        long cutoff = System.currentTimeMillis() - AUTH_WINDOW_MS;
        for (java.util.Map<String, java.util.Deque<Long>> map : java.util.List.of(authAttempts, requestHits, imageHits, imageFetches)) {
            // computeIfPresent, not removeIf: it holds the map's lock for that key while the
            // function runs, so a concurrent record() — which reaches the same key through
            // computeIfAbsent — waits rather than adding a hit to a deque this is about to drop.
            for (String ip : new java.util.ArrayList<>(map.keySet())) {
                map.computeIfPresent(ip, (key, hits) -> {
                    synchronized (hits) {
                        while (!hits.isEmpty() && hits.peekFirst() < cutoff) hits.pollFirst();
                        return hits.isEmpty() ? null : hits;
                    }
                });
            }
        }
    }

    /**
     * Adds CORS headers to the response, and the {@link #addSecurityHeaders security headers}.
     * <p>
     * Public so that {@link WebModule} implementations can apply the same headers.
     * </p>
     *
     * @param exchange The HTTP exchange.
     */
    public void addCorsHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        if (corsOrigins == null) {
            headers.set("Access-Control-Allow-Origin", corsOrigin);
            if (!"*".equals(corsOrigin)) headers.set("Vary", "Origin");
        } else {
            // Several origins allowed: the caller's own is named back when it is on the list,
            // compared exactly. Any other gets no header at all, and its browser withholds the
            // answer from the page that asked. The answer depends on Origin either way, so a cache
            // must not hand one origin's response to another.
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && corsOrigins.contains(origin.trim())) {
                headers.set("Access-Control-Allow-Origin", origin.trim());
            }
            headers.set("Vary", "Origin");
        }
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        // Here because every handler — the core's and every module's — starts with this call, so
        // it is the one place sure to reach every response, modules built before it existed included.
        addSecurityHeaders(exchange);
    }

    /**
     * Forbids framing and content sniffing on a response.
     *
     * <p>The dashboard bans, mutes and rewrites configuration files with one click each. Shown
     * inside an invisible frame on someone else's page, those clicks can be stolen from a logged-in
     * administrator (clickjacking): {@code X-Frame-Options} and {@code frame-ancestors} keep every
     * browser from framing it. {@code nosniff} keeps a browser from second-guessing a declared type
     * — a JSON answer quoting player-written text is never run as a script or rendered as a page.</p>
     *
     * <p>Applied by {@link #addCorsHeaders} and {@link #sendJson}; public for a module that writes
     * its responses by hand.</p>
     *
     * @param exchange The HTTP exchange.
     */
    public void addSecurityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "frame-ancestors 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
    }

    /**
     * Handles CORS preflight (OPTIONS) requests.
     * <p>
     * Public so that {@link WebModule} implementations can reuse preflight handling.
     * </p>
     *
     * @param exchange The HTTP exchange.
     * @return {@code true} if this was a preflight request (already handled), {@code false} otherwise.
     * @throws IOException If an I/O error occurs.
     */
    public boolean handlePreflight(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    /**
     * Sends a JSON response with the given status code and body.
     * <p>
     * Public so that {@link WebModule} implementations can send JSON responses
     * using the same format and content-type header.
     * </p>
     *
     * @param exchange The HTTP exchange.
     * @param code     The HTTP status code.
     * @param json     The JSON string to send.
     * @throws IOException If an I/O error occurs.
     */
    public void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        // Again here for a module handler that answers without calling addCorsHeaders first.
        addSecurityHeaders(exchange);

        // Compressed past a kilobyte, and only when the caller said it could read it. The payloads
        // that matter here — a sanction list, a player page, a catalogue — are highly repetitive
        // JSON, which gzip takes down by an order of magnitude. Below a kilobyte the header costs
        // more than the saving.
        if (data.length > 1024 && acceptsGzip(exchange)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(data.length / 4);
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(buffer)) {
                gzip.write(data);
            }
            byte[] compressed = buffer.toByteArray();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
            exchange.sendResponseHeaders(code, compressed.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(compressed);
            }
            return;
        }

        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /** @return Whether the request advertised gzip support. */
    private static boolean acceptsGzip(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return header != null && header.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }

    // **************************************************************************
    // *                       Addon configuration editor                       *
    // **************************************************************************

    /**
     * Serves {@code GET|POST /api/<addon>/config/raw} for every registered module.
     *
     * <p>The GET returns the addon's {@code config.yml} as it is written, its secrets — passwords,
     * keys, webhook addresses — shown as {@value ConfigSecrets#PLACEHOLDER} ({@link ConfigSecrets}).
     * The POST puts the stored value back wherever the placeholder was left, parses the result
     * before touching the disk, so a typo cannot leave an addon with a file it will refuse to load,
     * and reloads the addon once the file is written.</p>
     *
     * <p>Reading the file needs the module's permission and {@value #CONFIG_PERMISSION}. Saving it
     * is trusted like the console — a configuration can make a plugin run any command — and needs,
     * besides, {@value #CONFIG_SAVE_PERMISSION} and what the module named for it, usually its
     * reload command's permission; nothing short of an operator when it named nothing.</p>
     *
     * @param addonName The module name, which is also the addon's data folder name.
     */
    private void handleAddonConfig(HttpExchange exchange, String addonName) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        Session session = authenticated(exchange);
        if (session == null) return;
        boolean change = isChange(exchange);
        SaveRule save = saveRequirements(addonName);
        List<List<String>> needed = change ? save.requirements()
                : List.of(List.of(modulePermission(addonName)), List.of(CONFIG_PERMISSION));
        if (!permitted(exchange, session, needed)) return;
        if (change && save.operator() && !session.access().op()) {
            JsonObject body = new JsonObject();
            body.addProperty("error", addonName + " names no permission for saving its configuration:"
                    + " only an operator may save it from the dashboard.");
            body.addProperty("operator", true);
            sendJson(exchange, 403, body.toString());
            return;
        }

        File file = new File(moduleFolder(addonName), "config.yml");
        if (!file.isFile()) {
            sendJson(exchange, 404, "{\"error\":\"No config.yml for " + addonName + "\"}");
            return;
        }

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ConfigSecrets.Masked masked;
            try {
                masked = ConfigSecrets.mask(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            } catch (ConfigSecrets.Unsafe e) {
                // Better no editor than one that shows a password.
                logger.sendWarning("The configuration editor did not show " + addonName + "'s config.yml: " + e.getMessage() + ".");
                sendJson(exchange, 500, "{\"error\":\"The file could not be shown with its secrets hidden\"}");
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("yaml", masked.yaml());
            result.addProperty("hidden", masked.hidden());
            sendJson(exchange, 200, result.toString());
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String yaml;
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonObject body = JsonParser.parseReader(reader).getAsJsonObject();
            yaml = body.has("yaml") && !body.get("yaml").isJsonNull() ? body.get("yaml").getAsString() : null;
        } catch (Exception e) {
            sendJson(exchange, 400, "{\"error\":\"Invalid JSON body\"}");
            return;
        }
        if (yaml == null) {
            sendJson(exchange, 400, "{\"error\":\"Missing required field: yaml\"}");
            return;
        }

        // The secrets went out as placeholders: those left as they were keep what is stored.
        try {
            yaml = ConfigSecrets.restore(yaml, Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (ConfigSecrets.Refused e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            error.addProperty("hiddenKey", e.key());
            error.addProperty("line", e.line());
            sendJson(exchange, 400, error.toString());
            return;
        }

        // Parse before writing: rejecting bad YAML here keeps the addon's file loadable.
        try {
            new YamlConfiguration().loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Invalid YAML: " + e.getMessage());
            sendJson(exchange, 400, error.toString());
            return;
        }

        try {
            Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            sendJson(exchange, 500, "{\"error\":\"Could not write config.yml\"}");
            return;
        }
        // Trusted like the console, and written down like it.
        logger.sendInfo("Configuration of <aqua>" + addonName + "</aqua> saved from the dashboard by <aqua>"
                + session.owner() + "</aqua>.");

        // Reloading touches addon state, so it belongs on the server thread rather than here.
        main.schedulerAdapter().runGlobalTask(() -> main.extensions().reload(addonName));

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        sendJson(exchange, 200, result.toString());
    }

    /**
     * Guesses the MIME content type based on the file extension.
     *
     * @param path The file path.
     * @return The MIME content type string.
     */
    private String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf")) return "font/ttf";
        // The licences shipped beside the fonts and the icons, readable in a browser.
        if (path.endsWith(".txt")) return "text/plain; charset=UTF-8";
        return "application/octet-stream";
    }
}
