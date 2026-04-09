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

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Custom {@link URLClassLoader} for loading addon JARs.
 * <p>
 * Each addon gets its own class loader whose parent is XCore's plugin classloader.
 * The {@code addon.yml} descriptor is parsed from the JAR during construction
 * and made available via {@link #getDescriptor()}.
 * </p>
 */
public class AddonClassLoader extends URLClassLoader {

    private final AddonDescriptor descriptor;

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
     *
     * @param jarFile The JAR file to read from.
     * @return The parsed {@link AddonDescriptor}.
     * @throws IOException              If the JAR cannot be read.
     * @throws IllegalArgumentException If {@code addon.yml} is missing or malformed.
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
     *
     * @return The {@link AddonDescriptor}.
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
}
