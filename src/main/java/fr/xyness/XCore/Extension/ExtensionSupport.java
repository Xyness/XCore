package fr.xyness.XCore.Extension;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Supplier;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Addon.AddonDescriptor;
import fr.xyness.XCore.Gui.GuiRegistry;
import fr.xyness.XCore.Gui.GuiUtils;
import fr.xyness.XCore.Lang.LangNamespace;
import fr.xyness.XCore.Utils.Logger;
import fr.xyness.XCore.Utils.SchedulerAdapter;
import fr.xyness.XCore.Utils.Updater;

/**
 * What {@link fr.xyness.XCore.Addon.XAddon} and {@link XPlugin} have in common, written once.
 *
 * <p>An addon loaded from {@code plugins/XCore/addons/} and a plugin built on XCore read their
 * configuration, extract their bundled files, load their languages and check for updates the same
 * way: both delegate here. Two copies of these seven hundred lines would drift apart, and the first
 * fix made to one and not the other is a plugin that behaves differently from the addon it used to
 * be.</p>
 *
 * <p>Internal to XCore. An addon or a plugin calls the methods of its own base class, never this
 * one directly.</p>
 */
public final class ExtensionSupport {

    /** Language key for the in-game "an update is available" notice. */
    private static final String UPDATE_MESSAGE_KEY = "update-available";

    private final AddonDescriptor descriptor;
    private final XCore core;
    private final File dataFolder;
    private final Logger logger;
    private final LangNamespace lang;
    private final GuiRegistry guiRegistry;

    /** Where bundled files are read from: the addon's or the plugin's own jar, never XCore's. */
    private final ClassLoader resources;

    /** The scheduler the update notice is sent through: XCore's for an addon, the plugin's own for a plugin. */
    private final Supplier<SchedulerAdapter> scheduler;

    /** {@code addon.yml} or {@code paper-plugin.yml}: named in the messages that send the reader to it. */
    private final String descriptorFile;

    /** {@code addon} or {@code plugin}, for the same messages. */
    private final String noun;

    private FileConfiguration config;
    private File configFile;
    private Updater updater;

    /** Built on first use, so an extension that never opens a GUI never creates one. */
    private GuiUtils guiUtils;

    /** Same for cooldowns and placeholders. */
    private fr.xyness.XCore.Utils.Cooldowns cooldowns;
    private fr.xyness.XCore.Integrations.PlaceholderRegistry placeholders;

    /**
     * @param descriptor     Name, version, author and update address.
     * @param core           The XCore instance.
     * @param dataFolder     The folder the files live in.
     * @param logger         The extension's logger.
     * @param lang           Its language namespace.
     * @param guiRegistry    Its GUI registry.
     * @param resources      The class loader of its jar, where the bundled files are.
     * @param scheduler      The scheduler it runs its tasks on.
     * @param descriptorFile The descriptor file name, for messages.
     * @param noun           {@code "addon"} or {@code "plugin"}, for messages.
     */
    public ExtensionSupport(AddonDescriptor descriptor, XCore core, File dataFolder, Logger logger,
                            LangNamespace lang, GuiRegistry guiRegistry, ClassLoader resources,
                            Supplier<SchedulerAdapter> scheduler, String descriptorFile, String noun) {
        this.descriptor = descriptor;
        this.core = core;
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.lang = lang;
        this.guiRegistry = guiRegistry;
        this.resources = resources;
        this.scheduler = scheduler;
        this.descriptorFile = descriptorFile;
        this.noun = noun;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** @return The descriptor. */
    public AddonDescriptor descriptor() { return descriptor; }

    /** @return The XCore instance. */
    public XCore core() { return core; }

    /** @return The data folder. */
    public File dataFolder() { return dataFolder; }

    /** @return The logger. */
    public Logger logger() { return logger; }

    /** @return The language namespace. */
    public LangNamespace lang() { return lang; }

    /** @return The GUI registry. */
    public GuiRegistry guiRegistry() { return guiRegistry; }

    /** @return The shared GUI helpers, built on first use. */
    public GuiUtils guiUtils() {
        if (guiUtils == null) guiUtils = new GuiUtils();
        return guiUtils;
    }

    /** @return The cooldowns, built on first use. */
    public fr.xyness.XCore.Utils.Cooldowns cooldowns() {
        if (cooldowns == null) cooldowns = new fr.xyness.XCore.Utils.Cooldowns();
        return cooldowns;
    }

    /** @return The placeholders, identified by the lower-cased name, built on first use. */
    public fr.xyness.XCore.Integrations.PlaceholderRegistry placeholders() {
        if (placeholders == null) {
            placeholders = new fr.xyness.XCore.Integrations.PlaceholderRegistry(
                    descriptor.getName().toLowerCase(), descriptor.getAuthor(), descriptor.getVersion());
        }
        return placeholders;
    }

    /**
     * Takes the placeholders back from PlaceholderAPI, if any were published.
     *
     * <p>The expansion is persistent, which is right while its owner runs and wrong once it has
     * stopped: PlaceholderAPI kept answering from a disabled plugin, and kept it in memory.</p>
     */
    public void withdrawPlaceholders() {
        if (placeholders != null) placeholders.withdraw();
    }

    // -------------------------------------------------------------------------
    // Files
    // -------------------------------------------------------------------------

    /** Saves the bundled {@code config.yml} to the data folder if it does not already exist. */
    public void saveDefaultConfig() {
        if (configFile == null) {
            configFile = new File(dataFolder, "config.yml");
        }
        if (!configFile.exists()) {
            saveDefaultResource("config.yml");
        }
    }

    /**
     * Saves a bundled resource to the data folder if it does not already exist.
     *
     * @param path The resource path inside the jar.
     */
    public void saveDefaultResource(String path) {
        File outFile = new File(dataFolder, path);
        if (outFile.exists()) return;

        outFile.getParentFile().mkdirs();
        try (InputStream is = resources.getResourceAsStream(path)) {
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
     * Saves a bundled resource to the data folder, overwriting it only when asked to.
     *
     * <p>Silent when the jar has no such file, unlike {@code JavaPlugin#saveResource}, which throws:
     * addons have always been able to ask for an optional file.</p>
     *
     * @param path    The resource path inside the jar.
     * @param replace Whether an existing file is overwritten.
     */
    public void saveResource(String path, boolean replace) {
        File outFile = new File(dataFolder, path);
        if (outFile.exists() && !replace) return;
        outFile.getParentFile().mkdirs();
        try (InputStream is = resources.getResourceAsStream(path)) {
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
     * Returns the configuration, loaded from disk on first access.
     *
     * <p>Without the bundled file attached as defaults, on purpose: a key the administrator
     * deleted reads as absent — the fallback in the code applies — instead of quietly coming back
     * from the jar. {@code JavaPlugin#getConfig} attaches them, which is why a plugin built on XCore
     * does not use it.</p>
     *
     * @return The configuration.
     */
    public FileConfiguration getConfig() {
        if (config == null) {
            reloadConfig();
        }
        return config;
    }

    /** Reloads the configuration from disk. */
    public void reloadConfig() {
        if (configFile == null) {
            configFile = new File(dataFolder, "config.yml");
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    /** Saves the configuration to disk. */
    public void saveConfig() {
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

    /** Starts the update checker on the {@code update-url} of the descriptor. Off without one. */
    public void initUpdater() {
        String url = descriptor.getUpdateUrl();
        if (url == null || url.isBlank()) {
            logger.sendWarning("No 'update-url' in " + descriptorFile + ": update checking is off for this " + noun + ".");
            return;
        }
        this.updater = new Updater(url, descriptor.getVersion(), logger);
    }

    /** @return The update checker, or {@code null} when it was never started. */
    public Updater updater() { return updater; }

    /**
     * Tells a joining player about an available update, if they may be told.
     *
     * @param player         The player to notify.
     * @param permissionNode The permission required to receive the notice.
     */
    public void notifyUpdateOnJoin(Player player, String permissionNode) {
        if (updater == null || !updater.isUpdateAvailable()) return;
        if (!player.hasPermission(permissionNode)) return;
        if (!getConfig().getBoolean("update.notifications", true)) return;

        String addonName = descriptor.getName();
        String newVersion = updater.getNewVersionAvailable();
        String date = updater.getDate();

        // The extension's own file wins when it defines the key, so it can word it its way;
        // otherwise the single copy in XCore's lang/<code>.yml speaks for all of them.
        String template = lang.getRaw(UPDATE_MESSAGE_KEY);
        if (UPDATE_MESSAGE_KEY.equals(template)) {
            template = core.langManager().getRaw(UPDATE_MESSAGE_KEY);
        }
        final String message = template
                .replace("{addon}", addonName)
                .replace("{version}", newVersion)
                .replace("{date}", date == null ? "" : date);

        scheduler.get().runEntityTaskLater(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(message));
            }
        }, 60L);
    }

    // -------------------------------------------------------------------------
    // Configuration maintenance
    // -------------------------------------------------------------------------

    /**
     * Adds to {@code config.yml} the keys the bundled version has and it does not.
     *
     * @param protectedSections Sections whose contents are user-managed and never re-injected.
     */
    public void updateConfigWithDefaults(String... protectedSections) {
        if (configFile == null) {
            configFile = new File(dataFolder, "config.yml");
        }
        if (!configFile.exists()) {
            saveDefaultConfig();
            return;
        }

        setAsideIfUnreadable(configFile);
        FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream defStream = resources.getResourceAsStream("config.yml")) {
            if (defStream == null) return;
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defStream, StandardCharsets.UTF_8));

            java.util.List<String> added = fr.xyness.XCore.Utils.ConfigMerger.addMissingKeys(
                    diskConfig, defConfig, protectedSections);

            if (!added.isEmpty()) {
                diskConfig.save(configFile);
                logger.sendDebug("Added " + added.size() + " new setting(s) to config.yml.");
            }
        } catch (IOException e) {
            logger.sendError("Error updating config with defaults: " + e.getMessage());
        }
        reloadConfig();
    }

    /**
     * Removes the keys the bundled version no longer has, after a backup.
     *
     * @param fileName          The file, relative to the data folder and to the jar root.
     * @param protectedSections Paths whose contents are user-managed and must be left alone.
     * @return The number of keys removed.
     */
    public int pruneObsoleteKeys(String fileName, String... protectedSections) {
        File file = new File(dataFolder, fileName);
        if (!file.exists()) return 0;

        FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(file);
        try (InputStream defStream = resources.getResourceAsStream(fileName)) {
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
                if (fr.xyness.XCore.Utils.ConfigMerger.isUnderProtectedSection(key, protectedSections)) continue;
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
     * Prunes {@code config.yml}, then reloads it when anything was removed.
     *
     * @param protectedSections Paths whose contents are user-managed.
     * @return The number of keys removed.
     */
    public int pruneObsoleteConfigKeys(String... protectedSections) {
        int removed = pruneObsoleteKeys("config.yml", protectedSections);
        if (removed > 0) reloadConfig();
        return removed;
    }

    // -------------------------------------------------------------------------
    // Language
    // -------------------------------------------------------------------------

    /**
     * Resolves the language to speak: the extension's own {@code language} key when set, XCore's
     * otherwise.
     *
     * @return The language code, lower-cased.
     */
    public String getLanguage() {
        String own = getConfig().getString("language", "");
        if (own != null && !own.isBlank()) return own.trim().toLowerCase();
        return core.getLanguage();
    }

    /**
     * Copies a YAML file aside when it cannot be parsed, and says so.
     *
     * <p>{@code YamlConfiguration.loadConfiguration} answers an unreadable file with an empty
     * configuration. To the merge that follows, every default then looks missing: it adds them all
     * and writes the result back — over the file. One stray tab in a config.yml used to cost every
     * setting in it at the next start, with nothing left to recover them from. The file is now kept
     * under another name first, and the console names it.</p>
     *
     * @param file The file about to be merged with its defaults.
     * @return {@code true} when the file could not be read and a copy was made.
     */
    public boolean setAsideIfUnreadable(File file) {
        if (file == null || !file.isFile()) return false;
        try {
            new YamlConfiguration().load(file);
            return false;
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            File copy = new File(file.getParentFile(), file.getName() + ".unreadable-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.copy(file.toPath(), copy.toPath());
            } catch (IOException copyError) {
                logger.sendError(file.getName() + " is not valid YAML and could not be copied aside: " + copyError.getMessage());
                return false;
            }
            String reason = String.valueOf(e.getMessage());
            int cut = reason.indexOf('\n');
            logger.sendError(file.getName() + " is not valid YAML and was not read: " + (cut > 0 ? reason.substring(0, cut) : reason));
            logger.sendError("Your file is kept as " + copy.getName() + ". The defaults are used until it is repaired and put back.");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Adds to a file on disk the keys its bundled version has and it does not.
     *
     * <p>Never overwrites: an administrator's translation is theirs. Used for the dashboard string
     * files, which have no merge pass of their own.</p>
     *
     * @param resourcePath The resource inside the jar, e.g. {@code lang/web_fr.yml}.
     */
    private void mergeMissingKeys(String resourcePath) {
        File target = new File(dataFolder, resourcePath.replace('/', File.separatorChar));
        if (!target.isFile()) return;
        setAsideIfUnreadable(target);
        try (InputStream defaults = resources.getResourceAsStream(resourcePath)) {
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

    /**
     * Extracts every bundled translation, then loads the one {@link #getLanguage()} names,
     * falling back to the first available when the jar has none for it.
     *
     * @param available The language codes bundled in the jar, English first.
     */
    public void loadLanguage(String... available) {
        File langFolder = new File(dataFolder, "lang");
        if (!langFolder.exists()) langFolder.mkdirs();

        // Extract every bundled translation, never overwriting an edited file. The web_ files are
        // the extension's own dashboard strings, and only exist for those that register a web module.
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

        try (InputStream defaults = resources.getResourceAsStream("lang/" + wanted + ".yml")) {
            lang.reload(target, defaults);
        } catch (IOException e) {
            logger.sendError("Failed to load language '" + wanted + "': " + e.getMessage());
        }
    }
}
