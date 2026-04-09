package fr.xyness.XCore.Lang;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized language manager that provides per-addon {@link LangNamespace} instances.
 * <p>
 * Each addon registers its own namespace with a language file and optional defaults.
 * The core plugin's namespace is available via {@link #core()}.
 * </p>
 */
public class LangManager {

    /** The reserved namespace name for XCore itself. */
    private static final String CORE_NAMESPACE = "xcore";

    /** Registered namespaces keyed by addon name (lowercase). */
    private final Map<String, LangNamespace> namespaces = new ConcurrentHashMap<>();

    /**
     * Registers a new addon language namespace.
     * <p>
     * Loads messages from the given file, merging defaults from the provided
     * input stream. If the file does not exist, it will be created from defaults
     * by the caller before invoking this method.
     * </p>
     *
     * @param addonName The unique addon name (case-insensitive).
     * @param langFile  The YAML language file on disk.
     * @param defaults  An {@link InputStream} to the embedded default resource, or {@code null}.
     * @return The created and loaded {@link LangNamespace}.
     */
    public LangNamespace registerAddon(String addonName, File langFile, InputStream defaults) {
        LangNamespace namespace = new LangNamespace();
        namespace.reload(langFile, defaults);
        namespaces.put(addonName.toLowerCase(), namespace);
        return namespace;
    }

    /**
     * Returns the language namespace for the given addon.
     *
     * @param addonName The addon name (case-insensitive).
     * @return The {@link LangNamespace}, or {@code null} if not registered.
     */
    public LangNamespace getNamespace(String addonName) {
        return namespaces.get(addonName.toLowerCase());
    }

    /**
     * Returns XCore's own language namespace.
     *
     * @return The core {@link LangNamespace}, or {@code null} if not yet registered.
     */
    public LangNamespace core() {
        return namespaces.get(CORE_NAMESPACE);
    }
}
