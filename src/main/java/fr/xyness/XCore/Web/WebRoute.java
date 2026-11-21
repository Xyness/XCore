package fr.xyness.XCore.Web;

import java.util.ArrayList;
import java.util.List;

/**
 * What reading one of a module's routes needs, for a route no page describes.
 *
 * <p>A page names what reading it needs with {@link WebPageSpec#readPermission}. Some routes are
 * read from elsewhere — the player profile asks every module what it knows about the player shown,
 * at {@code /api/<module>/player/<name>} — and answer with what not every member of staff may see:
 * addresses, accounts, alts. A module declares those here, from {@link WebModule#getRoutePermissions()},
 * with the permission the matching command asks for in game:</p>
 *
 * <pre>
 * WebRoute.of("/api/xbans/player/&#42;/alts")
 *     .requires("xbans.command.alts")
 *     .requires(WebPanel.PLAYERS_PERMISSION)
 * </pre>
 *
 * <p>A {@code *} stands for one segment of the path — a player's name — and nothing else is a
 * pattern. The panel holds every request that matches to all of its requirements, whatever the
 * method, on top of {@code xcore.dashboard.<module>}. A request that reaches the same handler but
 * matches none of the module's declarations — another spelling, a sub-path nobody declared — is
 * held to every declaration made under that route at once: it cannot ask for less than the
 * spelling the module wrote down.</p>
 */
public final class WebRoute {

    private final String path;
    private final List<List<String>> requirements = new ArrayList<>();

    private WebRoute(String path) {
        this.path = path;
    }

    /**
     * A route of the module, as the browser asks for it.
     *
     * @param path The absolute path, {@code /api/<module>/...}; {@code *} for one segment.
     * @return The declaration, to which {@link #requires} adds what it needs.
     */
    public static WebRoute of(String path) {
        return new WebRoute(path == null ? "" : path.trim());
    }

    /**
     * One more permission the route needs. Several nodes mean any one of them — a command that
     * also lets its plugin's administrator through; each call adds a requirement of its own, and
     * all of them are needed.
     *
     * @param nodes The permission, or its alternatives.
     * @return This declaration.
     */
    public WebRoute requires(String... nodes) {
        List<String> anyOf = new ArrayList<>();
        if (nodes != null) {
            for (String node : nodes) {
                if (node == null) continue;
                String clean = node.trim().toLowerCase(java.util.Locale.ROOT);
                if (!clean.isEmpty() && !anyOf.contains(clean)) anyOf.add(clean);
            }
        }
        if (!anyOf.isEmpty() && !requirements.contains(anyOf)) requirements.add(List.copyOf(anyOf));
        return this;
    }

    /** @return The path, as declared. */
    String path() {
        return path;
    }

    /** @return What the route needs: each entry met by any one of its nodes, all of them needed. */
    List<List<String>> requirements() {
        return List.copyOf(requirements);
    }
}
