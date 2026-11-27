package fr.xyness.XCore.Extension;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Addon.AddonDescriptor;
import fr.xyness.XCore.Addon.AddonState;
import fr.xyness.XCore.Gui.GuiRegistry;
import fr.xyness.XCore.Gui.GuiUtils;
import fr.xyness.XCore.Lang.LangNamespace;
import fr.xyness.XCore.Utils.Logger;
import fr.xyness.XCore.Utils.SchedulerAdapter;
import fr.xyness.XCore.Utils.Updater;
import io.papermc.paper.plugin.configuration.PluginMeta;

/**
 * Base class of a plugin built on XCore: a jar in {@code plugins/}, loaded by the server, that
 * depends on XCore for its database, caches, synchronisation, GUIs, languages, economy and
 * dashboard.
 *
 * <p>It offers what {@link fr.xyness.XCore.Addon.XAddon} offers, under the same names and with the
 * same behaviour — both delegate to one implementation — so an addon becomes a plugin by changing
 * its base class, its lifecycle method names and its descriptor. The differences are the ones a
 * real plugin brings:</p>
 * <ul>
 *   <li><b>Lifecycle.</b> The server's {@code onLoad}, {@code onEnable} and {@code onDisable} are
 *       final here and call {@link #load()}, {@link #enable()} and {@link #disable()}.
 *       {@code enable()} returns a boolean like an addon's {@code onEnable()} did: {@code false}
 *       disables the plugin, with a line in the console. {@link #disable()} only runs after an
 *       {@code enable()} that returned {@code true} — the contract addons were written against.
 *       {@link #reload()} is what {@code /xcore reload} calls. {@link #startFailed(String)} is
 *       called on every path where the plugin does not start, and may keep it enabled in a minimal
 *       state (an authentication plugin keeping the server closed).</li>
 *   <li><b>Its files</b> live in {@code plugins/<name>/}. On the first start, the folder the addon
 *       used, {@code plugins/XCore/addons/<name>/}, is moved there before anything reads it; when
 *       that fails, the plugin does not start rather than run on default settings.</li>
 *   <li><b>What it creates is its own.</b> {@link #scheduler()} schedules under this plugin, so its
 *       tasks stop when it is disabled; {@link #registerListener(Listener)} registers under this
 *       plugin, still timed by {@code /xcore profile}; its commands go through its own
 *       {@link #getLifecycleManager()}. What it handed XCore — web modules, sync channels,
 *       placeholders, UUID change handlers — is taken back when it is disabled.</li>
 *   <li><b>XCore still sees it.</b> It registers with {@link XCore#extensions()} when it enables,
 *       so {@code /xcore addons}, {@code /xcore reload}, {@code /xcore diag}, the dashboard and the
 *       {@code addons.yml} switches cover it as they cover addons.</li>
 * </ul>
 *
 * <pre>{@code
 * public class MyPlugin extends XPlugin {
 *     @Override
 *     protected boolean enable() {
 *         updateConfigWithDefaults();
 *         loadLanguage("en", "fr");
 *         registerListener(new MyListener(this));
 *         return true;
 *     }
 *
 *     @Override
 *     public void reload() {
 *         reloadConfig();
 *         loadLanguage("en", "fr");
 *     }
 * }
 * }</pre>
 *
 * <p>Its {@code paper-plugin.yml} declares XCore under {@code dependencies.server} with
 * {@code load: BEFORE}, {@code required: true} and {@code join-classpath: true}.</p>
 */
public abstract class XPlugin extends JavaPlugin {

    private XCore core;
    private Logger logger;
    private LangNamespace lang;
    private GuiRegistry guiRegistry;
    private AddonDescriptor descriptor;
    private SchedulerAdapter scheduler;

    /** Configuration, files, languages and updates: shared with {@code XAddon}, created in {@link #onLoad()}. */
    private ExtensionSupport support;

    /** Built on first use, so a plugin that never opens a GUI never creates one. */
    private GuiUtils guiUtils;

    /** Same for cooldowns. */
    private fr.xyness.XCore.Utils.Cooldowns cooldowns;

    private volatile AddonState state;

    /** Set once {@link #enable()} returned {@code true}: only then does {@link #disable()} run. */
    private volatile boolean started;

    /** Why this plugin cannot start, found in {@link #onLoad()}; {@code null} when it can. */
    private String refusal;

    /** Switched off in {@code addons.yml}: a choice, not an error. */
    private boolean switchedOff;

    /** Did not start, and stays enabled with what {@link #startFailed(String)} set up. */
    private volatile boolean held;

    /** What XCore's registry holds for this plugin. */
    private final View view = new View();

    // -------------------------------------------------------------------------
    // Lifecycle methods (override in subclasses)
    // -------------------------------------------------------------------------

    /**
     * Called while the server loads plugins, before any is enabled — XCore's database and services
     * are not up yet. The data folder has already been brought over from
     * {@code plugins/XCore/addons/<name>/} when there was one.
     */
    protected void load() {}

    /**
     * Called when the plugin is enabled, once XCore is running: the main initialization.
     *
     * @return {@code true} if it started; {@code false} to have it disable itself cleanly.
     */
    protected abstract boolean enable();

    /**
     * Called when the plugin is disabled, if {@link #enable()} had returned {@code true}.
     * XCore is still running at this point. Like any plugin's {@code onDisable}, it can no longer
     * schedule tasks: the server refuses them from a plugin being disabled.
     */
    protected void disable() {}

    /**
     * Re-reads configuration and language files. Called by {@code /xcore reload}, by the
     * dashboard after it saved this plugin's {@code config.yml}, and by the plugin's own reload
     * command if it has one. Runs on the server thread.
     */
    public void reload() {}

    /**
     * Called on every path where this plugin does not start: XCore is not running, the plugin could
     * not be prepared (its files could not be brought over from the addon folder, {@link #load()}
     * threw), or {@link #enable()} returned {@code false} or threw. Not when it is switched off in
     * {@code addons.yml}: that is the administrator's choice, not a failure.
     *
     * <p>By then, whatever {@code enable()} had registered is taken down: its listeners, tasks,
     * plugin channels and services, and what XCore held for it. Commands registered through the
     * lifecycle manager stay — the server offers no way to take them back short of disabling the
     * plugin — which is one more reason to register them last in {@code enable()}.</p>
     *
     * <p>By default nothing more happens, and the plugin is disabled. A plugin whose absence is a
     * danger in itself returns {@code true} to stay enabled with what it sets up here and nothing
     * else: an authentication plugin refusing every connection, so that an offline-mode server whose
     * authentication did not start is closed rather than open to whoever types a name. It is then
     * listed as errored by {@code /xcore addons}, cannot be reloaded, and {@link #disable()} is not
     * called for it; what it registered here goes when the server disables it.</p>
     *
     * <p><b>XCore may be gone when this runs.</b> When XCore's own start failed, the server has
     * disabled it and closed its jar: a class of XCore this plugin had no reason to load yet cannot
     * be loaded any more, and none of its services is there. Use the server's API and this plugin's
     * own classes only — a listener class of its own registered under this plugin, files read with
     * {@code YamlConfiguration} — plus {@link #logger()}. Not {@link #api()}, {@link #lang()},
     * {@link #getConfig()} or {@link #scheduler()}. {@code core() != null && core().isRunning()}
     * says whether XCore is up.</p>
     *
     * @param reason Why the plugin did not start, as the console was told.
     * @return {@code true} to stay enabled with what was set up here; {@code false} to be disabled.
     */
    protected boolean startFailed(String reason) {
        return false;
    }

    // -------------------------------------------------------------------------
    // The server's lifecycle (final: the hooks above are what subclasses override)
    // -------------------------------------------------------------------------

    @Override
    public final void onLoad() {
        String name = getName();
        this.logger = new Logger(name);
        try {
            this.lang = new LangNamespace();
            this.guiRegistry = new GuiRegistry();
            this.descriptor = describe();
            this.scheduler = new SchedulerAdapter(this, logger);
            this.core = findCore();

            // Switched off in addons.yml: left alone, its files included, until it is switched on.
            // A move that failed here would otherwise stop a plugin the administrator did not want
            // running — and XLogin would close the server they had chosen to open.
            if (core != null && isSwitchedOff(name)) {
                // Said at enable, where the line sits next to the server's own "Enabling".
                switchedOff = true;
                state = AddonState.DISABLED;
                return;
            }

            // First, before anything can read a file: the addon this plugin used to be kept its
            // settings and data in plugins/XCore/addons/<name>/.
            DataFolderMigration.Result moved = DataFolderMigration.run(name, getDataFolder(), logger);
            if (moved.failed()) {
                // Started anyway, it would write its defaults into plugins/<name>/, run on them, and
                // every later start would find both folders full and move nothing, for good.
                refusal = "its settings and data could not be brought over from " + moved.oldFolder()
                        + " (see above), and it does not run on default settings in their place.";
                state = AddonState.ERRORED;
                return;
            }

            // Resources from this plugin's own jar: XCore's has a config.yml and a lang/en.yml too.
            this.support = new ExtensionSupport(descriptor, core, getDataFolder(), logger, lang, guiRegistry,
                    getClass().getClassLoader(), () -> scheduler, "paper-plugin.yml", "plugin");
            this.state = AddonState.LOADED;
        } catch (Throwable t) {
            // Not expected to happen. Recorded, so that onEnable says it plainly instead of failing
            // on whatever was left unset.
            refusal = "it could not be prepared: " + t;
            state = AddonState.ERRORED;
            logger.sendError("Error loading " + name + ": " + t);
            return;
        }

        if (core == null) {
            refusal = "XCore is not loaded, and " + name + " runs on it.";
            state = AddonState.ERRORED;
            logger.sendError(refusal);
            return;
        }
        try {
            load();
        } catch (Throwable t) {
            refusal = "load() failed: " + t;
            state = AddonState.ERRORED;
            logger.sendError("Error loading " + name + ": " + t);
        }
    }

    @Override
    public final void onEnable() {
        String name = getName();

        if (switchedOff) {
            logger.sendWarning(name + " is switched off in plugins/XCore/addons.yml (addons." + name
                    + ": false) and stays disabled. Set it to true, or remove the plugin's jar.");
            stopHere();
            return;
        }
        if (refusal != null || core == null) {
            String why = refusal != null ? refusal : "XCore is not loaded.";
            logger.sendError(name + " cannot start: " + why);
            failClosed(why);
            return;
        }
        // The server enables a plugin even when the one it depends on failed to start: XCore is
        // disabled by now, its jar closed, and this plugin's enable() is never reached.
        if (!core.isRunning()) {
            String why = "XCore did not start (see the lines above), and " + name + " runs on it.";
            logger.sendError(why);
            failClosed(why);
            return;
        }

        File folder = getDataFolder();
        if (!folder.exists()) folder.mkdirs();

        // The two switches an addon had in addons.yml, under the same name, so the values set for
        // the addon go on applying to the plugin it became.
        core.registerAddonToggle(name);
        core.registerSyncAddon(name);
        core.saveDataConfigIfChanged();

        state = AddonState.ENABLING;
        core.extensions().register(view);

        boolean enabled;
        String why = "enable() returned false";
        try {
            enabled = enable();
        } catch (Throwable t) {
            // Caught here rather than left to the server, so a throw ends like a false return:
            // errored in /xcore addons, one line saying why. Throwable, because a plugin built
            // against another XCore fails with a LinkageError, which is not an Exception.
            logger.sendError("Error enabling " + name + ": " + t);
            enabled = false;
            why = "enable() failed: " + t;
        }

        if (!isEnabled()) {
            // enable() disabled the plugin itself; onDisable has already recorded the failure.
            return;
        }
        if (enabled) {
            started = true;
            state = AddonState.ENABLED;
            logger.sendInfo("Enabled plugin: " + descriptor);
            // XCore holds the server's door shut until XLogin is up: this may be what it waits for.
            try {
                core.extensionEnabled(name);
            } catch (Throwable t) {
                logger.sendDebug("Could not tell XCore that " + name + " is up: " + t);
            }
            return;
        }
        logger.sendError(name + " did not start (" + why + "). The lines above say why.");
        failClosed(why);
    }

    @Override
    public final void onDisable() {
        String name = getName();
        boolean wasStarted = started;
        started = false;
        held = false;

        if (wasStarted) {
            state = AddonState.DISABLING;
            try {
                disable();
            } catch (Throwable t) {
                logger.sendError("Error disabling " + name + ": " + t);
            }
        }
        // The server drops this plugin's tasks once this returns: what one of them was to hand
        // over is handed over now, by its fallback.
        try {
            if (scheduler != null) scheduler.runPendingFallbacks();
        } catch (Throwable t) {
            logger.sendError("Pending tasks of " + name + " could not hand over what they held: " + t);
        }

        release();

        if (core == null) return;
        if (wasStarted) {
            state = AddonState.DISABLED;
            core.extensions().unregister(view);
            logger.sendInfo("Disabled plugin: " + name);
        } else if (!switchedOff && core.isRunning()) {
            // A start that failed stays listed as errored, as a failed addon does, but as a
            // snapshot: the registry must not keep this instance and its class loader alive.
            state = AddonState.ERRORED;
            core.extensions().register(ExtensionRegistry.Snapshot.of(view, AddonState.ERRORED));
        }
    }

    /** Disables this plugin from inside its own enable: the server runs {@link #onDisable()} at once. */
    private void stopHere() {
        Bukkit.getPluginManager().disablePlugin(this);
    }

    /**
     * Ends a start that did not happen: takes down what {@code enable()} may have left, then lets
     * {@link #startFailed(String)} keep the plugin enabled in a minimal state, or disables it.
     *
     * <p>Runs while XCore may be gone, its jar closed (see {@code startFailed}): nothing here needs a
     * class of XCore that this plugin had no reason to load already, and every call into XCore is
     * guarded.</p>
     *
     * @param why Why it did not start, as the console was told.
     */
    private void failClosed(String why) {
        String name = getName();
        state = AddonState.ERRORED;
        tearDown();

        boolean stay;
        try {
            stay = startFailed(why);
        } catch (Throwable t) {
            logger.sendError("Error in startFailed() of " + name + ": " + t);
            stay = false;
        }
        if (stay && isEnabled()) {
            held = true;
            logger.sendWarning(name + " did not start, and stays enabled with only what it keeps up for that case (see above).");
            // Listed as errored wherever XCore lists extensions, and refused a reload there.
            try {
                if (core != null && core.isRunning()) core.extensions().register(view);
            } catch (Throwable t) {
                logger.sendDebug("Could not list " + name + " with XCore's extensions: " + t);
            }
            return;
        }
        stopHere();
    }

    /**
     * Takes down what a start that did not complete may have left behind: the plugin's listeners,
     * tasks, plugin channels and services on the server's side, what it handed XCore on the other.
     *
     * <p>The server takes the first half down itself when it disables a plugin. A plugin that stays
     * enabled after a failed start would otherwise keep half a start running — listeners calling
     * managers that were never created.</p>
     */
    private void tearDown() {
        try {
            HandlerList.unregisterAll(this);
        } catch (Throwable ignored) {}
        try {
            if (scheduler != null) scheduler.cancelAllTasks();
        } catch (Throwable ignored) {}
        try {
            getServer().getMessenger().unregisterIncomingPluginChannel(this);
            getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        } catch (Throwable ignored) {}
        try {
            getServer().getServicesManager().unregisterAll(this);
        } catch (Throwable ignored) {}
        release();
    }

    /**
     * Whether this plugin did not start and stays enabled, with only what
     * {@link #startFailed(String)} set up.
     *
     * @return {@code true} while it is held that way.
     */
    public final boolean isHeld() {
        return held;
    }

    /**
     * Takes back from XCore what this plugin handed it.
     *
     * <p>The server already drops this plugin's tasks and Bukkit listeners, since they are owned by
     * it. XCore holds the rest: the names its listeners are counted under, its dashboard module and
     * routes, its sync channels, its UUID change handlers, its placeholders. Each is released on its
     * own, so one failing does not keep the others.</p>
     */
    private void release() {
        if (core == null) return;
        ClassLoader loader = getClass().getClassLoader();
        try {
            if (core.getListenerRegistry() != null) core.getListenerRegistry().unregisterAll(getName());
        } catch (Throwable t) {
            logger.sendDebug("Could not release the listeners: " + t);
        }
        try {
            if (core.getWebPanel() != null) core.getWebPanel().unregisterModulesOf(loader);
        } catch (Throwable t) {
            logger.sendDebug("Could not release the web module: " + t);
        }
        try {
            if (core.getSyncManager() != null) core.getSyncManager().unregisterChannelsOf(loader);
        } catch (Throwable t) {
            logger.sendDebug("Could not release the sync channels: " + t);
        }
        try {
            if (core.uuidChanges() != null) core.uuidChanges().unregisterAll(this);
        } catch (Throwable t) {
            logger.sendDebug("Could not release the UUID change handlers: " + t);
        }
        try {
            if (support != null) support.withdrawPlaceholders();
        } catch (Throwable t) {
            logger.sendDebug("Could not release the placeholders: " + t);
        }
    }

    private boolean isSwitchedOff(String name) {
        try {
            return !core.isAddonEnabled(name);
        } catch (Throwable t) {
            // addons.yml unreadable: XCore said so already, and a missing switch means on.
            return false;
        }
    }

    /**
     * Finds XCore. Through its class rather than its name: the class this plugin was compiled
     * against is the one that must be running.
     */
    private static XCore findCore() {
        try {
            return JavaPlugin.getPlugin(XCore.class);
        } catch (Throwable t) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("XCore");
            return plugin instanceof XCore xcore ? xcore : null;
        }
    }

    /**
     * The descriptor an addon had, built from what the server read in {@code paper-plugin.yml},
     * plus the one key the server has no use for: {@code update-url}.
     */
    private AddonDescriptor describe() {
        PluginMeta meta = getPluginMeta();
        String author = meta.getAuthors() == null || meta.getAuthors().isEmpty()
                ? "Unknown" : String.join(", ", meta.getAuthors());
        return new AddonDescriptor(meta.getName(), meta.getVersion(), author, meta.getMainClass(),
                meta.getDescription(), meta.getPluginDependencies(), meta.getPluginSoftDependencies(),
                readUpdateUrl(), getFile());
    }

    /**
     * Reads {@code update-url} from the descriptor in this plugin's jar. The server ignores keys
     * it does not know, so it sits in {@code paper-plugin.yml} as it sat in {@code addon.yml}.
     */
    private String readUpdateUrl() {
        for (String file : new String[]{"paper-plugin.yml", "plugin.yml"}) {
            try (InputStream in = getResource(file)) {
                if (in == null) continue;
                String url = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getString("update-url", "");
                return url == null ? "" : url.trim();
            } catch (IOException | RuntimeException e) {
                return "";
            }
        }
        return "";
    }

    private ExtensionSupport support() {
        if (support == null) {
            throw new IllegalStateException(getName() + " used its configuration before onLoad: read it in load()"
                    + " or enable(), not in a constructor or a field initializer.");
        }
        return support;
    }

    // -------------------------------------------------------------------------
    // Provided accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the XCore plugin instance.
     *
     * @return The {@link XCore} instance.
     */
    public final XCore core() { return core; }

    /**
     * Returns this plugin's Bukkit/Folia scheduler adapter. Tasks scheduled through it belong to
     * this plugin, and stop when it is disabled.
     *
     * @return The {@link SchedulerAdapter} instance.
     */
    public final SchedulerAdapter scheduler() { return scheduler; }

    /**
     * Returns the plugin-scoped logger.
     *
     * @return The {@link Logger} instance scoped to this plugin's name.
     */
    public final Logger logger() { return logger; }

    /**
     * Returns the plugin's language namespace for retrieving localized messages.
     *
     * @return The {@link LangNamespace} instance.
     */
    public final LangNamespace lang() { return lang; }

    /**
     * Returns the plugin's GUI registry for registering GUI definitions.
     *
     * @return The {@link GuiRegistry} instance.
     */
    public final GuiRegistry guiRegistry() { return guiRegistry; }

    /**
     * Returns the descriptor an addon had: name, version, author and description as the server read
     * them in {@code paper-plugin.yml}, and the {@code update-url} declared there.
     *
     * @return The {@link AddonDescriptor}.
     */
    public final AddonDescriptor getDescriptor() { return descriptor; }

    /**
     * Returns where this plugin stands in its lifecycle, as {@code /xcore addons} shows it.
     *
     * @return The state, {@code null} before {@code onLoad}.
     */
    public final AddonState getState() { return state; }

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
     * @return A {@link GuiUtils} instance for this plugin.
     */
    public GuiUtils guiUtils() {
        if (guiUtils == null) guiUtils = new GuiUtils();
        return guiUtils;
    }

    /**
     * Returns the mailbox for things a player could not be handed straight away.
     *
     * @return The {@link fr.xyness.XCore.Delivery.DeliveryService}.
     */
    public final fr.xyness.XCore.Delivery.DeliveryService delivery() {
        return core.delivery();
    }

    /**
     * Returns the money service: withdrawals, deposits and rounding in an XCore currency or in
     * the economy another plugin registered with Vault, behind one set of methods.
     *
     * @return The {@link fr.xyness.XCore.Economy.Money} service.
     * @since 2.1
     */
    public final fr.xyness.XCore.Economy.Money money() {
        return core.money();
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
     * Has this plugin's data follow a returning player whose UUID changed.
     *
     * <p>When XCore recognises a player under a new UUID ({@code migrate-uuid-changes}: a server
     * switched between offline and online UUIDs, or put behind a proxy that changes them), it moves
     * its own row during the player's pre-login, then calls every handler with the old and the new
     * UUID, and the login waits for them — five seconds each at most — before going on. The checks
     * this plugin runs at pre-login (at {@code NORMAL} or later) and at join therefore already see
     * the data under the new UUID. A handler that fails or runs out of time is reported under this
     * plugin's name; the login is not refused for it.</p>
     *
     * <pre>{@code
     * onUuidChange((oldUuid, newUuid, name) -> CompletableFuture.runAsync(
     *         () -> store.moveAll(oldUuid, newUuid), api().getDbExecutor()));
     * }</pre>
     *
     * <p>Register it in {@link #enable()}. It is taken back when the plugin is disabled.</p>
     *
     * @param handler What moves this plugin's data; see {@link fr.xyness.XCore.Identity.UuidChangeHandler}.
     */
    public final void onUuidChange(fr.xyness.XCore.Identity.UuidChangeHandler handler) {
        core.uuidChanges().register(this, handler);
    }

    /**
     * Returns this plugin's cooldowns.
     *
     * @return A {@link fr.xyness.XCore.Utils.Cooldowns} instance of its own.
     */
    public final fr.xyness.XCore.Utils.Cooldowns cooldowns() {
        if (cooldowns == null) cooldowns = new fr.xyness.XCore.Utils.Cooldowns();
        return cooldowns;
    }

    /**
     * Returns this plugin's placeholders. Register them in {@code enable()} and call
     * {@link fr.xyness.XCore.Integrations.PlaceholderRegistry#publish()} once; the identifier is the
     * plugin name in lower case. They are withdrawn when the plugin is disabled.
     *
     * @return The {@link fr.xyness.XCore.Integrations.PlaceholderRegistry}.
     */
    public final fr.xyness.XCore.Integrations.PlaceholderRegistry placeholders() {
        return support().placeholders();
    }

    // -------------------------------------------------------------------------
    // Listener registration
    // -------------------------------------------------------------------------

    /**
     * Registers a Bukkit event listener for this plugin.
     *
     * <p>Registered under this plugin, so the server attributes it here and drops it when the
     * plugin is disabled, and tracked by XCore's listener registry under the plugin name, so
     * {@code /xcore profile} times each handler and {@code /xcore diag} counts it — as for an
     * addon. Prefer it to {@code Bukkit.getPluginManager().registerEvents(listener, this)}, which
     * works but is invisible to both.</p>
     *
     * @param listener The Bukkit event listener to register.
     */
    public final void registerListener(Listener listener) {
        core.getListenerRegistry().registerListener(getName(), this, listener);
    }

    // -------------------------------------------------------------------------
    // Config convenience methods (JavaPlugin's, with an addon's behaviour)
    // -------------------------------------------------------------------------

    /**
     * Saves the bundled {@code config.yml} to the data folder if it does not already exist.
     * Unlike {@code JavaPlugin}'s, does not throw when the jar has none.
     */
    @Override
    public final void saveDefaultConfig() {
        support().saveDefaultConfig();
    }

    /**
     * Saves a resource from the plugin jar to the data folder if it does not already exist.
     *
     * @param path The resource path inside the jar (e.g. {@code "config.yml"}).
     */
    public final void saveDefaultResource(String path) {
        support().saveDefaultResource(path);
    }

    /**
     * Saves a bundled resource to the data folder, overwriting it only when asked to. Unlike
     * {@code JavaPlugin}'s, silent when the jar has no such file or the file already exists.
     */
    @Override
    public final void saveResource(String path, boolean replace) {
        support().saveResource(path, replace);
    }

    /**
     * Returns the configuration, loaded from disk on first access.
     *
     * <p>Not {@code JavaPlugin}'s: that one attaches the bundled file as defaults, so a key the
     * administrator deleted came back from the jar, and {@code contains()} answered {@code true} for
     * a key that is not in the file. An addon never had defaults attached, and its code relies on
     * reading what is on disk.</p>
     *
     * @return The {@link FileConfiguration} for this plugin.
     */
    @Override
    public final FileConfiguration getConfig() {
        return support().getConfig();
    }

    /** Reloads the configuration from disk, without bundled defaults (see {@link #getConfig()}). */
    @Override
    public final void reloadConfig() {
        support().reloadConfig();
    }

    /** Saves the configuration to disk. */
    @Override
    public final void saveConfig() {
        support().saveConfig();
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
        return support().isConfigReadable();
    }

    /**
     * @return Where {@code config.yml} stops being YAML and why, as one line of plain text, or
     *         {@code null} when it was read.
     */
    public final String configProblem() {
        return support().configProblem();
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
        return support().updateConfigFile(change);
    }

    // -------------------------------------------------------------------------
    // Update checker
    // -------------------------------------------------------------------------

    /**
     * Initializes the update checker, on the {@code update-url} declared in {@code paper-plugin.yml}.
     * Without one, update checking stays off. Check asynchronously:
     * {@code scheduler().runAsyncTask(() -> updater().checkForUpdates())}.
     */
    public final void initUpdater() {
        support().initUpdater();
    }

    /**
     * Returns the updater instance, or {@code null} if not initialized.
     *
     * @return The {@link Updater} instance.
     */
    public final Updater updater() { return support == null ? null : support.updater(); }

    /**
     * Tells a joining player about an available update, if they may be told. The wording comes
     * from {@code update-available} in the plugin's language file, XCore's otherwise.
     *
     * @param player         The player to notify.
     * @param permissionNode The permission required to receive update notifications.
     */
    public final void notifyUpdateOnJoin(Player player, String permissionNode) {
        support().notifyUpdateOnJoin(player, permissionNode);
    }

    /**
     * Adds to {@code config.yml} the keys the bundled version has and it does not; nothing under a
     * protected section is ever re-injected.
     *
     * @param protectedSections Sections whose contents are user-managed.
     */
    public final void updateConfigWithDefaults(String... protectedSections) {
        support().updateConfigWithDefaults(protectedSections);
    }

    /**
     * Declares keys this plugin renamed since an earlier version, so the value an administrator
     * gave the old name moves to the new one instead of being replaced by the default.
     *
     * <p>A renamed key otherwise looks like one key to add and one to delete: the merge writes the
     * new name with the shipped default, the prune removes the old one, and the customised value is
     * gone. Declare the renames first in {@link #enable()} — before
     * {@link #updateConfigWithDefaults(String...)}, {@link #loadLanguage(String...)} and
     * {@link #pruneObsoleteKeys(String, String...)} — and keep them across versions: they cost
     * nothing once applied.</p>
     *
     * <pre>{@code
     * @Override
     * protected boolean enable() {
     *     renamedKeys("config.yml", Map.of("anti-afk.enabled", "afk.enabled"));
     *     renamedKeys("lang/*.yml", Map.of("redstone-clock-detected", "redstone-clock-reported"));
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
        return support().renamedKeys(file, renames);
    }

    /**
     * Removes keys the plugin no longer reads, using the bundled defaults as the reference, after a
     * backup to {@code <name>.pre-prune.bak}.
     *
     * @param fileName          The file to prune, relative to the data folder and to the jar root.
     * @param protectedSections Paths whose contents are user-managed and must be left alone.
     * @return The number of keys removed.
     */
    public final int pruneObsoleteKeys(String fileName, String... protectedSections) {
        return support().pruneObsoleteKeys(fileName, protectedSections);
    }

    /**
     * Prunes {@code config.yml}. Convenience overload of
     * {@link #pruneObsoleteKeys(String, String...)}.
     *
     * @param protectedSections Paths whose contents are user-managed.
     * @return The number of keys removed.
     */
    public final int pruneObsoleteConfigKeys(String... protectedSections) {
        return support().pruneObsoleteConfigKeys(protectedSections);
    }

    // -------------------------------------------------------------------------
    // Language
    // -------------------------------------------------------------------------

    /**
     * Resolves which language this plugin should speak: its own {@code language} key when set,
     * XCore's otherwise.
     *
     * @return The language code, lower-cased (e.g. {@code "fr"}).
     */
    public final String getLanguage() {
        return support().getLanguage();
    }

    /**
     * Copies a YAML file aside when it cannot be parsed, and says so, so that a merge with the
     * defaults never writes over a file that only had a typo.
     *
     * @param file The file about to be merged with its defaults.
     * @return {@code true} when the file could not be read and a copy was made.
     */
    protected final boolean setAsideIfUnreadable(File file) {
        return support().setAsideIfUnreadable(file);
    }

    /**
     * Loads this plugin's language file, honouring the resolved language. Every bundled language
     * is extracted to {@code lang/} on first run; the one matching {@link #getLanguage()} is loaded,
     * falling back to the first given.
     *
     * @param available The language codes bundled in the jar, English first.
     */
    public final void loadLanguage(String... available) {
        support().loadLanguage(available);
    }

    // -------------------------------------------------------------------------
    // What the registry sees
    // -------------------------------------------------------------------------

    /** This plugin as an XCore extension. */
    private final class View implements XExtension {

        @Override public String name() { return getName(); }
        @Override public String version() { return getPluginMeta().getVersion(); }
        @Override public String author() { return descriptor == null ? "Unknown" : descriptor.getAuthor(); }
        @Override public String description() { return descriptor == null ? "" : descriptor.getDescription(); }
        @Override public Kind kind() { return Kind.PLUGIN; }

        @Override
        public AddonState state() {
            AddonState current = state;
            return current == null ? AddonState.LOADED : current;
        }

        @Override public File dataFolder() { return getDataFolder(); }
        @Override public Updater updater() { return XPlugin.this.updater(); }
        @Override public Plugin plugin() { return XPlugin.this; }
        @Override public void reload() { XPlugin.this.reload(); }
    }
}
