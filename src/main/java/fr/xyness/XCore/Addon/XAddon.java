package fr.xyness.XCore.Addon;

import java.io.File;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;

import org.bukkit.entity.Player;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Extension.ExtensionSupport;
import fr.xyness.XCore.Gui.GuiRegistry;
import fr.xyness.XCore.Gui.GuiUtils;
import fr.xyness.XCore.Lang.LangNamespace;
import fr.xyness.XCore.Utils.Logger;
import fr.xyness.XCore.Utils.SchedulerAdapter;
import fr.xyness.XCore.Utils.Updater;

/**
 * Base class for all XCore addons.
 * <p>
 * Addons extend this class and implement {@link #onEnable()} to initialize their features.
 * The addon lifecycle is managed by {@link AddonManager}, which injects dependencies
 * via {@link #init(AddonDescriptor, XCore, File, Logger, LangNamespace, GuiRegistry)}
 * before any lifecycle methods are called.
 * </p>
 * <p>
 * A plugin of its own — a jar in {@code plugins/} that depends on XCore — extends
 * {@link fr.xyness.XCore.Extension.XPlugin} instead, which offers the same methods. Both share one
 * implementation, so an addon and a plugin read their files the same way.
 * </p>
 *
 * <pre>{@code
 * public class MyAddon extends XAddon {
 *     @Override
 *     public boolean onEnable() {
 *         saveDefaultConfig();
 *         logger().sendInfo("MyAddon enabled!");
 *         return true;
 *     }
 * }
 * }</pre>
 */
public abstract class XAddon {

    private AddonDescriptor descriptor;
    private XCore core;
    private File dataFolder;
    private Logger logger;
    private LangNamespace lang;
    private GuiRegistry guiRegistry;

    /** Configuration, files, languages and updates: shared with {@code XPlugin}, created by {@link #init}. */
    private ExtensionSupport support;

    /** Built on first use, so an addon that never opens a GUI never creates one. */
    private GuiUtils guiUtils;

    /** Same for cooldowns. */
    private fr.xyness.XCore.Utils.Cooldowns cooldowns;

    // -------------------------------------------------------------------------
    // Lifecycle methods (override in subclasses)
    // -------------------------------------------------------------------------

    /**
     * Called after the addon is loaded but before it is enabled.
     * Use this for early initialization that must happen before other addons are enabled.
     */
    public void onLoad() {}

    /**
     * Called when the addon is being enabled.
     * This is the main initialization entry point for addons.
     *
     * @return {@code true} if the addon enabled successfully, {@code false} to mark it as errored.
     */
    public abstract boolean onEnable();

    /**
     * Called when the addon is being disabled.
     * Clean up resources, save data, unregister listeners, etc.
     */
    public void onDisable() {}

    /**
     * Called when the addon is being reloaded.
     * Default implementation does nothing; override to handle config/lang reloads.
     */
    public void onReload() {}

    // -------------------------------------------------------------------------
    // Provided accessors (final, available after init)
    // -------------------------------------------------------------------------

    /**
     * Returns the XCore plugin instance.
     *
     * @return The {@link XCore} instance.
     */
    public final XCore core() { return core; }

    /**
     * Returns the Bukkit/Folia-compatible scheduler adapter.
     *
     * @return The {@link SchedulerAdapter} instance.
     */
    public final SchedulerAdapter scheduler() { return core.schedulerAdapter(); }

    /**
     * Returns the addon-scoped logger.
     *
     * @return The {@link Logger} instance scoped to this addon's name.
     */
    public final Logger logger() { return logger; }

    /**
     * Returns the addon's data folder ({@code plugins/XCore/addons/<name>/}).
     *
     * @return The data folder.
     */
    public final File getDataFolder() { return dataFolder; }

    /**
     * Returns the addon's language namespace for retrieving localized messages.
     *
     * @return The {@link LangNamespace} instance.
     */
    public final LangNamespace lang() { return lang; }

    /**
     * Returns the addon's GUI registry for registering GUI definitions.
     *
     * @return The {@link GuiRegistry} instance.
     */
    public final GuiRegistry guiRegistry() { return guiRegistry; }

    /**
     * Returns the addon descriptor parsed from {@code addon.yml}.
     *
     * @return The {@link AddonDescriptor}.
     */
    public final AddonDescriptor getDescriptor() { return descriptor; }

    // -------------------------------------------------------------------------
    // Shared services
    // -------------------------------------------------------------------------

    /**
     * Returns the public API.
     *
     * @return The {@link fr.xyness.XCore.API.XCoreApi} instance.
     */
    public fr.xyness.XCore.API.XCoreApi api() {
        return fr.xyness.XCore.API.XCoreApiProvider.get();
    }

    /**
     * Returns the shared GUI helpers.
     *
     * @return A {@link GuiUtils} instance for this addon.
     */
    public GuiUtils guiUtils() {
        if (guiUtils == null) guiUtils = new GuiUtils();
        return guiUtils;
    }

    /**
     * Returns the mailbox for things a player could not be handed straight away: a reward when the
     * inventory was full, a payment for someone offline.
     *
     * @return The {@link fr.xyness.XCore.Delivery.DeliveryService}.
     */
    public final fr.xyness.XCore.Delivery.DeliveryService delivery() {
        return core.delivery();
    }

    /**
     * Returns the shared Discord sender.
     *
     * @return The {@link fr.xyness.XCore.Integrations.DiscordNotifier}.
     */
    public final fr.xyness.XCore.Integrations.DiscordNotifier discord() {
        return core.discord();
    }

    /**
     * Returns the leaderboard service.
     *
     * @return The {@link fr.xyness.XCore.Leaderboards.LeaderboardService}.
     */
    public final fr.xyness.XCore.Leaderboards.LeaderboardService leaderboards() {
        return core.leaderboards();
    }

    /**
     * Returns the network registry: which servers are up, and where the players are.
     *
     * @return The {@link fr.xyness.XCore.Network.NetworkRegistry}.
     */
    public final fr.xyness.XCore.Network.NetworkRegistry network() {
        return core.network();
    }

    /**
     * Returns group and rank lookups.
     *
     * @return The {@link fr.xyness.XCore.Integrations.RankResolver}.
     */
    public final fr.xyness.XCore.Integrations.RankResolver ranks() {
        return core.ranks();
    }

    /**
     * Has this addon's data follow a returning player whose UUID changed.
     *
     * <p>When XCore recognises a player under a new UUID ({@code migrate-uuid-changes}: a server
     * switched between offline and online UUIDs, or put behind a proxy that changes them), it moves
     * its own row during the player's pre-login, then calls every handler with the old and the new
     * UUID, and the login waits for them — five seconds each at most — before going on. The checks
     * this addon runs at pre-login (at {@code NORMAL} or later) and at join therefore already see
     * the data under the new UUID. A handler that fails or runs out of time is reported under this
     * addon's name; the login is not refused for it.</p>
     *
     * <pre>{@code
     * onUuidChange((oldUuid, newUuid, name) -> CompletableFuture.runAsync(
     *         () -> store.moveAll(oldUuid, newUuid), api().getDbExecutor()));
     * }</pre>
     *
     * <p>Register it in {@link #onEnable()}. It is taken back when the addon is disabled.</p>
     *
     * @param handler What moves this addon's data; see {@link fr.xyness.XCore.Identity.UuidChangeHandler}.
     */
    public final void onUuidChange(fr.xyness.XCore.Identity.UuidChangeHandler handler) {
        core.uuidChanges().register(getDescriptor().getName(), handler);
    }

    /**
     * Returns this addon's cooldowns.
     *
     * @return A {@link fr.xyness.XCore.Utils.Cooldowns} instance of its own.
     */
    public final fr.xyness.XCore.Utils.Cooldowns cooldowns() {
        if (cooldowns == null) cooldowns = new fr.xyness.XCore.Utils.Cooldowns();
        return cooldowns;
    }

    /**
     * Returns this addon's placeholders.
     *
     * <p>Register them in {@code onEnable()} and call
     * {@link fr.xyness.XCore.Integrations.PlaceholderRegistry#publish()} once; the identifier is the
     * addon name in lower case.</p>
     *
     * @return The {@link fr.xyness.XCore.Integrations.PlaceholderRegistry}.
     */
    public final fr.xyness.XCore.Integrations.PlaceholderRegistry placeholders() {
        return support.placeholders();
    }

    // -------------------------------------------------------------------------
    // Listener registration
    // -------------------------------------------------------------------------

    /**
     * Registers a Bukkit event listener for this addon.
     * <p>
     * The listener is registered under XCore's plugin instance and tracked by
     * the {@link AddonListenerRegistry}. When this addon is disabled, all its
     * listeners are automatically unregistered without affecting other addons.
     * </p>
     * <p>
     * <b>Prefer this method</b> over calling
     * {@code Bukkit.getPluginManager().registerEvents(listener, core())} directly.
     * </p>
     *
     * @param listener The Bukkit event listener to register.
     */
    public final void registerListener(Listener listener) {
        core.getListenerRegistry().registerListener(getDescriptor().getName(), listener);
    }

    // -------------------------------------------------------------------------
    // Config convenience methods
    // -------------------------------------------------------------------------

    /**
     * Saves the default {@code config.yml} from the addon JAR to the data folder
     * if it does not already exist.
     */
    public final void saveDefaultConfig() {
        support.saveDefaultConfig();
    }

    /**
     * Saves a resource from the addon JAR to the data folder if it does not already exist.
     *
     * @param path The resource path inside the JAR (e.g. {@code "config.yml"}, {@code "lang.yml"}).
     */
    public final void saveDefaultResource(String path) {
        support.saveDefaultResource(path);
    }

    /**
     * Alias for {@link #saveDefaultResource(String)}.
     */
    public final void saveResource(String path, boolean replace) {
        support.saveResource(path, replace);
    }

    /**
     * Returns the addon's configuration.
     * Loads from disk on first access.
     *
     * @return The {@link FileConfiguration} for this addon.
     */
    public final FileConfiguration getConfig() {
        return support.getConfig();
    }

    /**
     * Reloads the addon's configuration from disk.
     */
    public final void reloadConfig() {
        support.reloadConfig();
    }

    /**
     * Saves the addon's configuration to disk.
     */
    public final void saveConfig() {
        support.saveConfig();
    }

    /**
     * Whether {@code config.yml} was read as it stands on disk.
     *
     * <p>{@code false} means the file is not valid YAML. It is then left untouched — nothing merges
     * into it, prunes it or saves over it — and the settings in use are the bundled defaults (at
     * start) or the ones read before (after a reload). Worth telling whoever asked for the reload.</p>
     *
     * @return {@code true} when the file was read.
     */
    public final boolean isConfigReadable() {
        return support.isConfigReadable();
    }

    /**
     * @return Where {@code config.yml} stops being YAML and why, as one line of plain text, or
     *         {@code null} when it was read.
     */
    public final String configProblem() {
        return support.configProblem();
    }

    /**
     * Changes a few keys of {@code config.yml} on disk, and the same keys in memory.
     *
     * <p>To be used instead of {@code getConfig().set(...)} followed by {@link #saveConfig()}, which
     * writes the configuration as it was last read: whatever an administrator typed into the file
     * since then, and has not reloaded yet, is lost. This reads the file as it is now, applies the
     * change and saves that.</p>
     *
     * @param change Sets the keys; called on the file's content, then on the loaded configuration.
     * @return {@code false} when nothing was written: the file is not valid YAML, or could not be saved.
     */
    public final boolean updateConfigFile(java.util.function.Consumer<FileConfiguration> change) {
        return support.updateConfigFile(change);
    }

    // -------------------------------------------------------------------------
    // Update checker
    // -------------------------------------------------------------------------

    /**
     * Initializes the update checker for this addon.
     * Call this in {@link #onEnable()} to enable update checking and join notifications.
     *
     * <p>The address is the {@code update-url} declared in {@code addon.yml}. Without one, update
     * checking stays off.</p>
     */
    public final void initUpdater() {
        support.initUpdater();
    }


    /**
     * Returns the updater instance, or {@code null} if not initialized.
     *
     * @return The {@link Updater} instance.
     */
    public final Updater updater() { return support == null ? null : support.updater(); }

    /**
     * Checks for updates and notifies the player if one is available.
     * Call this in your PlayerJoinEvent handler for players with the update notification permission.
     *
     * <p>The wording comes from {@code update-available} in the language file — the addon's own
     * if it defines the key, XCore's otherwise. It takes {@code {addon}}, {@code {version}} and
     * {@code {date}}.</p>
     *
     * @param player         The player to notify.
     * @param permissionNode The permission required to receive update notifications (e.g. {@code "ah.update"}).
     */
    public final void notifyUpdateOnJoin(Player player, String permissionNode) {
        support.notifyUpdateOnJoin(player, permissionNode);
    }

    /**
     * Updates the configuration file by adding missing keys from the default config in the JAR.
     * Existing values are preserved; only new keys are added.
     *
     * @param protectedSections Sections whose contents are user-managed (e.g. key:value maps like
     *                          {@code "chunk-limits.blocks"} or named entries like {@code "shops"}).
     *                          Nothing under a protected path is ever re-injected — regardless of
     *                          whether the section is present on disk. The initial defaults are
     *                          still written on first run via {@link #saveDefaultConfig()}.
     */
    public final void updateConfigWithDefaults(String... protectedSections) {
        support.updateConfigWithDefaults(protectedSections);
    }

    /**
     * Declares keys this addon renamed since an earlier version, so the value an administrator gave
     * the old name moves to the new one instead of being replaced by the default.
     *
     * <p>A renamed key otherwise looks like one key to add and one to delete: the merge writes the
     * new name with the shipped default, the prune removes the old one, and the customised value is
     * gone. Declare the renames first in {@link #onEnable()} — before
     * {@link #updateConfigWithDefaults(String...)}, {@link #loadLanguage(String...)} and
     * {@link #pruneObsoleteKeys(String, String...)} — and keep them across versions: they cost
     * nothing once applied.</p>
     *
     * <pre>{@code
     * @Override
     * public boolean onEnable() {
     *     renamedKeys("config.yml", Map.of("anti-afk.enabled", "afk.enabled"));
     *     renamedKeys("lang/*.yml", Map.of("lore-buyout", "lore-buyout-drop"));
     *     updateConfigWithDefaults();
     *     loadLanguage("en", "fr");
     *     return true;
     * }
     * }</pre>
     *
     * <p>A value moves only when the new key is absent; nested paths and whole sections move; a
     * chain ({@code a → b}, then {@code b → c}) is followed; applying it twice changes nothing. Each
     * move is logged once, when it happens.</p>
     *
     * @param file    The file, relative to the data folder: {@code "config.yml"}, {@code "lang/fr.yml"},
     *                or {@code "lang/*.yml"} for every language file ({@code *} in the last segment only).
     * @param renames Old path → new path.
     * @return How many values moved now.
     */
    public final int renamedKeys(String file, java.util.Map<String, String> renames) {
        return support.renamedKeys(file, renames);
    }

    /**
     * Removes keys the addon no longer reads, using the bundled defaults as the reference.
     *
     * <p>The counterpart to {@link #updateConfigWithDefaults(String...)}, which only ever <em>adds</em>.
     * After a refactor an existing install keeps every renamed or deleted key, silently inert — which
     * is how an administrator ends up certain a setting is applied when nothing reads it any more.
     * Anything absent from the bundled file is therefore dead by definition and is dropped.</p>
     *
     * <p>Two safeguards. Protected sections are never touched: they are the ones whose keys the user
     * legitimately invents — world names, material lists, shop entries — and which the defaults
     * cannot possibly enumerate. And the file is copied to {@code <name>.pre-prune.bak} before the
     * first removal, because this deletes user data.</p>
     *
     * <p>YAML lists are values, not sections, so their contents are never walked: a list a user has
     * extended is kept whole as long as its key still exists in the defaults.</p>
     *
     * @param fileName          The file to prune, relative to the data folder and to the JAR root
     *                          (typically {@code "config.yml"} or {@code "lang.yml"}).
     * @param protectedSections Paths whose contents are user-managed and must be left alone.
     * @return The number of keys removed.
     */
    public final int pruneObsoleteKeys(String fileName, String... protectedSections) {
        return support.pruneObsoleteKeys(fileName, protectedSections);
    }

    /**
     * Prunes {@code config.yml}. Convenience overload of
     * {@link #pruneObsoleteKeys(String, String...)}.
     *
     * @param protectedSections Paths whose contents are user-managed.
     * @return The number of keys removed.
     */
    public final int pruneObsoleteConfigKeys(String... protectedSections) {
        return support.pruneObsoleteConfigKeys(protectedSections);
    }

    // -------------------------------------------------------------------------
    // Language
    // -------------------------------------------------------------------------

    /**
     * Resolves which language this addon should speak.
     *
     * <p>XCore's {@code language} setting drives the whole installation: pick French once and every
     * addon follows, instead of repeating the choice in a dozen files. An addon may still override
     * it with its own {@code language} key — useful when one addon has no translation for the
     * server's language, or when a network deliberately runs one component in another language.</p>
     *
     * @return The language code, lower-cased (e.g. {@code "fr"}).
     */
    public final String getLanguage() {
        return support.getLanguage();
    }

    /**
     * Copies a YAML file aside when it cannot be parsed, and says so.
     *
     * <p>{@code YamlConfiguration.loadConfiguration} answers an unreadable file with an empty
     * configuration. To the merge that follows, every default then looks missing: it adds them all
     * and writes the result back — over the file. One stray tab in a config.yml used to cost every
     * setting in it at the next start, with nothing left to recover them from. The file is now kept
     * under another name first, and the console names it.</p>
     *
     * @param file The file about to be merged with its defaults.
     * @return {@code true} when the file could not be read and a copy was made.
     */
    protected final boolean setAsideIfUnreadable(File file) {
        return support.setAsideIfUnreadable(file);
    }

    /**
     * Loads this addon's language file, honouring the resolved language.
     *
     * <p>Files live in {@code <addon>/lang/<code>.yml}. Every language bundled in the jar is
     * extracted on first run so the administrator can read and edit them all; the one matching
     * {@link #getLanguage()} is the one loaded, falling back to English when the addon ships no
     * translation for it.</p>
     *
     * @param available The language codes bundled in the jar, English first.
     */
    public final void loadLanguage(String... available) {
        support.loadLanguage(available);
    }

    // -------------------------------------------------------------------------
    // Internal init (package-private, called by AddonManager)
    // -------------------------------------------------------------------------

    /**
     * Injects dependencies into the addon. Called by {@link AddonManager} before any lifecycle method.
     *
     * @param descriptor  The addon descriptor.
     * @param core        The XCore plugin instance.
     * @param dataFolder  The addon's data folder.
     * @param logger      The addon-scoped logger.
     * @param lang        The addon's language namespace.
     * @param guiRegistry The addon's GUI registry.
     */
    void init(AddonDescriptor descriptor, XCore core, File dataFolder, Logger logger, LangNamespace lang, GuiRegistry guiRegistry) {
        this.descriptor = descriptor;
        this.core = core;
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.lang = lang;
        this.guiRegistry = guiRegistry;
        // Resources come from the addon's own class loader, which looks in its JAR before XCore's:
        // both ship a config.yml and a lang/en.yml, and the addon's are the ones meant here.
        this.support = new ExtensionSupport(descriptor, core, dataFolder, logger, lang, guiRegistry,
                getClass().getClassLoader(), core::schedulerAdapter, "addon.yml", "addon");
    }
}
