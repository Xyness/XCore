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

    /**
     * Why {@code config.yml} could not be read as it stands on disk, or {@code null} when it was.
     *
     * <p>While this is set the file is never written: not by the merge, not by the prune, not by
     * {@link #saveConfig()}. It only had a typo, and everything in it is one correction away.</p>
     */
    private volatile String configProblem;

    /** When the problem above was last written to the console, so one start says it once. */
    private long configProblemReported;

    /** Built on first use, so an extension that never opens a GUI never creates one. */
    private GuiUtils guiUtils;

    /** Same for cooldowns and placeholders. */
    private fr.xyness.XCore.Utils.Cooldowns cooldowns;
    private fr.xyness.XCore.Integrations.PlaceholderRegistry placeholders;

    /** The renames declared through {@link #renamedKeys(String, java.util.Map)}, in the order they were declared. */
    private final java.util.List<Renamed> renamed = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * One declaration: the files it covers and what moves where.
     *
     * @param pattern The file, relative to the data folder, {@code *} allowed in its last segment.
     * @param files   The same as a regular expression over relative paths written with {@code /}.
     * @param moves   Old path → new path.
     */
    private record Renamed(String pattern, java.util.regex.Pattern files, java.util.Map<String, String> moves) {}

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
        try (InputStream is = bundled(path)) {
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
        try (InputStream is = bundled(path)) {
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

    /**
     * Reloads the configuration from disk.
     *
     * <p>A file that is not valid YAML is left exactly as it is. On the first load the settings
     * bundled in the jar are used instead; on a later one, the settings read before stay. Loading it
     * anyway answered with an empty configuration — every setting at the fallback written in the
     * code and every list empty — and the merge that followed then wrote that emptiness over the
     * file. The console names the line; nothing else changes until it is fixed.</p>
     */
    public void reloadConfig() {
        if (configFile == null) {
            configFile = new File(dataFolder, "config.yml");
        }
        String problem = fr.xyness.XCore.Utils.YamlFiles.problem(configFile);
        if (problem == null) {
            if (configProblem != null) logger.sendInfo("config.yml can be read again.");
            configProblem = null;
            config = YamlConfiguration.loadConfiguration(configFile);
            configEverRead = true;
            return;
        }

        if (config == null) config = bundledConfig();
        // Not "is this the first load": after a start on a file that could not be read, what is
        // in memory is still the bundled settings, however many reloads later.
        boolean firstLoad = !configEverRead;

        // Said once per start or per reload: this method runs several times in a row during both.
        long now = System.currentTimeMillis();
        boolean justSaid = problem.equals(configProblem) && now - configProblemReported < 3000L;
        configProblem = problem;
        if (justSaid) return;
        configProblemReported = now;
        logger.sendError("config.yml is not valid YAML: " + fr.xyness.XCore.Utils.YamlFiles.forConsole(problem));
        logger.sendError("The file was left untouched. " + (firstLoad
                ? "The default settings are used until it is fixed and the " + noun + " reloaded."
                : "The settings read before stay in use until it is fixed and the " + noun + " reloaded."));
    }

    /**
     * A file bundled in this extension's own jar.
     *
     * <p>An addon's class loader goes on to XCore's jar when the addon has no such file, and XCore
     * ships a {@code config.yml} and a {@code lang/fr.yml} of its own: an addon without one was
     * handed XCore's as its defaults. A plugin's class loader never looks further than its jar.</p>
     *
     * @param path The resource path inside the jar.
     * @return The stream, or {@code null} when the jar has no such file.
     */
    private InputStream bundled(String path) {
        if (resources instanceof fr.xyness.XCore.Addon.AddonClassLoader addon) {
            return addon.getOwnResourceAsStream(path);
        }
        return resources.getResourceAsStream(path);
    }

    /** Set once {@code config.yml} has been read from disk: until then the bundled settings are in use. */
    private volatile boolean configEverRead;

    /** One writer at a time on {@code config.yml}: two changes read the same file and one was lost. */
    private final Object configWriteLock = new Object();

    /** The configuration bundled in the jar, for a start on a file that cannot be read. */
    private FileConfiguration bundledConfig() {
        try (InputStream in = bundled("config.yml")) {
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // No bundled file to fall back on: the fallbacks written in the code apply.
        }
        return new YamlConfiguration();
    }

    /**
     * @return Whether {@code config.yml} was read as it stands on disk. {@code false} means the file
     *         is not valid YAML, is being left alone, and the settings in use are not the ones it holds.
     */
    public boolean isConfigReadable() {
        return configProblem == null;
    }

    /**
     * @return Where {@code config.yml} stops being YAML and why, or {@code null} when it was read.
     */
    public String configProblem() {
        return configProblem;
    }

    /**
     * Saves the configuration to disk.
     *
     * <p>Not while the file on disk cannot be read: what is in memory then is the bundled defaults,
     * or an older reading, and saving it would replace the file an administrator only has to
     * correct. The refusal goes to the console only; a command that has to answer its sender uses
     * {@link #updateConfigFile(java.util.function.Consumer)}, which says whether it wrote.</p>
     */
    public void saveConfig() {
        if (config == null || configFile == null) return;
        synchronized (configWriteLock) {
            // Asked of the file as it is now: one edited into a mistake since the last reload has
            // not been reported yet, and is just as much not to be written over.
            String problem = configProblem != null ? configProblem : fr.xyness.XCore.Utils.YamlFiles.problem(configFile);
            if (problem != null) {
                logger.sendWarning("config.yml was not saved: the file on disk is not valid YAML ("
                        + fr.xyness.XCore.Utils.YamlFiles.forConsole(problem) + ") and stays as it is until that is fixed.");
                return;
            }
            try {
                config.save(configFile);
            } catch (IOException e) {
                logger.sendWarning("Failed to save config.yml: " + e.getMessage());
            }
        }
    }

    /**
     * Changes a few keys of {@code config.yml} on disk, and the same keys in memory.
     *
     * <p>{@link #saveConfig()} writes the configuration as it was last read. Whatever an
     * administrator typed into the file since then and has not reloaded yet goes with it: setting
     * one location from a command used to undo an afternoon of edits. This reads the file as it is
     * now, applies the change to it and saves that.</p>
     *
     * @param change Sets the keys; called once on the file's content and once on the loaded one.
     * @return {@code false} when nothing was written — the file cannot be read, or could not be saved.
     */
    public boolean updateConfigFile(java.util.function.Consumer<FileConfiguration> change) {
        if (change == null) return false;
        if (configFile == null) {
            configFile = new File(dataFolder, "config.yml");
        }
        synchronized (configWriteLock) {
            // Deleted while the server runs: the file a fresh install gets comes first. Written
            // with the changed keys alone, it would be taken at the next start for a file its
            // administrator had emptied of everything else on purpose.
            if (!configFile.exists()) saveDefaultConfig();

            String problem = fr.xyness.XCore.Utils.YamlFiles.problem(configFile);
            if (problem != null) {
                logger.sendWarning("config.yml was not written: the file is not valid YAML ("
                        + fr.xyness.XCore.Utils.YamlFiles.forConsole(problem) + ") and stays as it is until that is fixed.");
                return false;
            }
            FileConfiguration disk = YamlConfiguration.loadConfiguration(configFile);
            change.accept(disk);
            try {
                disk.save(configFile);
            } catch (IOException e) {
                logger.sendWarning("Failed to save config.yml: " + e.getMessage());
                return false;
            }
            if (config != null) change.accept(config);
            return true;
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

        // A file that cannot be read is never merged into. It used to be copied aside and then
        // rebuilt from the defaults — without a single list, since the merge leaves those to the
        // administrator, nor any of the sections declared as theirs: one tab in front of an entry
        // and the exclusions, the caps and the levels of a configuration were gone at the next start.
        reloadConfig();
        if (configProblem != null) return;
        reportCopySetAside(configFile);

        // An empty file is a missing one: it gets the file a fresh install gets, comments and
        // all, rather than the same settings written back one key at a time.
        if (fr.xyness.XCore.Utils.YamlFiles.isBlank(configFile) && configFile.delete()) {
            saveDefaultConfig();
            logger.sendWarning("config.yml was empty: the default file was written in its place.");
            reloadConfig();
            return;
        }

        FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(configFile);
        // Renamed keys first: merged before, the new name would arrive with its default. A section
        // a rename creates is new to the administrator, and gets its lists like any new section.
        java.util.Set<String> sectionsBefore = fr.xyness.XCore.Utils.ConfigMerger.sectionsOf(diskConfig);
        boolean renamedAny = applyRenames("config.yml", diskConfig);
        java.util.Set<String> madeByRenames = fr.xyness.XCore.Utils.ConfigMerger.sectionsOf(diskConfig);
        madeByRenames.removeAll(sectionsBefore);
        try (InputStream defStream = bundled("config.yml")) {
            if (defStream == null) {
                if (renamedAny) diskConfig.save(configFile);
                return;
            }
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defStream, StandardCharsets.UTF_8));

            java.util.List<String> added = fr.xyness.XCore.Utils.ConfigMerger.addMissingKeys(
                    diskConfig, defConfig, madeByRenames, protectedSections);

            if (renamedAny || !added.isEmpty()) {
                diskConfig.save(configFile);
                if (!added.isEmpty()) logger.sendDebug("Added " + added.size() + " new setting(s) to config.yml.");
            }
        } catch (IOException e) {
            logger.sendError("Error updating config with defaults: " + e.getMessage());
        } finally {
            reloadConfig();
        }
    }

    /** Whether the copy an earlier version set aside has been pointed out since this start. */
    private boolean copySetAsideReported;

    /**
     * Points out the copy an earlier version made of a file before rebuilding it from the defaults.
     *
     * <p>Until 2.0.1 a file that could not be read was copied to {@code <name>.unreadable-<time>}
     * and written again from the bundled settings — without its lists, which the merge leaves to
     * the administrator. Nothing can tell today which lists such a file is missing, but the copy
     * still holds them; said at every start for as long as it is there.</p>
     *
     * @param file The file that may have been rebuilt.
     */
    private void reportCopySetAside(File file) {
        if (copySetAsideReported || file == null) return;
        String prefix = file.getName() + ".unreadable-";
        String[] copies = file.getParentFile() == null ? null
                : file.getParentFile().list((dir, name) -> name.startsWith(prefix));
        if (copies == null || copies.length == 0) return;
        copySetAsideReported = true;
        java.util.Arrays.sort(copies);
        String newest = copies[copies.length - 1];
        logger.sendWarning(newest + " is your " + file.getName() + " from before an earlier version rebuilt it after a"
                + " YAML mistake. What you had set is still in that copy, the lists above all: they were not"
                + " carried over. Put back what is missing, then delete the copy to end this notice.");
    }

    // -------------------------------------------------------------------------
    // Renamed keys
    // -------------------------------------------------------------------------

    /**
     * Declares keys renamed since an earlier version, so that the value an administrator gave the
     * old name follows it to the new one.
     *
     * <p>Without it, a renamed key looks like one key to add and one to delete: the new name is
     * written with its shipped default, the old one is pruned, and the administrator's value goes
     * with it. The renames are applied now to every matching file, and again before every later
     * merge or prune of one, so declare them before {@code updateConfigWithDefaults},
     * {@code loadLanguage} and {@code pruneObsoleteKeys}. See
     * {@link fr.xyness.XCore.Utils.ConfigMerger#moveRenamedKeys} for the rules: a value moves only
     * when the new key is absent, nested paths and whole sections work, chains are followed, and a
     * second run finds nothing to do. Each move is logged once, when it happens.</p>
     *
     * @param file    The file, relative to the data folder: {@code "config.yml"}, {@code "lang/fr.yml"},
     *                or with {@code *} in its last segment, {@code "lang/*.yml"} for every language file.
     * @param renames Old path → new path.
     * @return How many values moved now.
     */
    public int renamedKeys(String file, java.util.Map<String, String> renames) {
        if (file == null || file.isBlank() || renames == null || renames.isEmpty()) return 0;
        String pattern = normalize(file);
        java.util.Map<String, String> moves = new java.util.LinkedHashMap<>();
        renames.forEach((from, to) -> {
            if (from != null && to != null && !from.isBlank() && !to.isBlank()) moves.put(from.trim(), to.trim());
        });
        if (moves.isEmpty()) return 0;
        renamed.add(new Renamed(pattern, glob(pattern), java.util.Collections.unmodifiableMap(moves)));

        int moved = 0;
        boolean config = false;
        for (File target : filesMatching(pattern)) {
            int count = applyRenames(target);
            moved += count;
            if (count > 0 && "config.yml".equals(relative(target))) config = true;
        }
        // Whatever read config.yml before this call holds the old names.
        if (config) reloadConfig();
        return moved;
    }

    /**
     * Applies the declared renames to a file on disk, saving it when something moved.
     *
     * @param file The file.
     * @return How many values moved.
     */
    private int applyRenames(File file) {
        String path = relative(file);
        if (path == null || !file.isFile()) return 0;
        java.util.Map<String, String> moves = renamesFor(path);
        if (moves.isEmpty()) return 0;

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            // Unreadable: the merge that follows sets it aside and says so. Nothing to move here.
            return 0;
        }
        java.util.List<String> moved = fr.xyness.XCore.Utils.ConfigMerger.moveRenamedKeys(yaml, moves);
        if (moved.isEmpty()) return 0;
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.sendWarning("Could not save " + path + " after moving its renamed keys: " + e.getMessage());
            return 0;
        }
        logMoves(path, moved);
        return moved.size();
    }

    /**
     * Applies the declared renames to a file already loaded, leaving the save to the caller.
     *
     * @param path The file, relative to the data folder.
     * @param yaml Its content.
     * @return {@code true} when something moved.
     */
    private boolean applyRenames(String path, FileConfiguration yaml) {
        java.util.Map<String, String> moves = renamesFor(normalize(path));
        if (moves.isEmpty()) return false;
        java.util.List<String> moved = fr.xyness.XCore.Utils.ConfigMerger.moveRenamedKeys(yaml, moves);
        if (moved.isEmpty()) return false;
        logMoves(normalize(path), moved);
        return true;
    }

    /** Every rename declared for a file, later declarations overriding earlier ones. */
    private java.util.Map<String, String> renamesFor(String path) {
        if (renamed.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> moves = new java.util.LinkedHashMap<>();
        for (Renamed declaration : renamed) {
            if (declaration.files().matcher(path).matches()) moves.putAll(declaration.moves());
        }
        return moves;
    }

    private void logMoves(String path, java.util.List<String> moved) {
        logger.sendInfo("Moved " + moved.size() + " renamed key(s) in " + path + ", keeping the values set there:");
        for (String move : moved) logger.sendInfo("  - " + move);
    }

    /** The files of the data folder a declaration covers. */
    private java.util.List<File> filesMatching(String pattern) {
        int slash = pattern.lastIndexOf('/');
        String name = pattern.substring(slash + 1);
        if (!name.contains("*")) {
            File file = new File(dataFolder, pattern.replace('/', File.separatorChar));
            return file.isFile() ? java.util.List.of(file) : java.util.List.of();
        }
        File folder = slash < 0 ? dataFolder : new File(dataFolder, pattern.substring(0, slash).replace('/', File.separatorChar));
        File[] listed = folder.listFiles(File::isFile);
        if (listed == null) return java.util.List.of();
        java.util.regex.Pattern names = glob(name);
        java.util.List<File> files = new java.util.ArrayList<>();
        for (File file : listed) {
            if (names.matcher(file.getName()).matches()) files.add(file);
        }
        files.sort(java.util.Comparator.comparing(File::getName));
        return files;
    }

    /** A path written with {@code /}, without a leading {@code ./} or {@code /}. */
    private static String normalize(String path) {
        String clean = path.trim().replace('\\', '/');
        while (clean.startsWith("./")) clean = clean.substring(2);
        while (clean.startsWith("/")) clean = clean.substring(1);
        return clean;
    }

    /** {@code lang/*.yml} as a regular expression: {@code *} stands for any run of characters but {@code /}. */
    private static java.util.regex.Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        int start = 0;
        for (int star = pattern.indexOf('*'); star >= 0; star = pattern.indexOf('*', start)) {
            if (star > start) regex.append(java.util.regex.Pattern.quote(pattern.substring(start, star)));
            regex.append("[^/]*");
            start = star + 1;
        }
        if (start < pattern.length()) regex.append(java.util.regex.Pattern.quote(pattern.substring(start)));
        return java.util.regex.Pattern.compile(regex.toString());
    }

    /** A file's path relative to the data folder, written with {@code /}; {@code null} outside it. */
    private String relative(File file) {
        try {
            java.nio.file.Path base = dataFolder.toPath().toAbsolutePath().normalize();
            java.nio.file.Path target = file.toPath().toAbsolutePath().normalize();
            if (!target.startsWith(base)) return null;
            return base.relativize(target).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return null;
        }
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

        // Not valid YAML: nothing in it can be told obsolete, and it is not to be written.
        String problem = fr.xyness.XCore.Utils.YamlFiles.problem(file);
        if (problem != null) {
            boolean alreadySaid = "config.yml".equals(normalize(fileName)) && configProblem != null;
            if (!alreadySaid) {
                logger.sendError(fileName + " is not valid YAML: " + fr.xyness.XCore.Utils.YamlFiles.forConsole(problem)
                        + ". The file was left untouched.");
            }
            return 0;
        }

        FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(file);
        // A renamed key is not obsolete: its value moves to the new name before anything is pruned.
        boolean renamedAny = applyRenames(fileName, diskConfig);
        try (InputStream defStream = bundled(fileName)) {
            if (defStream == null) {
                logger.sendDebug("No bundled " + fileName + " to compare against; nothing pruned.");
                if (renamedAny) saveRenamed(fileName, diskConfig, file);
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

            if (removed.isEmpty()) {
                if (renamedAny) saveRenamed(fileName, diskConfig, file);
                return 0;
            }

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
     * Writes a file whose only change is renamed keys, and re-reads it when it is the configuration.
     */
    private void saveRenamed(String fileName, FileConfiguration content, File file) throws IOException {
        content.save(file);
        if ("config.yml".equals(normalize(fileName))) reloadConfig();
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
        String problem = fr.xyness.XCore.Utils.YamlFiles.problem(target);
        if (problem != null) {
            logger.sendError(resourcePath + " is not valid YAML: " + fr.xyness.XCore.Utils.YamlFiles.forConsole(problem)
                    + ". The file was left untouched.");
            return;
        }
        try (InputStream defaults = bundled(resourcePath)) {
            if (defaults == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8));
            YamlConfiguration current = YamlConfiguration.loadConfiguration(target);
            boolean changed = applyRenames(resourcePath, current);
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
        java.util.Set<String> renamedFiles = new java.util.HashSet<>();
        for (String code : available) {
            saveDefaultResource("lang/" + code + ".yml");
            saveDefaultResource("lang/web_" + code + ".yml");
            // Every translation on disk, not only the one loaded below: the others are pruned too,
            // by whoever calls pruneObsoleteKeys on them, and a text renamed there was lost the same way.
            applyRenames(new File(langFolder, code + ".yml"));
            renamedFiles.add(code.toLowerCase() + ".yml");
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

        // A translation the administrator added themselves (lang/de.yml): its keys were renamed too.
        if (!renamedFiles.contains(target.getName().toLowerCase())) applyRenames(target);

        boolean inJar = false;
        try (InputStream defaults = bundled("lang/" + wanted + ".yml")) {
            inJar = defaults != null;
            lang.reload(target, defaults);
        } catch (IOException e) {
            logger.sendError("Failed to load language '" + wanted + "': " + e.getMessage());
        }
        if (lang.fileProblem() != null) {
            logger.sendError("lang/" + wanted + ".yml is not valid YAML: "
                    + fr.xyness.XCore.Utils.YamlFiles.forConsole(lang.fileProblem()));
            if (inJar) {
                logger.sendError("The file was left untouched. The bundled texts are used until it is fixed.");
                return;
            }
            // A translation of the administrator's own: the jar has no texts for it, and every
            // message was shown as its key. The first bundled language speaks meanwhile.
            String fallback = available.length > 0 ? available[0] : "en";
            try (InputStream texts = bundled("lang/" + fallback + ".yml")) {
                lang.fillFrom(texts);
            } catch (IOException e) {
                logger.sendError("Failed to load language '" + fallback + "': " + e.getMessage());
            }
            logger.sendError("The file was left untouched. The '" + fallback + "' texts are used until it is fixed.");
        }
    }
}
