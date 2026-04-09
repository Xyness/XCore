package fr.xyness.XCore.Gui;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry for storing and retrieving GUI definitions by name.
 * <p>
 * Holds a map of {@link GuiDefinition} objects keyed by their unique name.
 * The registry can be cleared and repopulated atomically via
 * {@link #clearAndPutAll(Map)}, which is typically called during a reload.
 * </p>
 */
public class GuiRegistry {

    /** Internal map of GUI definitions keyed by name. */
    private final Map<String, GuiDefinition> definitions = new HashMap<>();

    /**
     * Retrieves a GUI definition by its name.
     *
     * @param name The unique name of the GUI definition.
     * @return The matching {@link GuiDefinition}, or {@code null} if not found.
     */
    public GuiDefinition get(String name) {
        return definitions.get(name);
    }

    /**
     * Registers a GUI definition.
     *
     * @param name       The unique name.
     * @param definition The GUI definition.
     */
    public void put(String name, GuiDefinition definition) {
        definitions.put(name, definition);
    }

    /**
     * Clears all existing definitions and replaces them with the provided map.
     *
     * @param defs The new map of GUI definitions keyed by name.
     */
    public void clearAndPutAll(Map<String, GuiDefinition> defs) {
        definitions.clear();
        definitions.putAll(defs);
    }

    /**
     * Returns all registered GUI definitions.
     *
     * @return A collection of all {@link GuiDefinition} instances.
     */
    public Collection<GuiDefinition> all() {
        return definitions.values();
    }

    /**
     * Returns whether a definition exists for the given name.
     *
     * @param name The GUI name.
     * @return {@code true} if a definition is registered under that name.
     */
    public boolean contains(String name) {
        return definitions.containsKey(name);
    }

    /**
     * Removes all definitions from this registry.
     */
    public void clear() {
        definitions.clear();
    }
}
