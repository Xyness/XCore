package fr.xyness.XCore.Utils;

import java.io.File;
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

    private final XCore main;
    private final File langFile;
    private final Map<String, String> messages = new HashMap<>();

    /**
     * Creates a new LangManager and loads messages from {@code lang.yml}.
     *
     * @param main The main plugin instance.
     */
    public LangManager(XCore main) {
        this.main = main;
        this.langFile = new File(main.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            main.saveResource("lang.yml", false);
        }
        load();
    }

    /**
     * Loads or reloads all messages from {@code lang.yml}.
     */
    public void reload() {
        messages.clear();
        if (!langFile.exists()) main.saveResource("lang.yml", false);
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
     * Splits a multi-line lore string (pipe-style YAML) into a list of Adventure {@link Component}s.
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

}
