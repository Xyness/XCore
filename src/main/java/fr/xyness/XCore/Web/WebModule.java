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

    /**
     * What reading the module's routes needs beyond {@code xcore.dashboard.<module>}, for those no
     * page describes: the ones that answer with a player's data — a profile, the addresses, the
     * alts — which the dashboard's player profile reads from every module. Each names the
     * permission the matching command asks for in game.
     *
     * <p>Read again whenever the panel rebuilds its rules, like {@link #getPages()}. A module that
     * declares nothing keeps every route open to whoever may open its pages, as before.</p>
     *
     * @return The declarations; none by default.
     */
    default List<WebRoute> getRoutePermissions() {
        return List.of();
    }

    /**
     * What saving the module's {@code config.yml} from the dashboard needs, besides
     * {@code xcore.dashboard.config.save}: the permission of the plugin's own reload command,
     * usually its administrator's. Several nodes mean any one of them. A configuration page that
     * names its own ({@link WebPageSpec#savePermission}) is needed as well.
     *
     * <p>A module that names none, here or on a page, can only have its configuration saved by an
     * operator. A configuration can make a plugin run console commands or turn its protections
     * off: saving one is trusted like the console, and no dashboard permission alone grants it.</p>
     *
     * @return The permission, or its alternatives; none by default.
     */
    default List<String> getConfigSavePermission() {
        return List.of();
    }
}
