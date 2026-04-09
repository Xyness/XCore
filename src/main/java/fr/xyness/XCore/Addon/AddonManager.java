package fr.xyness.XCore.Addon;

import java.io.File;
import java.io.IOException;
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

import fr.xyness.XCore.XCore;
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
 *   <li>Parse {@code addon.yml} from each JAR via {@link AddonClassLoader}</li>
 *   <li>Sort addons by dependencies (topological sort)</li>
 *   <li>Create class loaders and instantiate main classes</li>
 *   <li>Inject dependencies via {@link XAddon#init(AddonDescriptor, XCore, File, Logger, LangNamespace, GuiRegistry)}</li>
 *   <li>Call {@link XAddon#onLoad()}, set state to {@link AddonState#LOADED}</li>
 * </ol>
 * </p>
 */
public class AddonManager {

    private final XCore core;
    private final Logger logger = new Logger("AddonManager");
    private final Map<String, XAddon> addons = new LinkedHashMap<>();
    private final Map<String, AddonState> states = new ConcurrentHashMap<>();
    private final Map<String, AddonClassLoader> classLoaders = new HashMap<>();

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
        if (!addonsDir.exists()) {
            addonsDir.mkdirs();
            return;
        }

        File[] jars = addonsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) return;

        // Phase 1: Parse all descriptors
        Map<String, AddonClassLoader> pendingLoaders = new HashMap<>();
        Map<String, AddonDescriptor> descriptors = new HashMap<>();

        for (File jar : jars) {
            try {
                AddonClassLoader loader = new AddonClassLoader(jar, core.getClass().getClassLoader());
                AddonDescriptor desc = loader.getDescriptor();

                if (descriptors.containsKey(desc.getName())) {
                    logger.sendWarning("Duplicate addon name '" + desc.getName() + "' in " + jar.getName() + ", skipping.");
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
                } catch (Exception e) {
                    logger.sendError("Error in onLoad() for addon '" + name + "': " + e.getMessage());
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

            // Load lang.yml: save default from JAR if missing, then load
            File langFile = new File(dataFolder, "lang.yml");
            if (!langFile.exists()) {
                addon.saveResource("lang.yml", false);
            }
            java.io.InputStream defaults = addon.getClass().getClassLoader().getResourceAsStream("lang.yml");
            addon.lang().reload(langFile, defaults);

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
            } catch (Exception e) {
                states.put(name, AddonState.ERRORED);
                logger.sendError("Error enabling addon '" + name + "': " + e.getMessage());
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

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

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
