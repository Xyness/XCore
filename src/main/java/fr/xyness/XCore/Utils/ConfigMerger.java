package fr.xyness.XCore.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
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
 *
 * <p>A key the new version renamed is a fourth case, handled before the merge by
 * {@link #moveRenamedKeys(ConfigurationSection, Map)}: left to the merge, the new name arrives with
 * its default and the prune that follows deletes the old one, with the value the administrator had
 * given it.</p>
 */
public final class ConfigMerger {

    private ConfigMerger() {}

    /**
     * Moves the values of renamed keys onto their new names, in place.
     *
     * <h2>Why</h2>
     * A key renamed in a new version looked, to the passes that keep a file up to date, like one key
     * to add and another to delete: the new name was written with the shipped default and the old
     * one pruned, so whatever the administrator had set under it was gone. It happened to a message
     * administrators customise ({@code lore-buyout}, now {@code lore-buyout-drop}) and to an alert
     * ({@code redstone-clock-detected}, now {@code redstone-clock-reported}).
     *
     * <h2>Rules</h2>
     * <ul>
     *   <li>A value moves only when the new key is absent: a value already there was written by
     *       somebody, and is kept.</li>
     *   <li>Paths are dotted and may be nested ({@code anti-afk.enabled} to {@code afk.enabled}).
     *       A section moves value by value, so a section the new version already started under the
     *       new name receives what it lacks; what could not move stays under the old name.</li>
     *   <li>Chains are followed: with {@code a → b} and {@code b → c} both declared, {@code a} goes
     *       straight to {@code c}. When several old names of one key are present, the most recent
     *       one wins.</li>
     *   <li>Nothing is moved into a path whose parent holds a plain value, nor between a path and
     *       one inside it: either would overwrite something.</li>
     *   <li>Idempotent: once moved, the old key is gone and there is nothing left to do.</li>
     * </ul>
     *
     * @param yaml    The file's content, changed in place.
     * @param renames Old path → new path.
     * @return One {@code "old → new"} entry per key whose value moved; empty when none did.
     */
    public static List<String> moveRenamedKeys(ConfigurationSection yaml, Map<String, String> renames) {
        List<String> moved = new ArrayList<>();
        if (yaml == null || renames == null || renames.isEmpty()) return moved;

        // Each old name straight to the name it ends up with, newest renames first: of two old names
        // present on disk, the one renamed last is the one that was written last.
        record Step(String from, String to, int hops) {}
        List<Step> plan = new ArrayList<>();
        for (Map.Entry<String, String> entry : renames.entrySet()) {
            String from = entry.getKey();
            String to = entry.getValue();
            if (isBlank(from) || isBlank(to) || from.equals(to)) continue;
            int hops = 1;
            Set<String> seen = new HashSet<>(List.of(from, to));
            while (renames.containsKey(to)) {
                String next = renames.get(to);
                if (isBlank(next) || !seen.add(next)) break;   // a cycle: stop where it loops
                to = next;
                hops++;
            }
            if (!from.equals(to)) plan.add(new Step(from, to, hops));
        }
        plan.sort(Comparator.comparingInt(Step::hops).thenComparing(Step::from));

        for (Step step : plan) {
            String from = step.from();
            String to = step.to();
            if (to.startsWith(from + ".") || from.startsWith(to + ".")) continue;
            if (!yaml.contains(from, true)) continue;

            if (yaml.isConfigurationSection(from)) {
                int count = moveSection(yaml, from, to);
                if (count > 0) moved.add(from + " → " + to + (count > 1 ? " (" + count + " values)" : ""));
                continue;
            }
            if (yaml.contains(to, true) || !canHold(yaml, to)) continue;
            moveValue(yaml, from, to, yaml.get(from));
            moved.add(from + " → " + to);
        }
        return moved;
    }

    /**
     * Writes a value under its new path and removes the old one, comments included: the file is the
     * administrator's documentation too, and the line explaining a setting went with the old key.
     */
    private static void moveValue(ConfigurationSection yaml, String from, String to, Object value) {
        List<String> comments = null;
        List<String> inline = null;
        try {
            comments = yaml.getComments(from);
            inline = yaml.getInlineComments(from);
        } catch (Throwable ignored) {
            // An API without comments: the value is what matters.
        }
        yaml.set(to, value);
        yaml.set(from, null);
        try {
            if (comments != null && !comments.isEmpty()) yaml.setComments(to, comments);
            if (inline != null && !inline.isEmpty()) yaml.setInlineComments(to, inline);
        } catch (Throwable ignored) {
            // Same.
        }
    }

    /**
     * Moves a section value by value.
     *
     * @return How many values (and empty sections) moved.
     */
    private static int moveSection(ConfigurationSection yaml, String from, String to) {
        ConfigurationSection source = yaml.getConfigurationSection(from);
        if (source == null) return 0;

        // Read first, written after: the section is being emptied while it is walked otherwise.
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> emptySections = new ArrayList<>();
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) {
                ConfigurationSection child = source.getConfigurationSection(key);
                if (child != null && child.getKeys(false).isEmpty()) emptySections.add(key);
                continue;
            }
            values.put(key, source.get(key));
        }

        if (values.isEmpty() && emptySections.isEmpty()) {
            // An empty section says "none of these", which the defaults would otherwise fill in.
            if (yaml.contains(to, true) || !canHold(yaml, to)) return 0;
            yaml.createSection(to);
            yaml.set(from, null);
            return 1;
        }

        // The comment above the section itself goes with it, when the section is new under that name.
        boolean fresh = !yaml.contains(to, true);
        List<String> heading = null;
        try {
            heading = yaml.getComments(from);
        } catch (Throwable ignored) {
            // An API without comments.
        }

        int count = 0;
        int stayed = 0;
        List<String> vacated = new ArrayList<>();
        for (Map.Entry<String, Object> value : values.entrySet()) {
            String target = to + "." + value.getKey();
            if (yaml.contains(target, true) || !canHold(yaml, target)) { stayed++; continue; }
            moveValue(yaml, from + "." + value.getKey(), target, value.getValue());
            vacated.add(value.getKey());
            count++;
        }
        for (String key : emptySections) {
            String target = to + "." + key;
            if (yaml.contains(target, true) || !canHold(yaml, target)) { stayed++; continue; }
            yaml.createSection(target);
            yaml.set(from + "." + key, null);
            vacated.add(key);
            count++;
        }
        if (fresh && count > 0 && heading != null && !heading.isEmpty()) {
            try {
                yaml.setComments(to, heading);
            } catch (Throwable ignored) {
                // Same.
            }
        }

        // Gone once everything in it has moved. Otherwise what is left had a value under the new name
        // already, and stays; only the sections the moves emptied go.
        if (stayed == 0) {
            yaml.set(from, null);
            return count;
        }
        for (String key : vacated) {
            int cut = key.lastIndexOf('.');
            while (cut > 0) {
                String parent = from + "." + key.substring(0, cut);
                ConfigurationSection section = yaml.getConfigurationSection(parent);
                if (section == null || !section.getKeys(false).isEmpty()) break;
                yaml.set(parent, null);
                cut = key.lastIndexOf('.', cut - 1);
            }
        }
        return count;
    }

    /** Whether every parent of a path is absent or a section, so that writing it overwrites nothing. */
    private static boolean canHold(ConfigurationSection yaml, String path) {
        int cut = path.indexOf('.');
        while (cut > 0) {
            String parent = path.substring(0, cut);
            if (yaml.contains(parent, true) && !yaml.isConfigurationSection(parent)) return false;
            cut = path.indexOf('.', cut + 1);
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

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
