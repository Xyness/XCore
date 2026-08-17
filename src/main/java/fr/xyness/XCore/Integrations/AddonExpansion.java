package fr.xyness.XCore.Integrations;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * The PlaceholderAPI side of a {@link PlaceholderRegistry}.
 *
 * <p>Kept as its own class so nothing loads {@code PlaceholderExpansion} on a server without
 * PlaceholderAPI: it is only touched from {@link PlaceholderRegistry#publish()}, after the plugin
 * has been found.</p>
 */
public class AddonExpansion extends PlaceholderExpansion {

    private final PlaceholderRegistry registry;
    private final String author;
    private final String version;

    /**
     * @param registry The placeholders to serve.
     * @param author   Shown by {@code /papi info}.
     * @param version  Shown by {@code /papi info}.
     */
    public AddonExpansion(PlaceholderRegistry registry, String author, String version) {
        this.registry = registry;
        this.author = author;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() {
        return registry.getIdentifier();
    }

    @Override
    public @NotNull String getAuthor() {
        return author;
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return registry.resolve(player, params);
    }
}
