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
    /** Language key for the in-game "an update is available" notice. */
    private static final String UPDATE_MESSAGE_KEY = "update-available";

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
     * Alias for {@link #saveDefaultResource(String)}.
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
     *
     * <p>The address is the {@code update-url} declared in {@code addon.yml}. Without one, update
     * checking stays off.</p>
     */
    public final void initUpdater() {
        String url = descriptor.getUpdateUrl();
        if (url == null || url.isBlank()) {
            logger.sendWarning("No 'update-url' in addon.yml: update checking is off for this addon.");
            return;
        }
        this.updater = new Updater(url, descriptor.getVersion(), logger);
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
     * <p>The wording comes from {@code update-available} in the language file — the addon's own
     * if it defines the key, XCore's otherwise. It takes {@code {addon}}, {@code {version}} and
     * {@code {date}}.</p>
     *
     * @param player         The player to notify.
     * @param permissionNode The permission required to receive update notifications (e.g. {@code "ah.update"}).
     */
    public final void notifyUpdateOnJoin(Player player, String permissionNode) {
        if (updater == null || !updater.isUpdateAvailable()) return;
        if (!player.hasPermission(permissionNode)) return;
        if (!getConfig().getBoolean("update.notifications", true)) return;

        String addonName = descriptor.getName();
        String newVersion = updater.getNewVersionAvailable();
        String date = updater.getDate();

        // The addon's own file wins when it defines the key, so an addon can word it its way;
        // otherwise the single copy in XCore's lang/<code>.yml speaks for all of them.
        String template = lang.getRaw(UPDATE_MESSAGE_KEY);
        if (UPDATE_MESSAGE_KEY.equals(template)) {
            template = core.langManager().getRaw(UPDATE_MESSAGE_KEY);
        }
        final String message = template
                .replace("{addon}", addonName)
                .replace("{version}", newVersion)
                .replace("{date}", date == null ? "" : date);

        core.schedulerAdapter().runEntityTaskLater(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(message));
            }
        }, 60L);
    }

    /**
     * Updates the configuration file by adding missing keys from the default config in the JAR.
     * Existing values are preserved; only new keys are added.
     *
     * @param protectedSections Sections whose contents are user-managed (e.g. key:value maps like
     *                          {@code "chunk-limits.blocks"} or named entries like {@code "shops"}).
     *                          Nothing under a protected path is ever re-injected — regardless of
     *                          whether the section is present on disk. The initial defaults are
     *                          still written on first run via {@link #saveDefaultConfig()}.
     */
    public final void updateConfigWithDefaults(String... protectedSections) {
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
                if (defConfig.isConfigurationSection(key)) continue;
                if (diskConfig.contains(key)) continue;
                if (isUnderProtectedSection(key, protectedSections)) continue;
                diskConfig.set(key, defConfig.get(key));
                changed = true;
            }

            if (changed) {
                diskConfig.save(configFile);
            }
        } catch (IOException e) {
            logger.sendError("Error updating config with defaults: " + e.getMessage());
        }
        reloadConfig();
    }

    private static boolean isUnderProtectedSection(String key, String[] protectedSections) {
        for (String prot : protectedSections) {
            if (prot == null || prot.isEmpty()) continue;
            if (key.equals(prot) || key.startsWith(prot + ".")) return true;
        }
        return false;
    }

    /**
     * Removes keys the addon no longer reads, using the bundled defaults as the reference.
     *
     * <p>The counterpart to {@link #updateConfigWithDefaults(String...)}, which only ever <em>adds</em>.
     * After a refactor an existing install keeps every renamed or deleted key, silently inert — which
     * is how an administrator ends up certain a setting is applied when nothing reads it any more.
     * Anything absent from the bundled file is therefore dead by definition and is dropped.</p>
     *
     * <p>Two safeguards. Protected sections are never touched: they are the ones whose keys the user
     * legitimately invents — world names, material lists, shop entries — and which the defaults
     * cannot possibly enumerate. And the file is copied to {@code <name>.pre-prune.bak} before the
     * first removal, because this deletes user data.</p>
     *
     * <p>YAML lists are values, not sections, so their contents are never walked: a list a user has
     * extended is kept whole as long as its key still exists in the defaults.</p>
     *
     * @param fileName          The file to prune, relative to the data folder and to the JAR root
     *                          (typically {@code "config.yml"} or {@code "lang.yml"}).
     * @param protectedSections Paths whose contents are user-managed and must be left alone.
     * @return The number of keys removed.
     */
    public final int pruneObsoleteKeys(String fileName, String... protectedSections) {
        File file = new File(dataFolder, fileName);
        if (!file.exists()) return 0;

        FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(file);
        try (InputStream defStream = getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (defStream == null) {
                logger.sendDebug("No bundled " + fileName + " to compare against; nothing pruned.");
                return 0;
            }
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defStream, StandardCharsets.UTF_8));

            // Shallowest first, so removing an obsolete section lets its children be skipped
            // instead of being reported one by one.
            java.util.List<String> candidates = new java.util.ArrayList<>();
            for (String key : diskConfig.getKeys(true)) {
                if (defConfig.contains(key)) continue;
                if (isUnderProtectedSection(key, protectedSections)) continue;
                candidates.add(key);
            }
            candidates.sort(java.util.Comparator.comparingInt(k -> k.split("\\.").length));

            java.util.List<String> removed = new java.util.ArrayList<>();
            for (String key : candidates) {
                // Already gone with an ancestor.
                boolean covered = false;
                for (String done : removed) {
                    if (key.startsWith(done + ".")) { covered = true; break; }
                }
                if (covered) continue;
                if (!diskConfig.contains(key)) continue;
                diskConfig.set(key, null);
                removed.add(key);
            }

            if (removed.isEmpty()) return 0;

            File backup = new File(dataFolder, fileName + ".pre-prune.bak");
            try {
                java.nio.file.Files.copy(file.toPath(), backup.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.sendWarning("Could not back up " + fileName + " before pruning: " + e.getMessage()
                        + " — leaving the file untouched.");
                return 0;
            }

            diskConfig.save(file);
            logger.sendInfo("Pruned " + removed.size() + " obsolete key(s) from " + fileName
                    + " (backup: " + backup.getName() + "):");
            for (String key : removed) logger.sendInfo("  - " + key);
            return removed.size();

        } catch (IOException e) {
            logger.sendError("Error pruning " + fileName + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Prunes {@code config.yml}. Convenience overload of
     * {@link #pruneObsoleteKeys(String, String...)}.
     *
     * @param protectedSections Paths whose contents are user-managed.
     * @return The number of keys removed.
     */
    public final int pruneObsoleteConfigKeys(String... protectedSections) {
        int removed = pruneObsoleteKeys("config.yml", protectedSections);
        if (removed > 0) reloadConfig();
        return removed;
    }

    // -------------------------------------------------------------------------
    // Language
    // -------------------------------------------------------------------------

    /**
     * Resolves which language this addon should speak.
     *
     * <p>XCore's {@code language} setting drives the whole installation: pick French once and every
     * addon follows, instead of repeating the choice in a dozen files. An addon may still override
     * it with its own {@code language} key — useful when one addon has no translation for the
     * server's language, or when a network deliberately runs one component in another language.</p>
     *
     * @return The language code, lower-cased (e.g. {@code "fr"}).
     */
    public final String getLanguage() {
        String own = getConfig().getString("language", "");
        if (own != null && !own.isBlank()) return own.trim().toLowerCase();
        return core.getLanguage();
    }

    /**
     * Loads this addon's language file, honouring the resolved language.
     *
     * <p>Files live in {@code <addon>/lang/<code>.yml}. Every language bundled in the jar is
     * extracted on first run so the administrator can read and edit them all; the one matching
     * {@link #getLanguage()} is the one loaded, falling back to English when the addon ships no
     * translation for it.</p>
     *
     * @param available The language codes bundled in the jar, English first.
     */
    /**
     * Adds to a file on disk the keys its bundled version has and it does not.
     *
     * <p>Never overwrites: an administrator's translation is theirs. Used for the dashboard string
     * files, which have no merge pass of their own.</p>
     *
     * @param resourcePath The resource inside the addon jar, e.g. {@code lang/web_fr.yml}.
     */
    private void mergeMissingKeys(String resourcePath) {
        File target = new File(dataFolder, resourcePath.replace('/', File.separatorChar));
        if (!target.isFile()) return;
        try (InputStream defaults = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (defaults == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8));
            YamlConfiguration current = YamlConfiguration.loadConfiguration(target);
            boolean changed = false;
            for (String key : bundled.getKeys(true)) {
                if (bundled.isConfigurationSection(key) || current.contains(key)) continue;
                current.set(key, bundled.get(key));
                changed = true;
            }
            if (changed) {
                current.save(target);
                logger.sendDebug("Added missing keys to " + resourcePath + ".");
            }
        } catch (Exception e) {
            logger.sendDebug("Failed to merge " + resourcePath + ": " + e.getMessage());
        }
    }

    public final void loadLanguage(String... available) {
        File langFolder = new File(dataFolder, "lang");
        if (!langFolder.exists()) langFolder.mkdirs();

        // Extract every bundled translation, never overwriting an edited file. The web_ files are
        // the addon's own dashboard strings, and only exist for addons that register a web module.
        for (String code : available) {
            saveDefaultResource("lang/" + code + ".yml");
            saveDefaultResource("lang/web_" + code + ".yml");
            // Unlike the message files, nothing merges the dashboard strings: a web_*.yml already
            // on disk never received the keys a later version added, and the page showed the raw
            // key instead of a label. Only missing keys are written, so translations survive.
            mergeMissingKeys("lang/web_" + code + ".yml");
        }

        String wanted = getLanguage();
        boolean bundled = false;
        for (String code : available) {
            if (code.equalsIgnoreCase(wanted)) { bundled = true; break; }
        }

        File target = new File(langFolder, wanted + ".yml");

        if (!bundled && !target.exists()) {
            String fallback = available.length > 0 ? available[0] : "en";
            logger.sendWarning("No '" + wanted + "' translation bundled and no lang/" + wanted
                    + ".yml on disk — falling back to '" + fallback + "'.");
            wanted = fallback;
            target = new File(langFolder, wanted + ".yml");
        }

        try (InputStream defaults = getClass().getClassLoader().getResourceAsStream("lang/" + wanted + ".yml")) {
            lang.reload(target, defaults);
        } catch (IOException e) {
            logger.sendError("Failed to load language '" + wanted + "': " + e.getMessage());
        }
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
