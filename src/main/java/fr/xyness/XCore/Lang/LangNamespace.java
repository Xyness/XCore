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
     * Parsed forms of the strings this namespace has already been asked to render.
     *
     * <p>MiniMessage parsing is the single most repeated piece of work in the whole ecosystem: a GUI
     * blink task re-renders every item twice a second, and the strings it parses are identical from
     * one tick to the next — same lore, same button, same numbers. Parsing is pure, and Adventure
     * components are immutable, so the result can simply be kept.</p>
     *
     * <p>Bounded and dropped on reload, so an administrator editing a language file never sees a
     * stale message, and a placeholder taking unbounded values (a countdown, a price) cannot grow
     * the cache without limit.</p>
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Component> parsedCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(8_192).build();

    /** Same idea for multi-line lore, keyed by the whole raw block. */
    private final com.github.benmanes.caffeine.cache.Cache<String, List<Component>> loreCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(4_096).build();

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
     * <p>Both notations are honoured: <code>{name}</code> and <code>%name%</code>. They coexist
     * across the ecosystem — the core and the older addons write braces, the GUI conventions and
     * the newer addons write percent signs — and only braces used to be substituted. Every
     * <code>%placeholder%</code> in a language file was therefore printed verbatim to players
     * unless the caller happened to run its own {@code replace()}. Accepting both is what makes a
     * language file behave the way it reads.</p>
     *
     * @param key          The message key.
     * @param replacements Alternating placeholder name and value pairs
     *                     (e.g. {@code "name", "Steve", "uuid", "abc"}).
     * @return The formatted message string.
     */
    public String getMessage(String key, String... replacements) {
        String msg = getRaw(key);
        if (replacements.length == 0) return msg;
        // Each notation is only attempted when the message actually contains its delimiter: a
        // message with no placeholder at all now costs two character scans instead of two string
        // concatenations and two full replace passes per replacement pair.
        boolean braces = msg.indexOf('{') >= 0;
        boolean percents = msg.indexOf('%') >= 0;
        if (!braces && !percents) return msg;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String name = replacements[i];
            String value = replacements[i + 1] == null ? "" : replacements[i + 1];
            if (braces) msg = msg.replace("{" + name + "}", value);
            if (percents) msg = msg.replace("%" + name + "%", value);
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
        return parse(getMessage(key, replacements));
    }

    /**
     * Parses a MiniMessage string, reusing the previous result for a string already seen.
     *
     * @param raw The MiniMessage string.
     * @return The parsed component (shared and immutable).
     */
    public Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return parsedCache.get(raw, MINI::deserialize);
    }

    /**
     * Splits a multi-line lore string into a list of Adventure {@link Component}s.
     * Each line is deserialized as MiniMessage.
     *
     * @param loreString The raw lore string, with lines separated by {@code \n}.
     * @return A list of parsed components, one per line.
     */
    public List<Component> getLore(String loreString) {
        if (loreString == null || loreString.isBlank()) return new ArrayList<>();
        List<Component> cached = loreCache.get(loreString, raw -> {
            List<Component> lore = new ArrayList<>();
            for (String line : raw.split("\n")) {
                if (line.isEmpty()) continue;
                lore.add(MINI.deserialize(line));
            }
            return List.copyOf(lore);
        });
        // A fresh mutable list every time: callers routinely append to the lore they get back, and
        // handing them the cached one would corrupt every later reader of the same block.
        return new ArrayList<>(cached);
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
        // The parsed forms belong to the file that was just replaced.
        parsedCache.invalidateAll();
        loreCache.invalidateAll();

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

        // Taken before the disk file is overlaid: read afterwards, this set also contains the keys
        // that only exist on disk, and the removal pass below can never match anything.
        Set<String> defaultKeys = new HashSet<>(messages.keySet());

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
                List<String> obsolete = new ArrayList<>();
                for (String key : new HashSet<>(yaml.getKeys(true))) {
                    if (!yaml.isConfigurationSection(key) && !defaultKeys.contains(key)) {
                        obsolete.add(key);
                    }
                }

                if (!obsolete.isEmpty()) {
                    // Copy the file first: this removes lines somebody may have written by hand.
                    if (backup(langFile)) {
                        for (String key : obsolete) {
                            yaml.set(key, null);
                            messages.remove(key);
                        }
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

    /**
     * Copies a language file next to itself before keys are removed from it.
     *
     * @param file The file about to be rewritten.
     * @return {@code true} when the copy succeeded and the removal may proceed.
     */
    private static boolean backup(File file) {
        try {
            java.nio.file.Files.copy(file.toPath(),
                    new File(file.getParentFile(), file.getName() + ".pre-prune.bak").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            java.util.logging.Logger.getLogger("XCore").warning(
                    "Could not back up " + file.getName() + " before pruning it; leaving it untouched.");
            return false;
        }
    }
}
