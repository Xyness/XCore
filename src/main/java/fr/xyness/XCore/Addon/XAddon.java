package fr.xyness.XCore.Addon;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;

import org.bukkit.entity.Player;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Gui.GuiRegistry;
import fr.xyness.XCore.Lang.LangNamespace;
import fr.xyness.XCore.Utils.Logger;
import fr.xyness.XCore.Utils.SchedulerAdapter;
import fr.xyness.XCore.Utils.Updater;

/**
 * Base class for all XCore addons.
 * <p>
 * Addons extend this class and implement {@link #onEnable()} to initialize their features.
 * The addon lifecycle is managed by {@link AddonManager}, which injects dependencies
 * via {@link #init(AddonDescriptor, XCore, File, Logger, LangNamespace, GuiRegistry)}
 * before any lifecycle methods are called.
 * </p>
 *
 * <pre>{@code
 * public class MyAddon extends XAddon {
 *     @Override
 *     public boolean onEnable() {
 *         saveDefaultConfig();
 *         logger().sendInfo("MyAddon enabled!");
 *         return true;
 *     }
 * }
 * }</pre>
 */
public abstract class XAddon {

    private AddonDescriptor descriptor;
    private XCore core;
    private File dataFolder;
    private Logger logger;
    private LangNamespace lang;
    private GuiRegistry guiRegistry;
    private FileConfiguration config;
    private File configFile;
    private Updater updater;

    // -------------------------------------------------------------------------
    // Lifecycle methods (override in subclasses)
    // -------------------------------------------------------------------------

    /**
     * Called after the addon is loaded but before it is enabled.
     * Use this for early initialization that must happen before other addons are enabled.
     */
    public void onLoad() {}

    /**
     * Called when the addon is being enabled.
     * This is the main initialization entry point for addons.
     *
     * @return {@code true} if the addon enabled successfully, {@code false} to mark it as errored.
     */
    public abstract boolean onEnable();

    /**
     * Called when the addon is being disabled.
     * Clean up resources, save data, unregister listeners, etc.
     */
    public void onDisable() {}

    /**
     * Called when the addon is being reloaded.
     * Default implementation does nothing; override to handle config/lang reloads.
     */
    public void onReload() {}

    // -------------------------------------------------------------------------
    // Provided accessors (final, available after init)
    // -------------------------------------------------------------------------

    /**
     * Returns the XCore plugin instance.
     *
     * @return The {@link XCore} instance.
     */
    public final XCore core() { return core; }

    /**
     * Returns the Bukkit/Folia-compatible scheduler adapter.
     *
     * @return The {@link SchedulerAdapter} instance.
     */
    public final SchedulerAdapter scheduler() { return core.schedulerAdapter(); }

    /**
     * Returns the addon-scoped logger.
     *
     * @return The {@link Logger} instance scoped to this addon's name.
     */
    public final Logger logger() { return logger; }

    /**
     * Returns the addon's data folder ({@code plugins/XCore/addons/<name>/}).
     *
     * @return The data folder.
     */
    public final File getDataFolder() { return dataFolder; }

    /**
     * Returns the addon's language namespace for retrieving localized messages.
     *
     * @return The {@link LangNamespace} instance.
     */
    public final LangNamespace lang() { return lang; }

    /**
     * Returns the addon's GUI registry for registering GUI definitions.
     *
     * @return The {@link GuiRegistry} instance.
     */
    public final GuiRegistry guiRegistry() { return guiRegistry; }

    /**
     * Returns the addon descriptor parsed from {@code addon.yml}.
     *
     * @return The {@link AddonDescriptor}.
     */
    public final AddonDescriptor getDescriptor() { return descriptor; }

    // -------------------------------------------------------------------------
    // Listener registration
    // -------------------------------------------------------------------------

    /**
     * Registers a Bukkit event listener for this addon.
     * <p>
     * The listener is registered under XCore's plugin instance and tracked by
     * the {@link AddonListenerRegistry}. When this addon is disabled, all its
     * listeners are automatically unregistered without affecting other addons.
     * </p>
     * <p>
     * <b>Prefer this method</b> over calling
     * {@code Bukkit.getPluginManager().registerEvents(listener, core())} directly.
     * </p>
     *
     * @param listener The Bukkit event listener to register.
     */
    public final void registerListener(Listener listener) {
        core.getListenerRegistry().registerListener(getDescriptor().getName(), listener);
    }

    // -------------------------------------------------------------------------
    // Config convenience methods
    // -------------------------------------------------------------------------

    /**
     * Saves the default {@code config.yml} from the addon JAR to the data folder
     * if it does not already exist.
     */
    public final void saveDefaultConfig() {
        if (configFile == null) {
            configFile = new File(dataFolder, "config.yml");
        }
        if (!configFile.exists()) {
            saveDefaultResource("config.yml");
        }
    }

    /**
     * Saves a resource from the addon JAR to the data folder if it does not already exist.
     *
     * @param path The resource path inside the JAR (e.g. {@code "config.yml"}, {@code "lang.yml"}).
     */
    public final void saveDefaultResource(String path) {
        File outFile = new File(dataFolder, path);
        if (outFile.exists()) return;

        outFile.getParentFile().mkdirs();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                logger.sendDebug("Resource not found in JAR: " + path);
                return;
            }
            try (OutputStream os = Files.newOutputStream(outFile.toPath())) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            logger.sendError("Failed to save default resource '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Alias for {@link #saveDefaultResource(String)} for compatibility.
     */
    public final void saveResource(String path, boolean replace) {
        File outFile = new File(dataFolder, path);
        if (outFile.exists() && !replace) return;
        outFile.getParentFile().mkdirs();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return;
            try (OutputStream os = Files.newOutputStream(outFile.toPath())) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) os.write(buffer, 0, read);
            }
        } catch (IOException e) {
            logger.sendError("Failed to save resource '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Returns the addon's configuration.
     * Loads from disk on first access.
     *
     * @return The {@link FileConfiguration} for this addon.
     */
    public final FileConfiguration getConfig() {
        if (config == null) {
            reloadConfig();
        }
        return config;
    }

    /**
     * Reloads the addon's configuration from disk.
     */
    public final void reloadConfig() {
        if (configFile == null) {
            configFile = new File(dataFolder, "config.yml");
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    /**
     * Saves the addon's configuration to disk.
     */
    public final void saveConfig() {
        if (config == null || configFile == null) return;
        try {
            config.save(configFile);
        } catch (IOException e) {
            logger.sendWarning("Failed to save config.yml: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Update checker
    // -------------------------------------------------------------------------

    /**
     * Initializes the update checker for this addon.
     * Call this in {@link #onEnable()} to enable update checking and join notifications.
     * <p>
     * The updater fetches version info from GitHub at
     * {@code https://raw.githubusercontent.com/Xyness/<addonName>/refs/heads/main/version.yml}.
     * </p>
     *
     * @param addonName The addon name matching the GitHub repository name.
     */
    public final void initUpdater(String addonName) {
        this.updater = new Updater(addonName, getDescriptor().getVersion(), logger);
    }

    /**
     * Returns the updater instance, or {@code null} if not initialized.
     *
     * @return The {@link Updater} instance.
     */
    public final Updater updater() { return updater; }

    /**
     * Checks for updates and notifies the player if one is available.
     * Call this in your PlayerJoinEvent handler for players with the update notification permission.
     *
     * @param player         The player to notify.
     * @param permissionNode The permission required to receive update notifications (e.g. {@code "ah.update"}).
     */
    public final void notifyUpdateOnJoin(Player player, String permissionNode) {
        if (updater == null || !updater.isUpdateAvailable()) return;
        if (!player.hasPermission(permissionNode)) return;
        boolean notifications = getConfig().getBoolean("update.notifications", true);
        if (!notifications) return;
        String addonName = descriptor.getName();
        String newVersion = updater.getNewVersionAvailable();
        String date = updater.getDate();
        core.schedulerAdapter().runEntityTaskLater(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                    "<red>[" + addonName + "] An update is available: <aqua>" + newVersion + " <red>(" + date + ")"));
            }
        }, 60L);
    }

    /**
     * Updates the configuration file by adding missing keys from the default config in the JAR.
     * Existing values are preserved; only new keys are added.
     */
    public final void updateConfigWithDefaults() {
        if (configFile == null) {
            configFile = new File(dataFolder, "config.yml");
        }
        if (!configFile.exists()) {
            saveDefaultConfig();
            return;
        }

        FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream defStream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (defStream == null) return;
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defStream, StandardCharsets.UTF_8));

            boolean changed = false;
            for (String key : defConfig.getKeys(true)) {
                if (!defConfig.isConfigurationSection(key) && !diskConfig.contains(key)) {
                    diskConfig.set(key, defConfig.get(key));
                    changed = true;
                }
            }

            if (changed) {
                diskConfig.save(configFile);
            }
        } catch (IOException e) {
            logger.sendError("Error updating config with defaults: " + e.getMessage());
        }
        reloadConfig();
    }

    // -------------------------------------------------------------------------
    // Internal init (package-private, called by AddonManager)
    // -------------------------------------------------------------------------

    /**
     * Injects dependencies into the addon. Called by {@link AddonManager} before any lifecycle method.
     *
     * @param descriptor  The addon descriptor.
     * @param core        The XCore plugin instance.
     * @param dataFolder  The addon's data folder.
     * @param logger      The addon-scoped logger.
     * @param lang        The addon's language namespace.
     * @param guiRegistry The addon's GUI registry.
     */
    void init(AddonDescriptor descriptor, XCore core, File dataFolder, Logger logger, LangNamespace lang, GuiRegistry guiRegistry) {
        this.descriptor = descriptor;
        this.core = core;
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.lang = lang;
        this.guiRegistry = guiRegistry;
    }
}
