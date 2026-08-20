package fr.xyness.XCore.Commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Addon.AddonManager;
import fr.xyness.XCore.Addon.AddonState;
import fr.xyness.XCore.Addon.XAddon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Adds the XCore addons to {@code /plugins}.
 *
 * <p>Addons are not Bukkit plugins — XCore loads them from its own folder with its own class
 * loader — so the server has no way of listing them. Somebody looking at {@code /plugins} to see
 * what is installed gets half the picture.</p>
 *
 * <h2>The output is Paper's</h2>
 * Deliberately, down to the information icon, the section colours, the ten-per-line wrapping and
 * the click that runs {@code /version}. This is the same list with one more section, not a
 * different list: an administrator should not have to learn a second layout because an addon
 * framework is installed.
 *
 * <h2>How it takes the command over</h2>
 * By swapping the entry in the command map, which Paper exposes through
 * {@link org.bukkit.Server#getCommandMap()} — public API, no reflection and nothing out of
 * CraftBukkit. Both the short forms and the namespaced {@code bukkit:} ones are replaced, so there
 * is one answer to the question rather than two that disagree. The originals are kept and put back
 * on disable, and the whole thing is off with {@code plugins-command.override: false}, which is
 * what to reach for if this ever gets in the way.
 */
public class PluginsCommand extends Command {

    /**
     * Every key we take over, the namespaced forms included.
     *
     * <p>{@code SimpleCommandMap} registers a command twice: under its label and under
     * {@code fallbackPrefix:label}. Replacing only the short forms left {@code /bukkit:plugins}
     * printing a list with no addons in it, which is a second answer to the same question.</p>
     */
    private static final List<String> LABELS =
            List.of("plugins", "pl", "bukkit:plugins", "bukkit:pl");

    /** What was in the map before, so disable can put it back. */
    private static final Map<String, Command> REPLACED = new java.util.HashMap<>();

    // Paper's own palette, so the sections do not shift colour when this is installed.
    private static final TextColor INFO_COLOR = TextColor.color(52, 159, 218);
    private static final TextColor PAPER_COLOR = TextColor.color(0x0288D1);
    private static final TextColor BUKKIT_COLOR = TextColor.color(0xED8106);

    /** The addon section. */
    private static final TextColor ADDON_COLOR = TextColor.color(0xA855F7);

    private static final Component PLUGIN_TICK = Component.text("- ", NamedTextColor.DARK_GRAY);
    private static final Component PLUGIN_TICK_EMPTY = Component.text(" ");

    private static final Component SERVER_PLUGIN_INFO = Component.text("ℹ What is a server plugin?", INFO_COLOR)
            .append(plainLines("Server plugins can add new behavior to your server!\n"
                    + "You can find new plugins on Paper's plugin repository, Hangar.\n\n"
                    + "https://hangar.papermc.io/\n"));

    private static final Component ADDON_INFO = Component.text("ℹ What is an XCore addon?", INFO_COLOR)
            .append(plainLines("An addon is a plugin that runs inside XCore.\n"
                    + "It is loaded from plugins/XCore/addons/, not from plugins/,\n"
                    + "which is why the server does not list it as a plugin.\n"));

    private static final Component INFO_ICON_SERVER_PLUGIN = Component.text("ℹ ", INFO_COLOR)
            .hoverEvent(HoverEvent.showText(SERVER_PLUGIN_INFO))
            .clickEvent(ClickEvent.openUrl("https://docs.papermc.io/paper/adding-plugins"));

    private static final Component INFO_ICON_ADDON = Component.text("ℹ ", INFO_COLOR)
            .hoverEvent(HoverEvent.showText(ADDON_INFO));

    private final XCore core;

    /**
     * @param core The plugin instance.
     */
    public PluginsCommand(XCore core) {
        super("plugins", "Gets a list of plugins running on the server", "/plugins", List.of("pl"));
        this.core = core;
        // The permission the built-in uses, so an existing setup keeps working.
        setPermission("bukkit.command.plugins");
    }

    /**
     * Puts this command in the map in place of the server's.
     *
     * @param core The plugin instance.
     * @return {@code true} when the swap happened.
     */
    public static boolean install(XCore core) {
        try {
            CommandMap map = Bukkit.getCommandMap();
            Map<String, Command> known = map.getKnownCommands();
            PluginsCommand ours = new PluginsCommand(core);
            for (String label : LABELS) {
                Command previous = known.put(label, ours);
                if (previous != null) REPLACED.put(label, previous);
            }
            // A map that handed back a copy would leave the built-in in place and no error behind.
            return known.get("plugins") == ours;
        } catch (Throwable t) {
            core.getLogger().warning("Could not take over /plugins, leaving the server's own: " + t.getMessage());
            return false;
        }
    }

    /** Puts the server's own command back. */
    public static void uninstall() {
        if (REPLACED.isEmpty()) return;
        try {
            Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
            REPLACED.forEach(known::put);
        } catch (Throwable ignored) {
            // Shutting down: an unrestored entry outlives nothing.
        }
        REPLACED.clear();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!testPermission(sender)) return true;

        TreeMap<String, Plugin> paper = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        TreeMap<String, Plugin> bukkit = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            (isPaperPlugin(plugin) ? paper : bukkit).put(plugin.getName(), plugin);
        }

        AddonManager manager = core.getAddonManager();
        Map<String, XAddon> addons = manager == null ? Map.of() : new TreeMap<>(manager.getAddons());

        // Paper only shows the per-section counts when there is more than one section to tell
        // apart. Counting the addons in, the rule is the same.
        int sections = (paper.isEmpty() ? 0 : 1) + (bukkit.isEmpty() ? 0 : 1) + (addons.isEmpty() ? 0 : 1);
        boolean showSizes = sections > 1;

        sender.sendMessage(Component.text()
                .append(INFO_ICON_SERVER_PLUGIN)
                .append(Component.text("Server Plugins (%s):".formatted(paper.size() + bukkit.size()),
                        NamedTextColor.WHITE))
                .build());

        if (!paper.isEmpty()) {
            sender.sendMessage(header("Paper Plugins", PAPER_COLOR, paper.size(), showSizes));
            for (Component line : lines(paper.values().stream().map(this::formatPlugin).toList())) {
                sender.sendMessage(line);
            }
        }
        if (!bukkit.isEmpty()) {
            sender.sendMessage(header("Bukkit Plugins", BUKKIT_COLOR, bukkit.size(), showSizes));
            for (Component line : lines(bukkit.values().stream().map(this::formatPlugin).toList())) {
                sender.sendMessage(line);
            }
        }

        // The one section the server cannot produce.
        sender.sendMessage(Component.text()
                .append(INFO_ICON_ADDON)
                .append(header("XCore Addons", ADDON_COLOR, addons.size(), showSizes))
                .build());
        if (!addons.isEmpty()) {
            List<Component> entries = new ArrayList<>(addons.size());
            for (Map.Entry<String, XAddon> entry : addons.entrySet()) {
                entries.add(formatAddon(manager, entry.getKey(), entry.getValue()));
            }
            for (Component line : lines(entries)) sender.sendMessage(line);
        }
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias,
                                             @NotNull String[] args) {
        return List.of();
    }

    /**
     * One section heading, the shape Paper uses.
     *
     * @param text      The heading.
     * @param color     Its colour.
     * @param count     How many entries follow.
     * @param showCount Whether to show that number.
     * @return The heading.
     */
    private static Component header(String text, TextColor color, int count, boolean showCount) {
        TextComponent.Builder builder = Component.text().color(color).append(Component.text(text));
        if (showCount) builder.appendSpace().append(Component.text("(" + count + ")"));
        return builder.append(Component.text(":")).build();
    }

    /**
     * Lays entries out ten to a line, the first indented with a dash and the rest aligned under it.
     *
     * @param entries The formatted entries.
     * @return One component per line.
     */
    private static List<Component> lines(List<Component> entries) {
        List<Component> out = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < entries.size(); i += 10) {
            List<Component> slice = entries.subList(i, Math.min(i + 10, entries.size()));
            Component prefix = first ? Component.space().append(PLUGIN_TICK) : PLUGIN_TICK_EMPTY;
            first = false;
            out.add(prefix.append(Component.join(JoinConfiguration.commas(true), slice)));
        }
        return out;
    }

    /**
     * One plugin: green when enabled, red when not, clickable to its version.
     *
     * @param plugin The plugin.
     * @return The entry.
     */
    private Component formatPlugin(Plugin plugin) {
        String name = plugin.getName();
        return Component.text(name, plugin.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/version " + name));
    }

    /**
     * One addon: same shape, with the states an addon can be in and a click that reloads it.
     *
     * @param manager The addon manager.
     * @param name    The addon's name.
     * @param addon   The addon.
     * @return The entry.
     */
    private Component formatAddon(AddonManager manager, String name, XAddon addon) {
        AddonState state = manager == null ? null : manager.getState(name);
        TextColor color = switch (state == null ? AddonState.ERRORED : state) {
            case ENABLED -> NamedTextColor.GREEN;
            case LOADED, ENABLING, DISABLING -> NamedTextColor.YELLOW;
            default -> NamedTextColor.RED;
        };
        var descriptor = addon.getDescriptor();
        Component hover = Component.text(name, color)
                .append(Component.text(" " + descriptor.getVersion(), NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("by " + descriptor.getAuthor(), NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text(descriptor.getDescription() == null ? "" : descriptor.getDescription(),
                        NamedTextColor.WHITE))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("Click to reload it", NamedTextColor.DARK_GRAY));
        return Component.text(name, color)
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand("/xcore reload " + name));
    }

    /**
     * Turns a block of text into the newline-separated white component Paper uses in its hovers.
     *
     * @param text The text.
     * @return The component.
     */
    private static Component plainLines(String text) {
        TextComponent.Builder builder = Component.text();
        for (String line : text.split("\n")) {
            builder.append(Component.newline()).append(Component.text(line, NamedTextColor.WHITE));
        }
        return builder.build();
    }

    /**
     * Tells a Paper plugin from a Bukkit one.
     *
     * <p>Paper decides this from its plugin providers, which are server internals an addon
     * framework has no business reaching into. The class loader answers the same question from the
     * API: Paper gives its own plugins a different one. A fork that names it something else lands
     * everything in the Bukkit list, which is a cosmetic loss and never an error.</p>
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
