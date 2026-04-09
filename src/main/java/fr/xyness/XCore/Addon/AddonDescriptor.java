package fr.xyness.XCore.Addon;

import java.io.File;
import java.util.Collections;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Immutable descriptor parsed from an addon's {@code addon.yml} inside its JAR.
 * <p>
 * Contains metadata such as name, version, author, main class, description,
 * and dependency lists used for topological sorting during load order resolution.
 * </p>
 */
public class AddonDescriptor {

    private final String name;
    private final String version;
    private final String author;
    private final String main;
    private final String description;
    private final List<String> depend;
    private final List<String> softDepend;
    private final File jarFile;

    /**
     * Creates a new AddonDescriptor by parsing the given {@link YamlConfiguration}.
     *
     * @param yaml    The parsed {@code addon.yml} configuration.
     * @param jarFile The JAR file this descriptor was loaded from.
     * @throws IllegalArgumentException If required fields ({@code name}, {@code main}) are missing.
     */
    public AddonDescriptor(YamlConfiguration yaml, File jarFile) {
        this.name = requireKey(yaml, "name");
        this.version = yaml.getString("version", "1.0.0");
        this.author = yaml.getString("author", "Unknown");
        this.main = requireKey(yaml, "main");
        this.description = yaml.getString("description", "");
        this.depend = yaml.contains("depend")
                ? Collections.unmodifiableList(yaml.getStringList("depend"))
                : Collections.emptyList();
        this.softDepend = yaml.contains("soft-depend")
                ? Collections.unmodifiableList(yaml.getStringList("soft-depend"))
                : Collections.emptyList();
        this.jarFile = jarFile;
    }

    private static String requireKey(YamlConfiguration yaml, String key) {
        String value = yaml.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("addon.yml is missing required key: " + key);
        }
        return value;
    }

    /** @return The addon's unique name. */
    public String getName() { return name; }

    /** @return The addon's version string. */
    public String getVersion() { return version; }

    /** @return The addon's author. */
    public String getAuthor() { return author; }

    /** @return The fully qualified main class name. */
    public String getMain() { return main; }

    /** @return The addon's description. */
    public String getDescription() { return description; }

    /** @return Immutable list of required addon dependencies. */
    public List<String> getDepend() { return depend; }

    /** @return Immutable list of optional addon dependencies. */
    public List<String> getSoftDepend() { return softDepend; }

    /** @return The JAR file this addon was loaded from. */
    public File getJarFile() { return jarFile; }

    @Override
    public String toString() {
        return name + " v" + version + " by " + author;
    }
}
