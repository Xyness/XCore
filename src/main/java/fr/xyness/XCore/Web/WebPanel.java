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

    /** Path to the static web files directory. */
    private final Path webRoot;

    /** The server start time in millis, for uptime calculation. */
    private final long startTimeMillis;

    /** Allowed CORS origin, {@code "*"} to allow any. */
    private final String corsOrigin;

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

    /** Static files already read from disk, keyed by path, with the modification time they had. */
    private final java.util.Map<String, byte[]> staticCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Modification times matching {@link #staticCache}. */
    private final java.util.Map<String, Long> staticStamps = new java.util.concurrent.ConcurrentHashMap<>();

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
     * Creates a new WebPanel instance.
     *
     * @param main          The plugin instance.
     * @param dataFolder    The plugin data folder (used to resolve the {@code web/} directory).
     * @param port          The port to listen on.
     * @param metricsPublic Whether the metrics endpoint is publicly accessible.
     * @param corsOrigin    The allowed CORS origin, or {@code "*"}.
     * @param main          The plugin, used to resolve the configured language.
     */
    public WebPanel(XCore main, File dataFolder, int port, boolean metricsPublic, String corsOrigin) {
        this.main = main;
        this.port = port;
        this.metricsPublic = metricsPublic;
        this.corsOrigin = (corsOrigin == null || corsOrigin.isBlank()) ? "*" : corsOrigin;
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
                File addonFolder = new File(main.getDataFolder(),
                        "addons" + File.separator + module.getName() + File.separator + "lang");
                File addonFile = new File(addonFolder, "web_" + wanted + ".yml");
                if (!addonFile.isFile()) addonFile = new File(addonFolder, "web_en.yml");
                if (addonFile.isFile()) readStringsInto(json, addonFile, module.getName());
            }
        }

        stringsJson = json.toString();
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
        server = HttpServer.create(new InetSocketAddress(port), 0);
        httpExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "XCore-WebPanel-HTTP");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(httpExecutor);

        // API endpoints
        server.createContext("/api/lang", this::handleLang);
        server.createContext("/api/sprite/", this::handleSprite);
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
        logger.sendInfo("Web dashboard started on port <aqua>" + port + "</aqua>.");
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
        modules.add(module);
        String basePath = "/api/" + module.getName().toLowerCase();
        module.registerRoutes(server, basePath);
        // Every addon has a config.yml, so every module gets the editor without asking for it.
        server.createContext(basePath + "/config/raw", ex -> handleAddonConfig(ex, module.getName()));
        // The module may carry its own dashboard strings; fold them in now that it is known.
        reloadStrings();
        logger.sendInfo("Registered web module: <aqua>" + module.getName() + "</aqua>.");
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

            var addons = main.getAddonManager().getAddons();
            long enabled = addons.keySet().stream()
                    .filter(name -> main.getAddonManager().getState(name) == fr.xyness.XCore.Addon.AddonState.ENABLED)
                    .count();
            metrics.addProperty("addons_total", addons.size());
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
        // The page is served from the server it administers, so it is always a local, tiny
        // transfer — and caching it for an hour is how an updated dashboard looks unchanged
        // after a restart. Freshness wins over the saved kilobytes.
        exchange.getResponseHeaders().set("Cache-Control", "no-store, must-revalidate");

        // Held in memory, keyed by modification time: the browser is told not to cache, so every
        // reader used to re-read app.js from disk on every page load. An edited file is picked up on
        // its next request, exactly as before.
        String key = filePath.toString();
        byte[] data;
        long modified = Files.getLastModifiedTime(filePath).toMillis();
        Long stamp = staticStamps.get(key);
        if (stamp != null && stamp == modified) {
            data = staticCache.get(key);
        } else {
            data = Files.readAllBytes(filePath);
            staticCache.put(key, data);
            staticStamps.put(key, modified);
        }
        if (data == null) data = Files.readAllBytes(filePath);

        boolean compress = data.length > 1024 && acceptsGzip(exchange) && isCompressible(contentType);
        if (compress) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(data.length / 4);
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(buffer)) {
                gzip.write(data);
            }
            data = buffer.toByteArray();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
        }

        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
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

        // A wrong token costs an attempt. Ten failures inside a minute and the source is turned
        // away without the comparison even running, which is what turns a 32-character token from
        // "guessable eventually" into "not guessable".
        if (!allow(authAttempts, ip, AUTH_LIMIT)) {
            sendJson(exchange, 429, "{\"error\":\"Too many authentication attempts\"}");
            return false;
        }

        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String presented = auth.substring(7);
            if (!presented.isEmpty() && matchesSession(presented)) {
                authAttempts.remove(ip);
                return true;
            }
        }
        record(authAttempts, ip);
        sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
        return false;
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
     * Adds CORS headers to the response.
     * <p>
     * Public so that {@link WebModule} implementations can apply the same headers.
     * </p>
     *
     * @param exchange The HTTP exchange.
     */
    public void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
        if (!"*".equals(corsOrigin)) exchange.getResponseHeaders().set("Vary", "Origin");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
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

        File file = new File(main.getDataFolder(), "addons" + File.separator + addonName + File.separator + "config.yml");
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
        main.schedulerAdapter().runGlobalTask(() -> main.getAddonManager().reloadAddon(addonName));

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
        return "application/octet-stream";
    }
}
