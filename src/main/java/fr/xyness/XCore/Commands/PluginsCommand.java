package fr.xyness.XCore.Commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Addon.AddonManager;
import fr.xyness.XCore.Addon.AddonState;
import fr.xyness.XCore.Addon.XAddon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * Replaces {@code /plugins} so the XCore addons appear next to the server's plugins.
 *
 * <p>Addons are not Bukkit plugins — XCore loads them from its own folder with its own class
 * loader — so the server has no way of listing them. Somebody looking at {@code /plugins} to see
 * what is installed gets half the picture.</p>
 *
 * <p>The command is intercepted rather than re-registered: the built-in stays reachable as
 * {@code /bukkit:plugins}, and nothing is patched into the command map. Switch it off with
 * {@code plugins-command.override: false}.</p>
 */
public class PluginsCommand implements Listener {

    /** What we answer to, once the leading slash and any arguments are removed. */
    private static final List<String> LABELS = List.of("plugins", "pl");

    private final XCore core;

    /**
     * @param core The plugin instance.
     */
    public PluginsCommand(XCore core) {
        this.core = core;
    }

    /**
     * Intercepts the command typed by a player.
     *
     * @param event The command event.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!matches(event.getMessage())) return;
        if (!event.getPlayer().hasPermission("bukkit.command.plugins")) return;
        event.setCancelled(true);
        send(event.getPlayer());
    }

    /**
     * Intercepts the command typed in the console.
     *
     * @param event The command event.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!matches("/" + event.getCommand())) return;
        event.setCancelled(true);
        send(event.getSender());
    }

    /**
     * Whether this is a bare {@code /plugins} or {@code /pl}.
     *
     * <p>A namespaced form such as {@code /bukkit:plugins} is deliberately left alone, so the
     * server's own list stays one command away.</p>
     *
     * @param message The full command line, slash included.
     * @return {@code true} when we should answer instead.
     */
    private boolean matches(String message) {
        if (message == null || message.isEmpty()) return false;
        String label = message.startsWith("/") ? message.substring(1) : message;
        int space = label.indexOf(' ');
        if (space >= 0) label = label.substring(0, space);
        return LABELS.contains(label.toLowerCase());
    }

    /**
     * Writes the three lists.
     *
     * @param sender Who asked.
     */
    private void send(CommandSender sender) {
        List<Plugin> paper = new ArrayList<>();
        List<Plugin> bukkit = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            (isPaperPlugin(plugin) ? paper : bukkit).add(plugin);
        }
        paper.sort(Comparator.comparing(p -> p.getName().toLowerCase()));
        bukkit.sort(Comparator.comparing(p -> p.getName().toLowerCase()));

        AddonManager addons = core.getAddonManager();
        Map<String, XAddon> loaded = addons == null ? Map.of() : addons.getAddons();

        int total = paper.size() + bukkit.size() + loaded.size();
        sender.sendMessage(core.langManager().getComponent("plugins-header", "count", String.valueOf(total)));

        if (!paper.isEmpty()) {
            sender.sendMessage(core.langManager().getComponent("plugins-section-paper"));
            sender.sendMessage(joinPlugins(paper));
        }
        if (!bukkit.isEmpty()) {
            sender.sendMessage(core.langManager().getComponent("plugins-section-bukkit"));
            sender.sendMessage(joinPlugins(bukkit));
        }

        sender.sendMessage(core.langManager().getComponent("plugins-section-addons",
                "count", String.valueOf(loaded.size())));
        if (loaded.isEmpty()) {
            sender.sendMessage(core.langManager().getComponent("plugins-addons-none"));
        } else {
            sender.sendMessage(joinAddons(addons, loaded));
        }
    }

    /** Renders one comma-separated line of plugin names, coloured by state. */
    private Component joinPlugins(List<Plugin> plugins) {
        Component line = Component.empty();
        for (int i = 0; i < plugins.size(); i++) {
            Plugin plugin = plugins.get(i);
            if (i > 0) line = line.append(core.langManager().getComponent("plugins-separator"));
            line = line.append(core.langManager().getComponent(
                    plugin.isEnabled() ? "plugins-entry-enabled" : "plugins-entry-disabled",
                    "name", plugin.getName())
                    .hoverEvent(HoverEvent.showText(core.langManager().getComponent("plugins-hover",
                            "name", plugin.getName(),
                            "version", plugin.getPluginMeta().getVersion(),
                            "author", String.join(", ", plugin.getPluginMeta().getAuthors())))));
        }
        return line;
    }

    /** Renders the addon line: same shape, plus a click that reloads the one you point at. */
    private Component joinAddons(AddonManager manager, Map<String, XAddon> addons) {
        Component line = Component.empty();
        boolean first = true;
        for (Map.Entry<String, XAddon> entry : addons.entrySet()) {
            String name = entry.getKey();
            AddonState state = manager.getState(name);
            String key = switch (state == null ? AddonState.ERRORED : state) {
                case ENABLED -> "plugins-entry-enabled";
                case LOADED, ENABLING, DISABLING -> "plugins-entry-loading";
                default -> "plugins-entry-disabled";
            };
            if (!first) line = line.append(core.langManager().getComponent("plugins-separator"));
            first = false;

            var descriptor = entry.getValue().getDescriptor();
            line = line.append(core.langManager().getComponent(key, "name", name)
                    .hoverEvent(HoverEvent.showText(core.langManager().getComponent("plugins-addon-hover",
                            "name", name,
                            "version", descriptor.getVersion(),
                            "author", descriptor.getAuthor(),
                            "description", descriptor.getDescription())))
                    .clickEvent(ClickEvent.suggestCommand("/xcore reload " + name)));
        }
        return line;
    }

    /**
     * Tells a Paper plugin from a Bukkit one.
     *
     * <p>There is no API for this, so the class loader is what answers: Paper gives its own plugins
     * a different one. A fork that names it something else lands everything in the Bukkit list,
     * which is a cosmetic loss and never an error.</p>
     *
     * @param plugin The plugin to classify.
     * @return {@code true} when it looks like a Paper plugin.
     */
    private static boolean isPaperPlugin(Plugin plugin) {
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            return loader != null && loader.getClass().getName().contains("PaperPluginClassLoader");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
