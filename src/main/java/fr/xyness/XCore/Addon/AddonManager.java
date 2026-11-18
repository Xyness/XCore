package fr.xyness.XCore.Addon;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Extension.XExtension;
import fr.xyness.XCore.Extension.XPlugin;
import fr.xyness.XCore.Gui.GuiRegistry;
import fr.xyness.XCore.Lang.LangNamespace;
import fr.xyness.XCore.Utils.Logger;

/**
 * Manages the lifecycle of XCore addons.
 * <p>
 * Addons are JAR files placed in {@code plugins/XCore/addons/}. Each JAR must contain
 * an {@code addon.yml} descriptor that declares the addon's name, main class, version,
 * and dependency information.
 * </p>
 * <p>
 * The loading process:
 * <ol>
 *   <li>Scan the addons folder for {@code .jar} files</li>
 *   <li>Skip, with a console error saying what to do, the jars that are server plugins, the 1.x
 *       jars of the addons that are plugins since 2.0 (XAntiLag, XAuctionHouse, XBans, XLogin) when
 *       their 2.0 plugin is there or has run here (see {@link #PLUGINS_SINCE_2_0}), and the jars of
 *       addons whose name an installed {@link fr.xyness.XCore.Extension.XPlugin} carries</li>
 *   <li>Parse {@code addon.yml} from each JAR via {@link AddonClassLoader}</li>
 *   <li>Sort addons by dependencies (topological sort)</li>
 *   <li>Create class loaders and instantiate main classes</li>
 *   <li>Inject dependencies via {@link XAddon#init(AddonDescriptor, XCore, File, Logger, LangNamespace, GuiRegistry)}</li>
 *   <li>Call {@link XAddon#onLoad()}, set state to {@link AddonState#LOADED}</li>
 * </ol>
 * </p>
 * <p>
 * A {@code depend} entry is met by an addon of that name, or by a plugin of that name installed in
 * {@code plugins/}. The four addons that became plugins in 2.0 are the second kind now, and they
 * enable after XCore — so after every addon: an addon depending on one of them finds its classes and
 * its instance during its own {@code onEnable()}, but that plugin is not running yet. Use it from
 * {@code ServerLoadEvent} on, or check {@code isEnabled()} first.
 * </p>
 */
public class AddonManager {

    /**
     * The addons that are plugins of their own since XCore 2.0, in {@code plugins/}.
     *
     * <p>Their 1.x jars still run on XCore 2.0, and are loaded — with a warning to replace them —
     * unless the 2.0 plugin makes them wrong to run:</p>
     * <ul>
     *   <li>it is installed: both would run, two sets of listeners and timers on the same tables;</li>
     *   <li>its jar sits in the addons folder too: the administrator is moving to it, and the console
     *       says where it goes;</li>
     *   <li>{@code plugins/<name>/} holds files: the 2.0 plugin has run here and moved the settings
     *       and data there, so the 1.x jar — put back after it, typically — would start on an old or
     *       empty folder.</li>
     * </ul>
     * <p>Refusing them in every case, as this first did, was worse than running them: the order
     * XCore's notes give is XCore first, then the plugins, and between the two a server that had
     * XBans enforced no ban at all, since XBans enforces them in its own listener.</p>
     */
    private static final Set<String> PLUGINS_SINCE_2_0 = Set.of("XAntiLag", "XAuctionHouse", "XBans", "XLogin");

    private final XCore core;
    private final Logger logger = new Logger("AddonManager");
    private final Map<String, XAddon> addons = new LinkedHashMap<>();
    private final Map<String, AddonState> states = new ConcurrentHashMap<>();
    private final Map<String, AddonClassLoader> classLoaders = new HashMap<>();

    /** Jars of the addons folder that were deliberately not loaded, by file name, with the reason. */
    private final Map<String, String> refused = new LinkedHashMap<>();

    /** The name of every jar found in the addons folder, addon or misplaced plugin, lower-cased. */
    private final Set<String> found = new HashSet<>();

    /**
     * Creates a new AddonManager.
     *
     * @param core The XCore plugin instance.
     */
    public AddonManager(XCore core) {
        this.core = core;
    }

    /**
     * Scans the addons folder, loads all addon JARs, and calls {@link XAddon#onLoad()}.
     * Addons are loaded in dependency order.
     */
    public void loadAddons() {
        File addonsDir = new File(core.getDataFolder(), "addons");
        if (!addonsDir.exists()) addonsDir.mkdirs();

        File[] jars = addonsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            // Say so rather than returning silently: the caller frames this section with
            // separator bars, and an empty frame reads like something went wrong.
            logger.sendInfo("No addon found in " + addonsDir.getPath() + ".");
            return;
        }

        // Phase 0: the server plugins among them — the 2.0 jar of one of ours, typically, put where
        // the 1.x jar used to go. Read before anything is loaded: one being here decides what
        // becomes of the 1.x jar beside it, whichever of the two the folder lists first.
        Map<File, String> misplaced = new LinkedHashMap<>();
        for (File jar : jars) {
            String pluginName = serverPluginName(jar);
            if (pluginName != null) misplaced.put(jar, pluginName);
        }
        Set<String> misplacedNames = new HashSet<>();
        for (String pluginName : misplaced.values()) misplacedNames.add(pluginName.toLowerCase(java.util.Locale.ROOT));
        found.addAll(misplacedNames);

        // Phase 1: Parse all descriptors
        Map<String, AddonClassLoader> pendingLoaders = new HashMap<>();
        Map<String, AddonDescriptor> descriptors = new HashMap<>();

        for (File jar : jars) {
            // Without this it was reported as an addon JAR missing its addon.yml, which sends the
            // administrator looking for a file that should not exist.
            String pluginName = misplaced.get(jar);
            if (pluginName != null) {
                refuseMisplacedPlugin(jar, pluginName);
                continue;
            }
            try {
                AddonClassLoader loader = new AddonClassLoader(jar, core.getClass().getClassLoader());
                AddonDescriptor desc = loader.getDescriptor();
                found.add(desc.getName().toLowerCase(java.util.Locale.ROOT));

                if (refuseReplacedAddon(desc, jar, misplacedNames)) {
                    loader.close();
                    continue;
                }

                if (descriptors.containsKey(desc.getName())) {
                    logger.sendWarning("Duplicate addon name '" + desc.getName() + "' in " + jar.getName() + ", skipping.");
                    loader.close();
                    continue;
                }

                // Register toggle in config (default: true)
                core.registerAddonToggle(desc.getName());

                // Check if addon is disabled in config
                if (!core.isAddonEnabled(desc.getName())) {
                    logger.sendInfo("Addon '" + desc.getName() + "' is disabled in config, skipping.");
                    states.put(desc.getName(), AddonState.DISABLED);
                    loader.close();
                    continue;
                }

                pendingLoaders.put(desc.getName(), loader);
                descriptors.put(desc.getName(), desc);
            } catch (IOException e) {
                logger.sendError("Failed to read addon JAR " + jar.getName() + ": " + e.getMessage());
            } catch (IllegalArgumentException e) {
                logger.sendError("Invalid addon JAR " + jar.getName() + ": " + e.getMessage());
            }
        }

        // Phase 2: Topological sort by depend
        List<String> sorted = topologicalSort(descriptors);

        // Phase 3: Load each addon in order
        for (String name : sorted) {
            AddonClassLoader loader = pendingLoaders.get(name);
            AddonDescriptor desc = descriptors.get(name);

            // Check hard dependencies: an addon, or a plugin installed in plugins/ — which is what
            // XAntiLag, XAuctionHouse, XBans and XLogin are since 2.0. "depend: [XAntiLag]" failed
            // with XAntiLag installed and running, once it was no longer an addon.
            boolean missingDep = false;
            for (String dep : desc.getDepend()) {
                if (!descriptors.containsKey(dep) && Bukkit.getPluginManager().getPlugin(dep) == null) {
                    logger.sendError("Addon '" + name + "' requires missing dependency: " + dep);
                    missingDep = true;
                }
            }
            if (missingDep) {
                states.put(name, AddonState.ERRORED);
                closeLoaderSilently(loader);
                continue;
            }

            try {
                Class<?> mainClass = loader.loadClass(desc.getMain());
                Object instance = mainClass.getDeclaredConstructor().newInstance();

                if (!(instance instanceof XAddon addon)) {
                    logger.sendError("Main class of addon '" + name + "' does not extend XAddon.");
                    states.put(name, AddonState.ERRORED);
                    closeLoaderSilently(loader);
                    continue;
                }

                File dataFolder = new File(core.getDataFolder(), "addons" + File.separator + name);
                Logger addonLogger = new Logger(name);
                LangNamespace langNamespace = new LangNamespace();
                GuiRegistry guiRegistry = new GuiRegistry();

                addon.init(desc, core, dataFolder, addonLogger, langNamespace, guiRegistry);

                try {
                    addon.onLoad();
                } catch (Throwable e) {
                    logger.sendError("Error in onLoad() for addon '" + name + "': " + e);
                    states.put(name, AddonState.ERRORED);
                    closeLoaderSilently(loader);
                    continue;
                }

                addons.put(name, addon);
                classLoaders.put(name, loader);
                states.put(name, AddonState.LOADED);
                logger.sendInfo("Loaded addon: " + desc);

            } catch (ClassNotFoundException e) {
                logger.sendError("Main class not found for addon '" + name + "': " + desc.getMain());
                states.put(name, AddonState.ERRORED);
                closeLoaderSilently(loader);
            } catch (Exception e) {
                logger.sendError("Failed to instantiate addon '" + name + "': " + e.getMessage());
                states.put(name, AddonState.ERRORED);
                closeLoaderSilently(loader);
            }
        }
    }

    /**
     * Enables all loaded addons in dependency order.
     * Creates data folders and calls {@link XAddon#onEnable()}.
     */
    public void enableAddons() {
        for (Map.Entry<String, XAddon> entry : addons.entrySet()) {
            String name = entry.getKey();
            XAddon addon = entry.getValue();

            if (states.get(name) != AddonState.LOADED) continue;

            File dataFolder = addon.getDataFolder();
            if (!dataFolder.exists()) dataFolder.mkdirs();

            // The addon loads its own messages with loadLanguage() in onEnable().

            // Register addon in cross-server sync config
            core.registerSyncAddon(name);

            states.put(name, AddonState.ENABLING);
            try {
                boolean success = addon.onEnable();
                if (success) {
                    states.put(name, AddonState.ENABLED);
                    logger.sendInfo("Enabled addon: " + addon.getDescriptor());
                    // XCore refuses players until XLogin is up, and the 1.x addon counts as much as
                    // the plugin.
                    core.extensionEnabled(name);
                } else {
                    states.put(name, AddonState.ERRORED);
                    logger.sendError("Addon '" + name + "' returned false from onEnable().");
                }
            } catch (Throwable e) {
                // Throwable: an addon built against another XCore or another server fails with a
                // LinkageError, which is not an Exception. Left uncaught it ended XCore's own enable,
                // and with it every addon after this one.
                states.put(name, AddonState.ERRORED);
                logger.sendError("Error enabling addon '" + name + "': " + e);
            }
        }
    }

    /**
     * Disables all enabled addons in reverse dependency order.
     * Calls {@link XAddon#onDisable()} and closes class loaders.
     */
    public void disableAddons() {
        List<String> names = new ArrayList<>(addons.keySet());
        Collections.reverse(names);

        for (String name : names) {
            XAddon addon = addons.get(name);
            AddonState state = states.get(name);
            if (state != AddonState.ENABLED) continue;

            states.put(name, AddonState.DISABLING);
            try {
                addon.onDisable();
            } catch (Exception e) {
                logger.sendError("Error disabling addon '" + name + "': " + e.getMessage());
            }
            // Unregister all Bukkit listeners belonging to this addon
            core.getListenerRegistry().unregisterAll(name);
            // And what follows its players across a UUID change: its class loader closes below.
            if (core.uuidChanges() != null) core.uuidChanges().unregisterAll(name);
            states.put(name, AddonState.DISABLED);
            logger.sendInfo("Disabled addon: " + name);
        }

        // Close all class loaders
        for (Map.Entry<String, AddonClassLoader> entry : classLoaders.entrySet()) {
            closeLoaderSilently(entry.getValue());
        }

        addons.clear();
        classLoaders.clear();
        states.clear();
    }

    /**
     * Reloads a single addon by name.
     * Calls {@link XAddon#onReload()} on the addon.
     *
     * @param name The addon name (case-sensitive).
     */
    public void reloadAddon(String name) {
        XAddon addon = addons.get(name);
        if (addon == null) {
            logger.sendWarning("Addon '" + name + "' not found for reload.");
            return;
        }
        AddonState state = states.get(name);
        if (state != AddonState.ENABLED) {
            logger.sendWarning("Addon '" + name + "' is not enabled (state: " + state + "), cannot reload.");
            return;
        }
        try {
            addon.onReload();
            logger.sendInfo("Reloaded addon: " + name);
        } catch (Exception e) {
            logger.sendError("Error reloading addon '" + name + "': " + e.getMessage());
        }
    }

    /**
     * Returns an unmodifiable view of all loaded addons.
     *
     * @return A map of addon name to {@link XAddon} instance.
     */
    public Map<String, XAddon> getAddons() {
        return Collections.unmodifiableMap(addons);
    }

    /**
     * Returns the addon with the given name, if loaded.
     *
     * @param name The addon name.
     * @return An optional containing the addon, or empty.
     */
    public Optional<XAddon> getAddon(String name) {
        return Optional.ofNullable(addons.get(name));
    }

    /**
     * Returns the current state of the given addon.
     *
     * @param name The addon name.
     * @return The {@link AddonState}, or {@code null} if not tracked.
     */
    public AddonState getState(String name) {
        return states.get(name);
    }

    /**
     * Returns the loaded addons as XCore extensions, in load order: what {@code /xcore addons},
     * the reload and the diagnostics list, next to the plugins built on XCore.
     *
     * @return A new list of views over the addons.
     */
    public List<XExtension> extensions() {
        List<XExtension> out = new ArrayList<>(addons.size());
        for (Map.Entry<String, XAddon> entry : addons.entrySet()) {
            out.add(new AddonView(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    /**
     * Returns the jars that were found in the addons folder and deliberately not loaded.
     *
     * @return File name to reason, in the order they were met.
     */
    public Map<String, String> getRefused() {
        return Collections.unmodifiableMap(refused);
    }

    /**
     * Whether a jar of this name — an addon, loaded or not, or a plugin put here by mistake — was
     * found in the addons folder.
     *
     * @param name The name, compared ignoring case.
     * @return {@code true} when one was.
     */
    public boolean foundJarOf(String name) {
        return name != null && found.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** One addon, seen as an XCore extension. */
    private final class AddonView implements XExtension {

        private final String name;
        private final XAddon addon;

        private AddonView(String name, XAddon addon) {
            this.name = name;
            this.addon = addon;
        }

        @Override public String name() { return name; }
        @Override public String version() { return addon.getDescriptor().getVersion(); }
        @Override public String author() { return addon.getDescriptor().getAuthor(); }
        @Override public String description() { return addon.getDescriptor().getDescription(); }
        @Override public Kind kind() { return Kind.ADDON; }

        @Override
        public AddonState state() {
            AddonState state = states.get(name);
            return state == null ? AddonState.ERRORED : state;
        }

        @Override public File dataFolder() { return addon.getDataFolder(); }
        @Override public fr.xyness.XCore.Utils.Updater updater() { return addon.updater(); }

        /** The hook itself, so a failure reaches the caller instead of stopping at a log line. */
        @Override public void reload() { addon.onReload(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof AddonView view && view.addon == addon;
        }

        @Override
        public int hashCode() { return System.identityHashCode(addon); }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the name of a jar that is a server plugin rather than an addon.
     *
     * @param jar A jar found in the addons folder.
     * @return The name in its {@code paper-plugin.yml} or {@code plugin.yml}; {@code null} for an
     *         addon, or a jar that cannot be read (the regular path reports it).
     */
    private static String serverPluginName(File jar) {
        try (JarFile file = new JarFile(jar)) {
            if (file.getJarEntry("addon.yml") != null) return null;
            JarEntry descriptor = file.getJarEntry("paper-plugin.yml");
            if (descriptor == null) descriptor = file.getJarEntry("plugin.yml");
            if (descriptor == null) return null;
            try (InputStreamReader reader = new InputStreamReader(file.getInputStream(descriptor), StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader).getString("name", jar.getName());
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Skips a jar that is a server plugin rather than an addon, and says where it belongs.
     *
     * @param jar        The jar found in the addons folder.
     * @param pluginName The name it declares.
     */
    private void refuseMisplacedPlugin(File jar, String pluginName) {
        String reason = "a server plugin (" + pluginName + "), not an addon";
        refused.put(jar.getName(), reason);
        logger.sendError(jar.getName() + " was not loaded: it is " + reason + ".");
        if (Bukkit.getPluginManager().getPlugin(pluginName) != null) {
            logger.sendError("  " + pluginName + " is already installed in plugins/: delete "
                    + fr.xyness.XCore.Extension.DataFolderMigration.displayPath(jar) + ".");
        } else {
            logger.sendError("  Move it to plugins/, the folder XCore's own jar is in, and restart.");
        }
    }

    /**
     * Skips the jar of an addon that must not run beside the plugin it became: one of the four that
     * are plugins since XCore 2.0, when that plugin is installed, waits in the addons folder or has
     * already run here (see {@link #PLUGINS_SINCE_2_0}), or any addon whose name an installed plugin
     * built on XCore already carries — both copies running at once is never what was meant. The
     * 1.x jar of one of the four that is still on its own is loaded, with a warning.
     *
     * @param desc           The parsed {@code addon.yml}.
     * @param jar            The jar.
     * @param misplacedNames The plugins found in the addons folder, lower-cased.
     * @return {@code true} when it was skipped.
     */
    private boolean refuseReplacedAddon(AddonDescriptor desc, File jar, Set<String> misplacedNames) {
        String name = desc.getName();
        Plugin installed = Bukkit.getPluginManager().getPlugin(name);
        String ours = PLUGINS_SINCE_2_0.stream().filter(n -> n.equalsIgnoreCase(name)).findFirst().orElse(null);
        String jarPath = fr.xyness.XCore.Extension.DataFolderMigration.displayPath(jar);

        if (ours == null) {
            if (!(installed instanceof XPlugin)) return false;
            String version = installed.getPluginMeta().getVersion();
            refused.put(jar.getName(), name + " " + version + " is installed as a plugin");
            logger.sendError(jar.getName() + " was not loaded: " + name + " " + version
                    + " is installed in plugins/ as a plugin built on XCore, and both cannot run at once.");
            logger.sendError("  Delete " + jarPath + ": the plugin has replaced it.");
            return true;
        }

        if (installed != null) {
            String version = installed.getPluginMeta().getVersion();
            refused.put(jar.getName(), ours + " " + version + " is installed as a plugin");
            logger.sendError(jar.getName() + " was not loaded: " + ours + " is a plugin since XCore 2.0, and "
                    + ours + " " + version + " is already installed in plugins/.");
            logger.sendError("  Delete " + jarPath + ": the plugin has replaced it.");
            return true;
        }
        if (misplacedNames.contains(ours.toLowerCase(java.util.Locale.ROOT))) {
            refused.put(jar.getName(), ours + " 2.0 is in the addons folder too");
            logger.sendError(jar.getName() + " was not loaded: the " + ours + " 2.0 jar is beside it, in the"
                    + " addons folder, where it cannot run.");
            logger.sendError("  Move the " + ours + " 2.0 jar to plugins/, delete " + jarPath
                    + ", and restart. Its settings and data are picked up as they are.");
            return true;
        }
        File pluginFolder = new File(core.getDataFolder().getParentFile(), ours);
        if (holdsFiles(pluginFolder)) {
            String folderShown = fr.xyness.XCore.Extension.DataFolderMigration.displayPath(pluginFolder) + "/";
            refused.put(jar.getName(), folderShown + " holds the files of " + ours + " 2.0");
            logger.sendError(jar.getName() + " was not loaded: " + folderShown + " exists, so " + ours
                    + " 2.0 has run on this server and its settings and data are there now. This jar would"
                    + " start without them, on the folder the addon used.");
            logger.sendError("  Put the " + ours + " 2.0 jar back in plugins/ and delete " + jarPath
                    + ". To stay on the addon instead, move " + folderShown + " back to "
                    + fr.xyness.XCore.Extension.DataFolderMigration.displayPath(new File(core.getDataFolder(),
                            "addons" + File.separator + ours)) + "/ (in place of what is left there) and restart.");
            return true;
        }

        // Still on its own: it runs, on XCore 2.0 as on 1.x. Refused, it took its features with it —
        // XBans' bans, XLogin's passwords — until the administrator got to the 2.0 jar.
        logger.sendWarning(jar.getName() + " is the " + ours + " addon of XCore 1.x. It still runs on XCore 2.0,"
                + " but " + ours + " is a plugin since 2.0: replace it with " + ours + " 2.0 in plugins/, then delete "
                + jarPath + ". Its settings and data are picked up as they are.");
        return false;
    }

    /** Whether a folder exists and holds anything. */
    private static boolean holdsFiles(File folder) {
        String[] entries = folder.list();
        return entries != null && entries.length > 0;
    }

    /**
     * Performs a topological sort of addon names based on their {@code depend} lists.
     * Addons with no dependencies come first; cyclic dependencies are detected and reported.
     */
    private List<String> topologicalSort(Map<String, AddonDescriptor> descriptors) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String name : descriptors.keySet()) {
            if (!visited.contains(name)) {
                if (!visit(name, descriptors, visited, visiting, sorted)) {
                    logger.sendError("Cyclic dependency detected involving addon: " + name);
                }
            }
        }

        return sorted;
    }

    /**
     * DFS visit for topological sort.
     *
     * @return {@code false} if a cycle is detected.
     */
    private boolean visit(String name, Map<String, AddonDescriptor> descriptors,
                          Set<String> visited, Set<String> visiting, List<String> sorted) {
        if (visiting.contains(name)) return false; // cycle
        if (visited.contains(name)) return true;

        visiting.add(name);
        AddonDescriptor desc = descriptors.get(name);
        if (desc != null) {
            for (String dep : desc.getDepend()) {
                if (descriptors.containsKey(dep)) {
                    if (!visit(dep, descriptors, visited, visiting, sorted)) return false;
                }
            }
            for (String dep : desc.getSoftDepend()) {
                if (descriptors.containsKey(dep)) {
                    if (!visit(dep, descriptors, visited, visiting, sorted)) return false;
                }
            }
        }

        visiting.remove(name);
        visited.add(name);
        sorted.add(name);
        return true;
    }

    /**
     * Closes a class loader, suppressing any IOException.
     */
    private void closeLoaderSilently(AddonClassLoader loader) {
        try {
            if (loader != null) loader.close();
        } catch (IOException e) {
            java.util.logging.Logger.getLogger("XCore").warning("Failed to close addon class loader: " + e.getMessage());
        }
    }
}
