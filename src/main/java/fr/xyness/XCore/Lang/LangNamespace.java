package fr.xyness.XCore.Lang;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Per-addon language namespace containing loaded messages.
 * <p>
 * Messages are stored as MiniMessage-formatted strings and can contain
 * {@code {placeholder}} tokens replaced at runtime via {@link #getMessage(String, String...)}.
 * Defaults are merged from an embedded resource stream so that missing keys
 * are always populated.
 * </p>
 */
public class LangNamespace {

    /** Shared MiniMessage parser instance. */
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Loaded messages keyed by their YAML key. */
    private final Map<String, String> messages = new HashMap<>();

    /**
     * Creates a new empty LangNamespace.
     */
    public LangNamespace() {
    }

    /**
     * Returns the raw MiniMessage string for the given key.
     *
     * @param key The message key (flat, e.g. {@code "stats-title"}).
     * @return The raw message string, or the key itself if not found.
     */
    public String getRaw(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * Returns the raw MiniMessage string for the given key.
     * Alias for {@link #getRaw(String)}.
     *
     * @param key The message key.
     * @return The raw message string, or the key itself if not found.
     */
    public String getMessageString(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * Returns the raw MiniMessage string with placeholders replaced.
     *
     * @param key          The message key.
     * @param replacements Alternating placeholder name and value pairs
     *                     (e.g. {@code "name", "Steve", "uuid", "abc"}).
     * @return The formatted message string.
     */
    public String getMessage(String key, String... replacements) {
        String msg = getRaw(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return msg;
    }

    /**
     * Returns a parsed Adventure {@link Component} for the given key with placeholder replacements.
     *
     * @param key          The message key.
     * @param replacements Alternating placeholder name and value pairs.
     * @return The parsed component.
     */
    public Component getComponent(String key, String... replacements) {
        return MINI.deserialize(getMessage(key, replacements));
    }

    /**
     * Splits a multi-line lore string into a list of Adventure {@link Component}s.
     * Each line is deserialized as MiniMessage.
     *
     * @param loreString The raw lore string, with lines separated by {@code \n}.
     * @return A list of parsed components, one per line.
     */
    public List<Component> getLore(String loreString) {
        List<Component> lore = new ArrayList<>();
        if (loreString == null || loreString.isBlank()) return lore;
        for (String line : loreString.split("\n")) {
            if (line.isEmpty()) continue;
            lore.add(MINI.deserialize(line));
        }
        return lore;
    }

    /**
     * Reloads messages from a YAML file, merging defaults from an embedded resource.
     * <p>
     * Keys present in the defaults but missing from the file are added.
     * Keys present in the file take precedence over defaults.
     * </p>
     *
     * @param langFile The YAML language file on disk.
     * @param defaults An {@link InputStream} to the embedded default resource, or {@code null}.
     */
    /**
     * Reloads messages from the stored lang file (no-arg convenience).
     */
    public void reload() {
        if (this.langFile != null) {
            reload(this.langFile, null);
        }
    }

    /** Stores the lang file for no-arg reload. */
    private File langFile;

    public void reload(File langFile, InputStream defaults) {
        this.langFile = langFile;
        messages.clear();

        // Load defaults first
        if (defaults != null) {
            YamlConfiguration defaultYaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaults, StandardCharsets.UTF_8));
            for (String key : defaultYaml.getKeys(true)) {
                if (!defaultYaml.isConfigurationSection(key)) {
                    messages.put(key, defaultYaml.getString(key, ""));
                }
            }
        }

        // Overlay with file values
        if (langFile != null && langFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(langFile);
            for (String key : yaml.getKeys(true)) {
                if (!yaml.isConfigurationSection(key)) {
                    messages.put(key, yaml.getString(key, ""));
                }
            }

            // Save back merged keys and remove obsolete keys
            if (defaults != null) {
                boolean changed = false;

                // Add missing keys
                for (Map.Entry<String, String> entry : messages.entrySet()) {
                    if (!yaml.contains(entry.getKey())) {
                        yaml.set(entry.getKey(), entry.getValue());
                        changed = true;
                    }
                }

                // Remove obsolete keys (present on disk but not in defaults)
                Set<String> defaultKeys = messages.keySet();
                Set<String> diskKeys = new HashSet<>(yaml.getKeys(true));
                for (String key : diskKeys) {
                    if (!yaml.isConfigurationSection(key) && !defaultKeys.contains(key)) {
                        yaml.set(key, null);
                        changed = true;
                    }
                }

                if (changed) {
                    try {
                        yaml.save(langFile);
                    } catch (IOException e) {
                        java.util.logging.Logger.getLogger("XCore").warning("Failed to save lang file: " + e.getMessage());
                    }
                }
            }
        }
    }
}
