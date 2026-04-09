package fr.xyness.XCore.Web;

import com.sun.net.httpserver.HttpServer;
import java.util.List;

/**
 * Interface for plugins to register web dashboard modules.
 * <p>
 * Each module provides a name, icon, list of pages for the sidebar,
 * and registers its own API routes on the shared HTTP server.
 * </p>
 */
public interface WebModule {

    /**
     * Returns the module display name (e.g., "XBans").
     *
     * @return The module name.
     */
    String getName();

    /**
     * Returns the module icon name for the sidebar (e.g., "shield", "coins", "chart").
     *
     * @return The icon identifier.
     */
    String getIcon();

    /**
     * Returns the list of sidebar pages for this module.
     *
     * @return A list of {@link WebPage} entries.
     */
    List<WebPage> getPages();

    /**
     * Registers API routes on the HTTP server under the given base path.
     * <p>
     * The base path will be {@code /api/<moduleName>}, and the module should create
     * contexts for its endpoints relative to that path.
     * </p>
     *
     * @param server   The shared HTTP server instance.
     * @param basePath The base path assigned to this module (e.g., "/api/xbans").
     */
    void registerRoutes(HttpServer server, String basePath);
}
