package fr.xyness.XCore.Integrations;

import org.bukkit.Bukkit;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import fr.xyness.XCore.Utils.Logger;

/**
 * Centralized PlaceholderAPI registration helper for XCore and its addons.
 * <p>
 * Checks whether PlaceholderAPI is present on the server, and provides
 * a convenience method to register {@link PlaceholderExpansion} instances.
 * </p>
 */
public class PlaceholderHook {

    /** Logger instance for this class. */
    private final Logger logger;

    /** Whether PlaceholderAPI is available on the server. */
    private final boolean available;

    /**
     * Creates a new PlaceholderHook and checks for PlaceholderAPI presence.
     */
    public PlaceholderHook() {
        this.logger = new Logger("PlaceholderHook");
        this.available = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    /**
     * Returns whether PlaceholderAPI is available on the server.
     *
     * @return {@code true} if PlaceholderAPI is loaded.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Registers a {@link PlaceholderExpansion} with PlaceholderAPI.
     * <p>
     * If PlaceholderAPI is not available, a warning is logged and the
     * expansion is not registered.
     * </p>
     *
     * @param expansion The expansion to register.
     * @return {@code true} if the expansion was registered successfully.
     */
    public boolean register(PlaceholderExpansion expansion) {
        if (!available) {
            logger.sendWarning("PlaceholderAPI not found, cannot register expansion: " + expansion.getIdentifier());
            return false;
        }
        boolean success = expansion.register();
        if (success) {
            logger.sendInfo("Registered PAPI expansion: <aqua>%" + expansion.getIdentifier() + "_%</aqua>");
        } else {
            logger.sendWarning("Failed to register PAPI expansion: " + expansion.getIdentifier());
        }
        return success;
    }
}
