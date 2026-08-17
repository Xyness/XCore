package fr.xyness.XCore.Utils;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Adds the settings a new version introduces to a config file already on disk.
 *
 * <p>Only missing keys are written, existing values are never touched. Three kinds of key are left
 * alone because they hold what the administrator wrote rather than what we ship:</p>
 *
 * <ul>
 *   <li>anything under a path the caller declares protected;</li>
 *   <li>anything under a listing — a section whose children are all sections, like
 *       {@code economy.currencies} — once that section exists on disk. Otherwise renaming the
 *       {@code dollar} currency to {@code euro} brings {@code dollar} back on the next start;</li>
 *   <li>a list whose key was deleted, unless the section around it is new too.</li>
 * </ul>
 */
public final class ConfigMerger {

    private ConfigMerger() {}

    /**
     * Copies into {@code disk} the values {@code defaults} declares and it does not have.
     *
     * @param disk              The config loaded from the file.
     * @param defaults          The config bundled in the jar.
     * @param protectedSections Paths to leave alone entirely.
     * @return The keys that were added, empty when there was nothing to do.
     */
    public static List<String> addMissingKeys(FileConfiguration disk, FileConfiguration defaults,
                                              String... protectedSections) {
        List<String> added = new ArrayList<>();
        if (disk == null || defaults == null) return added;

        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (disk.contains(key)) continue;
            if (isUnderProtectedSection(key, protectedSections)) continue;
            if (isUnderExistingListing(key, disk, defaults)) continue;
            if (defaults.isList(key) && parentExistsOnDisk(key, disk)) continue;

            disk.set(key, defaults.get(key));
            added.add(key);
        }
        return added;
    }

    /**
     * Checks whether a key sits at or below one of the protected paths.
     *
     * @param key               The key to test.
     * @param protectedSections The protected paths.
     * @return {@code true} if the key must not be written.
     */
    public static boolean isUnderProtectedSection(String key, String... protectedSections) {
        if (protectedSections == null) return false;
        for (String path : protectedSections) {
            if (path == null || path.isEmpty()) continue;
            if (key.equals(path) || key.startsWith(path + ".")) return true;
        }
        return false;
    }

    /** Walks up the key looking for a listing section the file already has. */
    private static boolean isUnderExistingListing(String key, FileConfiguration disk, FileConfiguration defaults) {
        int cut = key.lastIndexOf('.');
        while (cut > 0) {
            String parent = key.substring(0, cut);
            if (isListing(defaults, parent) && disk.isConfigurationSection(parent)) return true;
            cut = parent.lastIndexOf('.');
        }
        return false;
    }

    /**
     * A section counts as a listing when it has children and all of them are sections.
     *
     * <p>{@code economy.currencies} holds {@code dollar}, a section, so it is a listing.
     * {@code database} holds {@code host} and {@code port}, so it is not. A section holding both,
     * like {@code cross-server}, is not one either, which is what lets a new setting be added next
     * to an existing sub-section.</p>
     */
    private static boolean isListing(FileConfiguration config, String path) {
        if (!config.isConfigurationSection(path)) return false;
        var section = config.getConfigurationSection(path);
        if (section == null) return false;
        var children = section.getKeys(false);
        if (children.isEmpty()) return false;
        for (String child : children) {
            if (!section.isConfigurationSection(child)) return false;
        }
        return true;
    }

    /** Whether the section holding this key is already on disk. Top-level keys count as seen. */
    private static boolean parentExistsOnDisk(String key, FileConfiguration disk) {
        int cut = key.lastIndexOf('.');
        if (cut <= 0) return true;
        return disk.isConfigurationSection(key.substring(0, cut));
    }
}
