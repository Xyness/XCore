package fr.xyness.XCore.Addon;

/**
 * Represents the lifecycle state of an {@link XAddon}.
 */
public enum AddonState {

    /** The addon JAR has been loaded and its main class instantiated, but {@code onEnable()} has not been called yet. */
    LOADED,

    /** The addon is currently executing its {@code onEnable()} method. */
    ENABLING,

    /** The addon has been successfully enabled. */
    ENABLED,

    /** The addon is currently executing its {@code onDisable()} method. */
    DISABLING,

    /** The addon has been disabled (either cleanly or after an error during enable). */
    DISABLED,

    /** The addon encountered an error during loading or enabling. */
    ERRORED
}
