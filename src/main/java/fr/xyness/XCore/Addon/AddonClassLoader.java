package fr.xyness.XCore.Addon;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Custom {@link URLClassLoader} for loading addon JARs.
 * <p>
 * Each addon gets its own class loader whose parent is XCore's plugin classloader.
 * The {@code addon.yml} descriptor is parsed from the JAR during construction
 * and made available via {@link #getDescriptor()}.
 * </p>
 * <p>
 * As a last-resort fallback, {@link #findClass(String)} iterates over loaded
 * server plugins (Bukkit + Paper) and tries their classloaders. This allows
 * addons to import classes from plugins like FancyHolograms that XCore itself
 * does not declare as a paper-plugin dependency. A small negative cache avoids
 * re-scanning all plugins for every class lookup miss.
 * </p>
 */
public class AddonClassLoader extends URLClassLoader {

    private final AddonDescriptor descriptor;
    private final java.util.Set<String> missCache = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Creates a new AddonClassLoader for the given JAR file.
     *
     * @param jarFile The addon JAR file.
     * @param parent  The parent classloader (typically XCore's plugin classloader).
     * @throws MalformedURLException    If the JAR file URL cannot be constructed.
     * @throws IOException              If the JAR cannot be read.
     * @throws IllegalArgumentException If {@code addon.yml} is missing or invalid.
     */
    public AddonClassLoader(File jarFile, ClassLoader parent) throws IOException {
        super(new URL[]{jarFile.toURI().toURL()}, parent);
        this.descriptor = parseDescriptor(jarFile);
    }

    /**
     * Parses the {@code addon.yml} from inside the JAR file.
     */
    private AddonDescriptor parseDescriptor(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("addon.yml");
            if (entry == null) {
                throw new IllegalArgumentException("JAR " + jarFile.getName() + " does not contain addon.yml");
            }
            try (InputStream is = jar.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                return new AddonDescriptor(yaml, jarFile);
            }
        }
    }

    /**
     * Returns the addon descriptor parsed from this JAR's {@code addon.yml}.
     */
    public AddonDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Override to search the addon JAR FIRST before delegating to parent.
     * This ensures addon resources (lang.yml, config.yml) are found in the addon JAR,
     * not in XCore's JAR which also has files with the same names.
     */
    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        if (url != null) return url;
        return super.getResource(name);
    }

    /**
     * Override to search the addon JAR FIRST before delegating to parent.
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        URL url = findResource(name);
        if (url != null) {
            try {
                return url.openStream();
            } catch (IOException e) {
                return null;
            }
        }
        return super.getResourceAsStream(name);
    }

    /**
     * Fallback class lookup : if a class isn't in the addon jar nor in XCore's
     * classloader, iterate loaded plugins' classloaders. This lets addons use
     * third-party Paper plugins (FancyHolograms, ItemsAdder, etc.) via direct
     * imports without requiring XCore to declare each of them in its
     * {@code paper-plugin.yml}.
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException primary) {
            // Skip core Java / Bukkit classes : never going to be in another plugin.
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("org.bukkit.") || name.startsWith("net.kyori.")
                    || name.startsWith("fr.xyness.XCore.")) {
                throw primary;
            }
            if (missCache.contains(name)) throw primary;
            Plugin[] plugins;
            try {
                plugins = Bukkit.getPluginManager().getPlugins();
            } catch (Throwable t) {
                missCache.add(name);
                throw primary;
            }
            for (Plugin plugin : plugins) {
                if (plugin == null) continue;
                ClassLoader pluginLoader = plugin.getClass().getClassLoader();
                if (pluginLoader == null || pluginLoader == this) continue;
                try {
                    Class<?> found = Class.forName(name, false, pluginLoader);
                    if (found != null) return found;
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // keep iterating
                } catch (Throwable t) {
                    // Any other error (e.g. classloader closed) : skip this plugin.
                }
            }
            missCache.add(name);
            throw primary;
        }
    }
}
