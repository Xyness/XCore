package fr.xyness.XCore.Utils;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import fr.xyness.XCore.XCore;

/**
 * Manages the language file ({@code lang.yml}) for configurable messages.
 * <p>
 * Messages are stored as MiniMessage-formatted strings and can contain
 * {@code {placeholder}} tokens replaced at runtime via {@link #getMessage(String, String...)}.
 * </p>
 */
public class LangManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Translations shipped in the jar, English first — it is also the fallback. */
    private static final String[] BUNDLED = { "en", "fr" };

    private final XCore main;
    private File langFile;
    private final Map<String, String> messages = new HashMap<>();

    /** Parsed forms, kept for the same reason as in {@link fr.xyness.XCore.Lang.LangNamespace}. */
    private final com.github.benmanes.caffeine.cache.Cache<String, Component> parsedCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(4_096).build();

    /** Parsed multi-line lore blocks. */
    private final com.github.benmanes.caffeine.cache.Cache<String, List<Component>> loreCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(2_048).build();

    /**
     * Creates a new LangManager and loads messages from {@code lang.yml}.
     *
     * @param main The main plugin instance.
     */
    public LangManager(XCore main) {
        this.main = main;
        this.langFile = resolve(main);
        load();
    }

    /**
     * Picks the file for the configured language, extracting the bundled ones on first run.
     *
     * <p>Files live in {@code plugins/XCore/lang/<code>.yml}.</p>
     */
    private static File resolve(XCore main) {
        File folder = new File(main.getDataFolder(), "lang");
        if (!folder.exists()) folder.mkdirs();

        for (String code : BUNDLED) {
            File f = new File(folder, code + ".yml");
            if (!f.exists()) {
                try { main.saveResource("lang/" + code + ".yml", false); } catch (Exception ignored) {}
            }
        }

        String wanted = main.getLanguage();
        File target = new File(folder, wanted + ".yml");

        if (!target.exists()) {
            main.logger().sendWarning("No '" + wanted + "' translation for XCore — falling back to English.");
            target = new File(folder, "en.yml");
            if (!target.exists()) {
                try { main.saveResource("lang/en.yml", false); } catch (Exception ignored) {}
            }
        }

        fillMissingKeys(main, target);
        return target;
    }

    /**
     * Adds keys the bundled translation has and the file on disk lacks.
     *
     * <p>Without this an upgrade that introduces a message leaves the administrator's file behind,
     * and the new message renders as its own key in front of players. Existing values are never
     * touched.</p>
     */
    private static void fillMissingKeys(XCore main, File target) {
        String resource = "lang/" + target.getName();
        try (InputStream defaults = main.getResource(resource)) {
            if (defaults == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8));
            YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(target);

            List<String> added = new ArrayList<>();
            for (String key : bundled.getKeys(false)) {
                if (onDisk.contains(key)) continue;
                onDisk.set(key, bundled.getString(key));
                added.add(key);
            }
            if (added.isEmpty()) return;

            onDisk.save(target);
            main.logger().sendInfo("Added " + added.size() + " new message(s) to lang/" + target.getName() + ".");
        } catch (Exception e) {
            main.logger().sendWarning("Could not update lang/" + target.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Reloads the messages, re-resolving the file first.
     *
     * <p>Re-resolving matters: {@code /xcore reload} is exactly when an administrator has just
     * changed {@code language}, and keeping the previously chosen file would make the setting look
     * like it needs a restart.</p>
     */
    public void reload() {
        messages.clear();
        parsedCache.invalidateAll();
        loreCache.invalidateAll();
        this.langFile = resolve(main);
        load();
    }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(langFile);
        for (String key : yaml.getKeys(false)) {
            messages.put(key, yaml.getString(key, ""));
        }
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
     * Alias for {@link #getRaw(String)} for consistency.
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
     * @param replacements Alternating placeholder name and value pairs (e.g. {@code "name", "Steve", "uuid", "abc"}).
     * @return The formatted message string.
     */
    public String getMessage(String key, String... replacements) {
        String msg = getRaw(key);
        if (replacements.length == 0) return msg;
        // Both notations, as in LangNamespace: language files across the ecosystem use either.
        // Each is only attempted when its delimiter is actually present.
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
     * Splits a multi-line lore string (pipe-style YAML) into a list of Adventure {@link Component}s.
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
        return new ArrayList<>(cached);
    }

}
