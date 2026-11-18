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
 *   <li>Skip, with a console error saying what to do, the jars that are server plugins and the
 *       jars of addons that are plugins since 2.0 (XAntiLag, XAuctionHouse, XBans, XLogin) or whose
 *       name an installed {@link fr.xyness.XCore.Extension.XPlugin} carries</li>
 *   <li>Parse {@code addon.yml} from each JAR via {@link AddonClassLoader}</li>
 *   <li>Sort addons by dependencies (topological sort)</li>
 *   <li>Create class loaders and instantiate main classes</li>
 *   <li>Inject dependencies via {@link XAddon#init(AddonDescriptor, XCore, File, Logger, LangNamespace, GuiRegistry)}</li>
 *   <li>Call {@link XAddon#onLoad()}, set state to {@link AddonState#LOADED}</li>
 * </ol>
 * </p>
 */
public class AddonManager {

    /**
     * The addons that are plugins of their own since XCore 2.0, in {@code plugins/}.
     *
     * <p>Their 1.x jars are never loaded, whether or not the 2.0 plugin is installed. With it, both
     * would run: two sets of listeners and timers working on the same tables. Without it, the 1.x
     * jar would keep its files in {@code plugins/XCore/addons/<name>/}, which the 2.0 plugin moves
     * to {@code plugins/<name>/} on its first start — two versions and two folders, and no way to
     * tell from the console which one holds the current settings. One rule, stated plainly at
     * startup, is easier to act on.</p>
     */
    private static final Set<String> PLUGINS_SINCE_2_0 = Set.of("XAntiLag", "XAuctionHouse", "XBans", "XLogin");

    private final XCore core;
    private final Logger logger = new Logger("AddonManager");
    private final Map<String, XAddon> addons = new LinkedHashMap<>();
    private final Map<String, AddonState> states = new ConcurrentHashMap<>();
    private final Map<String, AddonClassLoader> classLoaders = new HashMap<>();

    /** Jars of the addons folder that were deliberately not loaded, by file name, with the reason. */
    private final Map<String, String> refused = new LinkedHashMap<>();

    /**
     * Whether XLogin, which authenticates an offline-mode server, was refused here with nothing
     * installed to replace it. XCore then closes the server itself, see
     * {@link #needsLoginLockdown()}.
     */
    private boolean loginUnprotected;

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

        // Phase 1: Parse all descriptors
        Map<String, AddonClassLoader> pendingLoaders = new HashMap<>();
        Map<String, AddonDescriptor> descriptors = new HashMap<>();

        for (File jar : jars) {
            // A server plugin dropped here — the 2.0 jar of one of ours, typically, put where the
            // 1.x jar used to go. Without this it was reported as an addon JAR missing its
            // addon.yml, which sends the administrator looking for a file that should not exist.
            if (refuseMisplacedPlugin(jar)) continue;
            try {
                AddonClassLoader loader = new AddonClassLoader(jar, core.getClass().getClassLoader());
                AddonDescriptor desc = loader.getDescriptor();

                if (refuseReplacedAddon(desc, jar)) {
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

            // Check hard dependencies
            boolean missingDep = false;
            for (String dep : desc.getDepend()) {
                if (!descriptors.containsKey(dep)) {
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
     * Whether the server is left without authentication: the XLogin addon was refused (it is a
     * plugin since 2.0), no XLogin plugin is installed in its place, and it was not switched off in
     * {@code addons.yml}.
     *
     * @return {@code true} when XCore must refuse connections itself.
     */
    public boolean needsLoginLockdown() {
        return loginUnprotected;
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
     * Skips a jar that is a server plugin rather than an addon, and says where it belongs.
     *
     * @param jar The jar found in the addons folder.
     * @return {@code true} when it was skipped.
     */
    private boolean refuseMisplacedPlugin(File jar) {
        String pluginName;
        try (JarFile file = new JarFile(jar)) {
            if (file.getJarEntry("addon.yml") != null) return false;
            JarEntry descriptor = file.getJarEntry("paper-plugin.yml");
            if (descriptor == null) descriptor = file.getJarEntry("plugin.yml");
            if (descriptor == null) return false;
            try (InputStreamReader reader = new InputStreamReader(file.getInputStream(descriptor), StandardCharsets.UTF_8)) {
                pluginName = YamlConfiguration.loadConfiguration(reader).getString("name", jar.getName());
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable: the regular path reports it.
            return false;
        }
        String reason = "a server plugin (" + pluginName + "), not an addon";
        refused.put(jar.getName(), reason);
        logger.sendError(jar.getName() + " was not loaded: it is " + reason + ".");
        if (Bukkit.getPluginManager().getPlugin(pluginName) != null) {
            logger.sendError("  " + pluginName + " is already installed in plugins/: delete "
                    + fr.xyness.XCore.Extension.DataFolderMigration.displayPath(jar) + ".");
        } else {
            logger.sendError("  Move it to plugins/, the folder XCore's own jar is in, and restart.");
        }
        // The XLogin plugin put here instead of plugins/ leaves the server as open as no XLogin at all.
        if ("XLogin".equalsIgnoreCase(pluginName) && core.isAddonEnabled("XLogin")
                && !(Bukkit.getPluginManager().getPlugin("XLogin") instanceof XPlugin)) {
            loginUnprotected = true;
        }
        return true;
    }

    /**
     * Skips the jar of an addon that is now a plugin of its own: one of the four that became
     * plugins in XCore 2.0, or any addon whose name an installed plugin built on XCore already
     * carries — both copies running at once is never what was meant.
     *
     * @param desc The parsed {@code addon.yml}.
     * @param jar  The jar.
     * @return {@code true} when it was skipped.
     */
    private boolean refuseReplacedAddon(AddonDescriptor desc, File jar) {
        String name = desc.getName();
        Plugin installed = Bukkit.getPluginManager().getPlugin(name);
        boolean pluginInstalled = installed instanceof XPlugin;
        boolean ours = PLUGINS_SINCE_2_0.stream().anyMatch(n -> n.equalsIgnoreCase(name));
        if (!ours && !pluginInstalled) return false;

        String jarPath = fr.xyness.XCore.Extension.DataFolderMigration.displayPath(jar);
        if (pluginInstalled) {
            String version = installed.getPluginMeta().getVersion();
            refused.put(jar.getName(), name + " " + version + " is installed as a plugin");
            if (ours) {
                logger.sendError(jar.getName() + " was not loaded: " + name + " is a plugin since XCore 2.0, and "
                        + name + " " + version + " is already installed in plugins/.");
            } else {
                logger.sendError(jar.getName() + " was not loaded: " + name + " " + version
                        + " is installed in plugins/ as a plugin built on XCore, and both cannot run at once.");
            }
            logger.sendError("  Delete " + jarPath + ": the plugin has replaced it.");
        } else {
            refused.put(jar.getName(), name + " is a plugin since XCore 2.0");
            logger.sendError(jar.getName() + " was not loaded: " + name
                    + " is a plugin since XCore 2.0, not an addon.");
            logger.sendError("  Put the " + name + " 2.0 jar in plugins/, delete " + jarPath
                    + ", and restart. Its settings and data are picked up as they are.");
            if ("XLogin".equalsIgnoreCase(name) && core.isAddonEnabled(name)) loginUnprotected = true;
        }
        return true;
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
