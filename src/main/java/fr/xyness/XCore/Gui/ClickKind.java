package fr.xyness.XCore.Gui;

import org.bukkit.event.inventory.ClickType;

/**
 * Enum representing the type of click in a GUI.
 * <p>
 * Maps Bukkit {@link ClickType} values to a simplified set of click kinds
 * used throughout the GUI framework.
 * </p>
 */
public enum ClickKind {

    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT;

    /**
     * Converts a Bukkit {@link ClickType} to a {@link ClickKind}.
     *
     * @param type The Bukkit click type.
     * @return The corresponding {@link ClickKind}, defaulting to {@link #LEFT}.
     */
    public static ClickKind fromBukkit(ClickType type) {
        return switch (type) {
            case SHIFT_LEFT -> SHIFT_LEFT;
            case SHIFT_RIGHT -> SHIFT_RIGHT;
            case RIGHT -> RIGHT;
            default -> LEFT;
        };
    }

    /**
     * Parses a {@link ClickKind} from a string representation.
     * <p>
     * The input is case-insensitive and underscores are stripped before matching.
     * </p>
     *
     * @param s The string to parse (may be {@code null}).
     * @return The corresponding {@link ClickKind}, defaulting to {@link #LEFT}.
     */
    public static ClickKind fromString(String s) {
        if (s == null) return LEFT;
        return switch (s.toLowerCase().replace("_", "")) {
            case "right" -> RIGHT;
            case "shiftleft" -> SHIFT_LEFT;
            case "shiftright" -> SHIFT_RIGHT;
            default -> LEFT;
        };
    }
}
