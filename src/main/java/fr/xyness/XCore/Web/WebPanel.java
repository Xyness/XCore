package fr.xyness.XCore.Web;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

    /** Bearer token for API authentication. */
    private final String token;

    /** Whether the /api/metrics endpoint is public (no auth required). */
    private final boolean metricsPublic;

    /** Registered web modules from other plugins. */
    private final List<WebModule> modules = Collections.synchronizedList(new ArrayList<>());

    /** Path to the static web files directory. */
    private final Path webRoot;

    /** The server start time in millis, for uptime calculation. */
    private final long startTimeMillis;

    // **************************************************************************
    // *                           Constructor                                  *
    // **************************************************************************

    /**
     * Creates a new WebPanel instance.
     *
     * @param dataFolder    The plugin data folder (used to resolve the {@code web/} directory).
     * @param port          The port to listen on.
     * @param token         The Bearer token for API authentication.
     * @param metricsPublic Whether the metrics endpoint is publicly accessible.
     */
    public WebPanel(File dataFolder, int port, String token, boolean metricsPublic) {
        this.port = port;
        this.token = token;
        this.metricsPublic = metricsPublic;
        this.logger = new Logger("WebPanel");
        this.webRoot = new File(dataFolder, "web").toPath();
        this.startTimeMillis = System.currentTimeMillis();
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
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "XCore-WebPanel-HTTP");
            t.setDaemon(true);
            return t;
        }));

        // API endpoints
        server.createContext("/api/auth", this::handleAuth);
        server.createContext("/api/modules", this::handleModules);
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/players", this::handlePlayers);

        // Static file handler (catch-all, must be last)
        server.createContext("/", this::handleStatic);

        server.start();
        logger.sendInfo("Web dashboard started on port <aqua>" + port + "</aqua>.");
    }

    /**
     * Stops the HTTP server gracefully.
     */
    public void stop() {
        if (server != null) {
            server.stop(2);
            logger.sendInfo("Web dashboard stopped.");
        }
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
    /**
     * Handles {@code GET /api/auth} — validates the token. Always requires authentication.
     */
    private void handleAuth(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!authenticate(exchange)) return;
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    /**
     * Handles {@code GET /api/players} — returns player list from database.
     */
    private void handlePlayers(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!authenticate(exchange)) return;

        String query = exchange.getRequestURI().getQuery();
        int offset = 0, limit = 100;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    if ("offset".equals(kv[0])) try { offset = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
                    if ("limit".equals(kv[0])) try { limit = Math.min(Integer.parseInt(kv[1]), 500); } catch (NumberFormatException ignored) {}
                }
            }
        }

        JsonArray players = new JsonArray();
        try (var conn = fr.xyness.XCore.API.XCoreApiProvider.get().getDataSource().getConnection();
             var ps = conn.prepareStatement("SELECT player_name, server_uuid, created_at FROM players ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("player_name", rs.getString("player_name"));
                    p.addProperty("server_uuid", rs.getString("server_uuid"));
                    p.addProperty("last_login", rs.getString("created_at"));
                    players.add(p);
                }
            }
        } catch (Exception e) {
            logger.sendDebug("Failed to load players for web panel: " + e.getMessage());
        }

        JsonObject result = new JsonObject();
        result.add("players", players);
        sendJson(exchange, 200, result.toString());
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (handlePreflight(exchange)) return;
        if (!metricsPublic && !authenticate(exchange)) return;

        JsonObject metrics = new JsonObject();
        long uptimeSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000;
        metrics.addProperty("uptime_seconds", uptimeSeconds);
        metrics.addProperty("players_online", Bukkit.getOnlinePlayers().size());

        int modulesCount;
        synchronized (modules) {
            modulesCount = modules.size();
        }
        metrics.addProperty("modules_count", modulesCount);

        sendJson(exchange, 200, metrics.toString());
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
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        byte[] data = Files.readAllBytes(filePath);
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
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
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ") &&
                java.security.MessageDigest.isEqual(auth.substring(7).getBytes(), token.getBytes())) return true;
        sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
        return false;
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
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
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
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
