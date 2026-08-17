package fr.xyness.XCore.Integrations;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Lets an addon publish placeholders without writing an expansion class.
 *
 * <p>Every addon had the same forty lines: extend {@code PlaceholderExpansion}, return an
 * identifier, a version and an author, then a {@code switch} over the parameter. Only the switch
 * ever differed.</p>
 *
 * <pre>{@code
 * placeholders()
 *     .register("balance", (player, arg) -> format(balanceOf(player)))
 *     .register("top", (player, arg) -> topName(Integer.parseInt(arg)));
 * }</pre>
 *
 * <p>The identifier is the addon's name in lower case, so those become {@code %myaddon_balance%}
 * and {@code %myaddon_top_1%}. Registration happens by itself; when PlaceholderAPI is absent
 * nothing is published and nothing breaks.</p>
 */
public class PlaceholderRegistry {

    /**
     * Produces the text behind one placeholder.
     */
    @FunctionalInterface
    public interface PlaceholderFunction {
        /**
         * @param player   Who the placeholder is being resolved for, possibly offline.
         * @param argument What followed the key, without its underscore. Empty when there was none.
         * @return The replacement text, or {@code null} to leave the placeholder untouched.
         */
        String resolve(OfflinePlayer player, String argument);
    }

    private final String identifier;
    private final String author;
    private final String version;
    private final Map<String, PlaceholderFunction> handlers = new LinkedHashMap<>();

    private boolean published;

    /**
     * @param identifier The prefix placeholders will carry, without the percent signs.
     * @param author     Shown by {@code /papi info}.
     * @param version    Shown by {@code /papi info}.
     */
    public PlaceholderRegistry(String identifier, String author, String version) {
        this.identifier = identifier == null ? "xcore" : identifier.toLowerCase();
        this.author = author == null ? "Xyness" : author;
        this.version = version == null ? "1.0.0" : version;
    }

    /**
     * Adds a placeholder.
     *
     * @param key      The name after the identifier, e.g. {@code "balance"}.
     * @param function What produces its value.
     * @return This registry.
     */
    public PlaceholderRegistry register(String key, PlaceholderFunction function) {
        if (key == null || function == null) return this;
        handlers.put(key.toLowerCase(), function);
        return this;
    }

    /** @return How many placeholders are declared. */
    public int size() {
        return handlers.size();
    }

    /** @return The prefix these placeholders carry. */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Resolves one request.
     *
     * <p>An exact match on the whole parameter wins; failing that the longest declared key that the
     * parameter starts with is used, and the rest is passed along as the argument. That is what
     * makes both {@code %addon_top%} and {@code %addon_top_3%} reach the same handler.</p>
     *
     * @param player    Who it is for.
     * @param parameter Everything after the identifier.
     * @return The replacement, or {@code null} when nothing handles it.
     */
    public String resolve(OfflinePlayer player, String parameter) {
        if (parameter == null) return null;
        String lower = parameter.toLowerCase();

        PlaceholderFunction exact = handlers.get(lower);
        if (exact != null) return safe(exact, player, "");

        String bestKey = null;
        for (String key : handlers.keySet()) {
            if (!lower.startsWith(key + "_")) continue;
            if (bestKey == null || key.length() > bestKey.length()) bestKey = key;
        }
        if (bestKey == null) return null;
        return safe(handlers.get(bestKey), player, parameter.substring(bestKey.length() + 1));
    }

    private String safe(PlaceholderFunction function, OfflinePlayer player, String argument) {
        try {
            return function.resolve(player, argument);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Publishes the placeholders, if PlaceholderAPI is installed and there is anything to publish.
     *
     * @return {@code true} when the expansion was registered.
     */
    public boolean publish() {
        if (published || handlers.isEmpty()) return false;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return false;
        try {
            published = new AddonExpansion(this, author, version).register();
            return published;
        } catch (Throwable t) {
            return false;
        }
    }
}
