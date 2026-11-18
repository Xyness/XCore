package fr.xyness.XCore.Extension;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Addon.AddonManager;
import fr.xyness.XCore.Addon.AddonState;
import fr.xyness.XCore.Utils.Logger;
import fr.xyness.XCore.Utils.Updater;

/**
 * Every XCore extension on this server, addons and plugins alike.
 *
 * <p>Addons come from {@link AddonManager}, which loads them and knows their states. Plugins built
 * on {@link XPlugin} are loaded by the server, so XCore only learns about them when they tell it:
 * each one registers here when it starts enabling and leaves when it is disabled. Whatever lists,
 * reloads or diagnoses extensions asks this class, and sees both.</p>
 *
 * <p>A plugin whose start failed stays listed, as errored, the way a failed addon does — but as a
 * {@link Snapshot}, so XCore does not keep a stopped plugin and its class loader in memory.</p>
 */
public final class ExtensionRegistry {

    private final XCore core;
    private final Logger logger = new Logger("Extensions");

    /** Plugins, in the order they started. Read from the dashboard's threads, hence copy-on-write. */
    private final List<XExtension> plugins = new CopyOnWriteArrayList<>();

    /**
     * @param core The XCore instance, whose addon manager supplies the addons.
     */
    public ExtensionRegistry(XCore core) {
        this.core = core;
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Returns every extension: the addons in load order, then the plugins in the order they started.
     *
     * @return A new list, safe to keep.
     */
    public List<XExtension> all() {
        List<XExtension> out = new ArrayList<>();
        AddonManager manager = core.getAddonManager();
        if (manager != null) out.addAll(manager.extensions());
        out.addAll(plugins);
        return out;
    }

    /**
     * Returns the plugins only.
     *
     * @return An unmodifiable view.
     */
    public List<XExtension> plugins() {
        return Collections.unmodifiableList(plugins);
    }

    /**
     * Finds an extension by name: the exact spelling first, then ignoring case, since that is how
     * an administrator types it.
     *
     * @param name The name.
     * @return The extension, or empty.
     */
    public Optional<XExtension> find(String name) {
        if (name == null) return Optional.empty();
        List<XExtension> all = all();
        for (XExtension extension : all) {
            if (extension.name().equals(name)) return Optional.of(extension);
        }
        for (XExtension extension : all) {
            if (extension.name().equalsIgnoreCase(name)) return Optional.of(extension);
        }
        return Optional.empty();
    }

    /**
     * Whether a plugin built on XCore of this name is registered, running or not.
     *
     * @param name The name, compared ignoring case.
     * @return {@code true} when one is.
     */
    public boolean hasPlugin(String name) {
        for (XExtension plugin : plugins) {
            if (plugin.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** @return How many extensions are enabled. */
    public long countEnabled() {
        return all().stream().filter(e -> e.state() == AddonState.ENABLED).count();
    }

    // -------------------------------------------------------------------------
    // Reload
    // -------------------------------------------------------------------------

    /**
     * Runs an extension's reload hook, and says how it went.
     *
     * <p>Throwable is caught, not Exception: an extension built against another XCore fails with a
     * LinkageError, and the reload of everything else must still go through.</p>
     *
     * @param extension The extension.
     * @return {@code true} when it reloaded; {@code false} when it is not enabled or its hook failed.
     */
    public boolean reload(XExtension extension) {
        if (extension.state() != AddonState.ENABLED) {
            logger.sendWarning(extension.name() + " is not enabled (state: " + extension.state() + "), cannot reload.");
            return false;
        }
        try {
            extension.reload();
            logger.sendInfo("Reloaded " + noun(extension) + ": " + extension.name());
            return true;
        } catch (Throwable t) {
            logger.sendError("Error reloading " + noun(extension) + " '" + extension.name() + "': " + t);
            return false;
        }
    }

    /**
     * Reloads an extension by name.
     *
     * @param name The name, as {@link #find(String)} matches it.
     * @return {@code true} when it reloaded.
     */
    public boolean reload(String name) {
        Optional<XExtension> extension = find(name);
        if (extension.isEmpty()) {
            logger.sendWarning("No addon or plugin named '" + name + "' to reload.");
            return false;
        }
        return reload(extension.get());
    }

    private static String noun(XExtension extension) {
        return extension.kind() == XExtension.Kind.PLUGIN ? "plugin" : "addon";
    }

    // -------------------------------------------------------------------------
    // Plugin registration (called by XPlugin)
    // -------------------------------------------------------------------------

    /**
     * Adds a plugin, replacing any earlier entry of the same name — the snapshot a failed start
     * left behind, or the previous instance of a plugin a plugin manager reloaded.
     *
     * @param plugin The plugin's view.
     */
    void register(XExtension plugin) {
        plugins.removeIf(existing -> existing.name().equalsIgnoreCase(plugin.name()));
        plugins.add(plugin);
    }

    /**
     * Removes a plugin that stopped normally.
     *
     * @param plugin The plugin's view.
     */
    void unregister(XExtension plugin) {
        plugins.remove(plugin);
    }

    /**
     * Disables the plugins built on XCore that are still enabled, newest first.
     *
     * <p>The server disables a plugin before the plugins it depends on, so on a normal stop there
     * is nothing left to do here. This is for XCore going away alone — its own start failed, or a
     * plugin manager unloads it — and a plugin must not keep running on a database pool that is
     * about to close under it.</p>
     */
    public void disablePlugins() {
        List<XExtension> running = new ArrayList<>(plugins);
        Collections.reverse(running);
        for (XExtension extension : running) {
            Plugin plugin = extension.plugin();
            if (plugin == null || !plugin.isEnabled()) continue;
            logger.sendWarning("XCore is stopping: disabling " + extension.name() + ", which runs on it.");
            try {
                Bukkit.getPluginManager().disablePlugin(plugin);
            } catch (Throwable t) {
                logger.sendError("Could not disable " + extension.name() + ": " + t);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    /**
     * What remains listed of a plugin that no longer runs: its identity and how it ended.
     *
     * @param name        The plugin name.
     * @param version     Its version.
     * @param author      Its author.
     * @param description Its description.
     * @param state       How it ended, normally {@link AddonState#ERRORED}.
     * @param dataFolder  Its data folder.
     */
    public record Snapshot(String name, String version, String author, String description,
                           AddonState state, File dataFolder) implements XExtension {

        /**
         * Freezes an extension as it is now, with the given state.
         *
         * @param extension The extension.
         * @param state     The state to record.
         * @return The snapshot.
         */
        public static Snapshot of(XExtension extension, AddonState state) {
            return new Snapshot(extension.name(), extension.version(), extension.author(),
                    extension.description(), state, extension.dataFolder());
        }

        @Override public Kind kind() { return Kind.PLUGIN; }
        @Override public Updater updater() { return null; }

        @Override
        public void reload() {
            throw new IllegalStateException(name + " is not running.");
        }
    }
}
