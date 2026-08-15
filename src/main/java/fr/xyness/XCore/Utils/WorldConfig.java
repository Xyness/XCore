package fr.xyness.XCore.Utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Per-world configuration overrides, resolved once instead of on every read.
 *
 * <h2>The problem it replaces</h2>
 * The natural way to write "this setting, but for that world" is to build the path and ask the
 * configuration:
 *
 * <pre>{@code
 * String path = "worlds." + world.getName() + "." + key;   // allocates
 * if (config.contains(path)) return config.getBoolean(path); // walks the YAML tree
 * return config.getBoolean(key, def);                        // walks it again
 * }</pre>
 *
 * That is two tree walks and up to three string allocations — acceptable in a command, ruinous in a
 * listener that fires on every mob spawn and every block placed.
 *
 * <h2>What this does instead</h2>
 * The {@code worlds} section is flattened once at load into {@code world -> path -> value}. A lookup
 * is then two hash lookups and no allocation at all. Rebuild it whenever the configuration is
 * reloaded — {@link #from(ConfigurationSection)} is cheap and is meant to be called again.
 *
 * <pre>{@code
 * this.worlds = WorldConfig.from(config.getConfigurationSection("worlds"));
 * boolean enabled = worlds.bool(world, "mob-stacker.enabled", globalDefault);
 * }</pre>
 */
public final class WorldConfig {

    /** An instance with nothing in it, for configurations that declare no override. */
    private static final WorldConfig EMPTY = new WorldConfig(Collections.emptyMap());

    /** {@code world name -> flattened path -> value}. */
    private final Map<String, Map<String, Object>> byWorld;

    private WorldConfig(Map<String, Map<String, Object>> byWorld) {
        this.byWorld = byWorld;
    }

    /**
     * Flattens a {@code worlds} section.
     *
     * @param section The {@code worlds} section, or {@code null} when the file declares none.
     * @return The resolved overrides, never {@code null}.
     */
    public static WorldConfig from(ConfigurationSection section) {
        if (section == null) return EMPTY;
        Map<String, Map<String, Object>> resolved = new HashMap<>();
        for (String worldName : section.getKeys(false)) {
            ConfigurationSection worldSection = section.getConfigurationSection(worldName);
            if (worldSection == null) continue;
            Map<String, Object> values = new HashMap<>();
            for (String path : worldSection.getKeys(true)) {
                if (worldSection.isConfigurationSection(path)) continue;
                values.put(path, worldSection.get(path));
            }
            if (!values.isEmpty()) resolved.put(worldName, values);
        }
        return resolved.isEmpty() ? EMPTY : new WorldConfig(resolved);
    }

    /** @return {@code true} when at least one world declares at least one override. */
    public boolean hasOverrides() {
        return !byWorld.isEmpty();
    }

    /** @return The names of the worlds carrying an override. */
    public Set<String> worlds() {
        return Collections.unmodifiableSet(byWorld.keySet());
    }

    /**
     * Reads a boolean for a world, falling back to the supplied global value.
     *
     * @param world The world, or {@code null} for the global value.
     * @param path  The path inside the world section.
     * @param def   The global value.
     * @return The override when there is one, {@code def} otherwise.
     */
    public boolean bool(World world, String path, boolean def) {
        Object value = raw(world, path);
        return value instanceof Boolean b ? b : def;
    }

    /**
     * Reads an int for a world, falling back to the supplied global value.
     *
     * @param world The world, or {@code null} for the global value.
     * @param path  The path inside the world section.
     * @param def   The global value.
     * @return The override when there is one, {@code def} otherwise.
     */
    public int integer(World world, String path, int def) {
        Object value = raw(world, path);
        return value instanceof Number n ? n.intValue() : def;
    }

    /**
     * Reads a double for a world, falling back to the supplied global value.
     *
     * @param world The world, or {@code null} for the global value.
     * @param path  The path inside the world section.
     * @param def   The global value.
     * @return The override when there is one, {@code def} otherwise.
     */
    public double decimal(World world, String path, double def) {
        Object value = raw(world, path);
        return value instanceof Number n ? n.doubleValue() : def;
    }

    /**
     * Reads a string for a world, falling back to the supplied global value.
     *
     * @param world The world, or {@code null} for the global value.
     * @param path  The path inside the world section.
     * @param def   The global value.
     * @return The override when there is one, {@code def} otherwise.
     */
    public String text(World world, String path, String def) {
        Object value = raw(world, path);
        return value instanceof String s ? s : def;
    }

    /** @return The raw override, or {@code null} when the world has none for that path. */
    private Object raw(World world, String path) {
        if (world == null || byWorld.isEmpty()) return null;
        Map<String, Object> values = byWorld.get(world.getName());
        return values == null ? null : values.get(path);
    }
}
