package fr.xyness.XCore.Extension;

import java.io.File;

import org.bukkit.plugin.Plugin;

import fr.xyness.XCore.Addon.AddonState;
import fr.xyness.XCore.Utils.Updater;

/**
 * Something XCore lists, reloads, measures and diagnoses: an addon loaded from
 * {@code plugins/XCore/addons/}, or a plugin of its own built on {@link XPlugin}.
 *
 * <p>{@code /xcore addons}, {@code /xcore reload}, {@code /xcore diag}, the profiler and the web
 * dashboard all go through {@link ExtensionRegistry}, which answers with these, so none of them
 * needs to know which of the two kinds it is looking at.</p>
 */
public interface XExtension {

    /** How an extension reached the server. */
    enum Kind {
        /** A jar in {@code plugins/XCore/addons/}, loaded by XCore's own class loader. */
        ADDON,
        /** A plugin in {@code plugins/}, loaded by the server, that depends on XCore. */
        PLUGIN
    }

    /** @return The name: the one in {@code addon.yml}, or the plugin name. */
    String name();

    /** @return The version string. */
    String version();

    /** @return The author, or authors separated by commas. */
    String author();

    /** @return The description, possibly empty. */
    String description();

    /** @return Which kind it is. */
    Kind kind();

    /** @return Where it stands in its lifecycle. */
    AddonState state();

    /** @return The folder its files live in. */
    File dataFolder();

    /** @return Its update checker, or {@code null} when it never started one. */
    Updater updater();

    /**
     * @return The server plugin behind it, or {@code null} for an addon (and for a plugin that is
     *         no longer running).
     */
    default Plugin plugin() { return null; }

    /**
     * Re-reads its configuration and language files: what {@code /xcore reload} runs.
     *
     * @throws Exception Whatever the reload hook throws; the registry reports it.
     */
    void reload() throws Exception;
}
