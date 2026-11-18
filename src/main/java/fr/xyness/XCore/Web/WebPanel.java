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
     * @param expiresAt Epoch millis after which the session is refused, or 0 for no expiry.
     * @param locale   The client language of the player it was issued to, e.g. {@code fr_fr}, or
     *                 empty. Captured when the link is created, because the dashboard outlives the
     *                 session that opened it and the player may well be offline while reading it.
     */
    private record Session(String owner, String ownerId, long expiresAt, String locale) {}

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
        loadSessions();
        reloadStrings();
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
     * @param playerId   UUID of the player the session belongs to.
     * @param playerName Their name, shown when listing sessions.
     * @return The plain token to put in the link.
     */
    public String createSession(java.util.UUID playerId, String playerName) {
        // Whoever asked for the link is the one who will read the dashboard: their game language is
        // a far better guess than the server's own setting.
        String locale = "";
        try {
            org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.locale() != null) {
                locale = player.locale().toString().toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Throwable ignored) {
            // A platform without per-player locales falls back to the configured language.
        }

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String plain = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        long ttlHours = main.getConfig().getLong("web-dashboard.session-ttl-hours", 24);
        long expiresAt = ttlHours > 0 ? System.currentTimeMillis() + ttlHours * 3_600_000L : 0L;

        sessions.put(hash(plain), new Session(playerName, playerId.toString(), expiresAt, locale));
        pruneSessions();
        saveSessions();
        return plain;
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
        int before = sessions.size();
        sessions.values().removeIf(session -> id.equals(session.ownerId()));
        int removed = before - sessions.size();
        if (removed > 0) saveSessions();
        return removed;
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

    /** Drops expired sessions so an old link cannot be replayed forever. */
    private void pruneSessions() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> session.expiresAt() > 0 && session.expiresAt() < now);
    }

    private void loadSessions() {
        if (!sessionFile.isFile()) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(sessionFile.toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (String key : root.keySet()) {
                JsonObject entry = root.getAsJsonObject(key);
                sessions.put(key, new Session(
                        entry.has("owner") ? entry.get("owner").getAsString() : "?",
                        entry.has("ownerId") ? entry.get("ownerId").getAsString() : "",
                        entry.has("expiresAt") ? entry.get("expiresAt").getAsLong() : 0L,
                        entry.has("locale") ? entry.get("locale").getAsString() : ""));
            }
            pruneSessions();
        } catch (Exception e) {
            logger.sendWarning("Could not read web-sessions.json: " + e.getMessage());
        }
    }

    private void saveSessions() {
        try {
            JsonObject root = new JsonObject();
            sessions.forEach((key, session) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("owner", session.owner());
                entry.addProperty("ownerId", session.ownerId());
                entry.addProperty("expiresAt", session.expiresAt());
                entry.addProperty("locale", session.locale() == null ? "" : session.locale());
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
        if (server != null) {
            server.stop(2);
            logger.sendInfo("Web dashboard stopped.");
        }
        // After the server, so in-flight requests get their two seconds first.
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        authAttempts.clear();
        requestHits.clear();
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
        logger.sendDebug("Unregistered web module: " + module.getName() + ".");
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
     * Handles {@code GET /api/modules} — returns all registered modules and their pages as JSON.
     *
     * @param exchange The HTTP exchange.
     * @throws IOException If an I/O error occurs.
     */
    private void handleModules(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        if (!authenticate(exchange)) return;

        JsonArray arr = new JsonArray();

        synchronized (modules) {
            for (WebModule module : modules) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", module.getName());
                obj.addProperty("icon", module.getIcon());
                JsonArray pages = new JsonArray();
                for (WebPage page : module.getPages()) {
                    JsonObject pageObj = new JsonObject();
                    pageObj.addProperty("name", page.name());
                    pageObj.addProperty("path", page.path());
                    pageObj.addProperty("icon", page.icon());
                    // The descriptor is what lets the browser build the page without knowing
                    // anything about this addon. Absent means "render it generically".
                    if (page.spec() != null) pageObj.add("spec", page.spec().toJson());
                    pages.add(pageObj);
                }
                obj.add("pages", pages);
                arr.add(obj);
            }
        }

        sendJson(exchange, 200, arr.toString());
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

    /** Materials no mirror could answer for, so a miss costs one round trip and not one per view. */
    private final java.util.Set<String> spriteMisses = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

        String path = exchange.getRequestURI().getPath();
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);

        // The name is pasted into a URL: anything but a material name is refused outright.
        if (!name.matches("[a-z0-9_]{1,64}")) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        byte[] data = spriteCache.get(name);
        if (data == null && !spriteMisses.contains(name)) {
            data = fetchSprite(name);
            if (data != null) spriteCache.put(name, data);
            else spriteMisses.add(name);
        }

        if (data == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
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
     * Until when faces are not fetched at all, after the source could not be reached.
     *
     * <p>A server with no way out to the internet would otherwise spend a connection timeout on
     * every face of every table, on the panel's few HTTP threads, and the pages would wait behind
     * them. The dashboard draws a monogram in the meantime.</p>
     */
    private volatile long headsOfflineUntil;

    /**
     * Handles {@code GET /api/head/<name>.png} — a player's face, served from here.
     *
     * <p>The dashboard used to point its images straight at a public site: every reader's browser
     * told a third party which players were being looked at, and a blocked or offline site left
     * broken images. As with {@link #handleSprite}, the server fetches each face once and the
     * browser only ever talks to the server it is already talking to.</p>
     */
    private void handleHead(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);

        // The name is pasted into a URL: anything but a player name is refused outright.
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        String key = name.toLowerCase(java.util.Locale.ROOT);
        byte[] data = headCache.get(key);
        if (data == null && System.currentTimeMillis() >= headsOfflineUntil) {
            data = fetchBytes("https://mc-heads.net/avatar/" + name + "/64");
            if (data != null) headCache.put(key, data);
            else headsOfflineUntil = System.currentTimeMillis() + 300_000L;
        }

        if (data == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
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

    /** Reads a remote image, or {@code null} when it cannot be had. */
    private byte[] fetchBytes(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "XCore-Dashboard");
            if (conn.getResponseCode() != 200) return null;
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] data = in.readAllBytes();
                return data.length > 0 ? data : null;
            }
        } catch (Exception e) {
            logger.sendDebug("Could not fetch " + url + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
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

    /** Tries each mirror in turn; {@code null} when none has that texture. */
    private byte[] fetchSprite(String name) {
        for (String source : SPRITE_SOURCES) {
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) java.net.URI.create(String.format(source, assetVersion(), name))
                        .toURL().openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("User-Agent", "XCore-Dashboard");
                if (conn.getResponseCode() != 200) continue;
                try (java.io.InputStream in = conn.getInputStream()) {
                    byte[] data = in.readAllBytes();
                    if (data.length > 0) return data;
                }
            } catch (Exception ignored) {
                // Next mirror.
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        logger.sendDebug("No mirror had a texture for '" + name + "'.");
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
     * Handles {@code GET /api/auth} — validates the token. Always requires authentication.
     */
    private void handleAuth(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        if (!authenticate(exchange)) return;
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
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
        if (!authenticate(exchange)) return;

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
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        String presented = auth.substring(7);
        return !presented.isEmpty() && matchesSession(presented);
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
     * Checks the {@code Authorization} header for a valid Bearer token.
     * Sends a 401 response and returns {@code false} if authentication fails.
     * <p>
     * This method is public so that registered {@link WebModule} implementations
     * can reuse the same authentication logic on their own route handlers.
     * </p>
     *
     * @param exchange The HTTP exchange.
     * @return {@code true} if authenticated, {@code false} otherwise.
     * @throws IOException If an I/O error occurs while sending the error response.
     */
    public boolean authenticate(HttpExchange exchange) throws IOException {
        String ip = clientIp(exchange);

        // A live session is let in whatever its address has been doing. The count below is kept per
        // address, and behind a reverse proxy every reader has the proxy's: ten bad requests a
        // minute from anybody used to shut every administrator out of the dashboard.
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String presented = auth.substring(7);
            if (!presented.isEmpty() && matchesSession(presented)) {
                authAttempts.remove(ip);
                return true;
            }
        }

        // A wrong token costs an attempt: ten failures inside a minute and the source is told to
        // slow down instead of being answered.
        if (!allow(authAttempts, ip, AUTH_LIMIT)) {
            sendJson(exchange, 429, "{\"error\":\"Too many authentication attempts\"}");
            return false;
        }
        record(authAttempts, ip);
        sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
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
        Session session = sessionOf(exchange);
        return session == null ? null : session.owner();
    }

    /**
     * The session behind a request, or {@code null} when there is none.
     *
     * <p>Used to answer in the reader's own language rather than the server's.</p>
     */
    private Session sessionOf(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        Session session = sessions.get(hash(auth.substring(7)));
        if (session == null) return null;
        if (session.expiresAt() > 0 && session.expiresAt() < System.currentTimeMillis()) return null;
        return session;
    }

    /** Looks the token up among the live sessions, refusing the expired ones. */
    private boolean matchesSession(String presented) {
        Session session = sessions.get(hash(presented));
        if (session == null) return false;
        if (session.expiresAt() > 0 && session.expiresAt() < System.currentTimeMillis()) {
            pruneSessions();
            saveSessions();
            return false;
        }
        return true;
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
        for (java.util.Map<String, java.util.Deque<Long>> map : java.util.List.of(authAttempts, requestHits)) {
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
     * <p>The GET returns the addon's {@code config.yml} verbatim. The POST parses the submitted
     * text before touching the disk, so a typo cannot leave an addon with a file it will refuse
     * to load, and reloads the addon once the file is written.</p>
     *
     * @param addonName The module name, which is also the addon's data folder name.
     */
    private void handleAddonConfig(HttpExchange exchange, String addonName) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!rateLimit(exchange)) return;
        if (!authenticate(exchange)) return;

        File file = new File(moduleFolder(addonName), "config.yml");
        if (!file.isFile()) {
            sendJson(exchange, 404, "{\"error\":\"No config.yml for " + addonName + "\"}");
            return;
        }

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonObject result = new JsonObject();
            result.addProperty("yaml", Files.readString(file.toPath(), StandardCharsets.UTF_8));
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
