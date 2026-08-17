package fr.xyness.XCore.Commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Addon.AddonManager;
import fr.xyness.XCore.Addon.AddonState;
import fr.xyness.XCore.Addon.XAddon;
import fr.xyness.XCore.Cache.PlayerCache;
import fr.xyness.XCore.Models.PlayerData;
import fr.xyness.XCore.Utils.LangManager;
import fr.xyness.XCore.Web.WebModule;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Registers the {@code /xcore} command using Paper's Brigadier lifecycle API.
 *
 * <h2>Subcommands</h2>
 * <ul>
 *   <li>{@code /xcore} — overview panel: version, uptime, database, addons, sync, dashboard</li>
 *   <li>{@code /xcore stats} — cache, API and circuit-breaker counters</li>
 *   <li>{@code /xcore addons} — every addon with its version and state</li>
 *   <li>{@code /xcore dashboard} — web dashboard address and one-click login link</li>
 *   <li>{@code /xcore reload} — reload the core and every addon</li>
 *   <li>{@code /xcore reload <addon>} — reload one addon</li>
 *   <li>{@code /xcore clear-cache} — empty every cache region</li>
 *   <li>{@code /xcore player <name>} — look up a player</li>
 * </ul>
 *
 * <p>Every line comes from {@code lang/<code>.yml}, including the colours, the hover text and the
 * click actions, so the whole panel can be restyled without touching this class.</p>
 */
public class XCoreCommand {

    /** Settings that are read once at startup and cannot be re-applied by a reload. */
    private static final String RESTART_ONLY_HINT = "reload-restart-required";

    private final XCore core;

    /**
     * Creates a new XCoreCommand.
     *
     * @param core The XCore plugin instance.
     */
    public XCoreCommand(XCore core) {
        this.core = core;
    }

    /** Suggests online player names. */
    private final SuggestionProvider<CommandSourceStack> playerSuggestions = (ctx, builder) -> {
        String input = builder.getRemainingLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(input)) {
                builder.suggest(p.getName());
            }
        }
        return builder.buildFuture();
    };

    /** Suggests loaded addon names. */
    private SuggestionProvider<CommandSourceStack> addonSuggestions() {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            for (String name : core.getAddonManager().getAddons().keySet()) {
                if (name.toLowerCase().startsWith(input)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    /**
     * Registers the command with Paper's Brigadier lifecycle API.
     */
    public void register() {
        core.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(
                Commands.literal("xcore")
                    .requires(src -> src.getSender().hasPermission("xcore.admin"))
                    .executes(ctx -> { sendOverview(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                    .then(Commands.literal("stats")
                        .executes(ctx -> { handleStats(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .then(Commands.literal("addons")
                        .executes(ctx -> { handleAddons(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .then(Commands.literal("dashboard")
                        .executes(ctx -> { handleDashboard(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                        .then(Commands.literal("revoke")
                            .executes(ctx -> { handleDashboardRevoke(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })))
                    .then(Commands.literal("reload")
                        .executes(ctx -> { handleReload(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                        .then(Commands.argument("addon", StringArgumentType.word())
                            .suggests(addonSuggestions())
                            .executes(ctx -> { handleReloadAddon(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "addon")); return Command.SINGLE_SUCCESS; })
                        ))
                    .then(Commands.literal("clear-cache")
                        .executes(ctx -> { handleClearCache(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .then(Commands.literal("profile")
                        .executes(ctx -> { handleProfile(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                        .then(Commands.literal("on")
                            .executes(ctx -> { handleProfileToggle(ctx.getSource().getSender(), true); return Command.SINGLE_SUCCESS; }))
                        .then(Commands.literal("off")
                            .executes(ctx -> { handleProfileToggle(ctx.getSource().getSender(), false); return Command.SINGLE_SUCCESS; }))
                        .then(Commands.literal("reset")
                            .executes(ctx -> { handleProfileReset(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })))
                    .then(Commands.literal("diag")
                        .executes(ctx -> { handleDiag(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .then(Commands.literal("player")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(playerSuggestions)
                            .executes(ctx -> { handlePlayer(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name")); return Command.SINGLE_SUCCESS; })
                        ))
                    .build(),
                "XCore admin commands"
            );
        });
    }

    private LangManager lang() { return core.langManager(); }

    private void send(CommandSender to, String key, String... replacements) {
        to.sendMessage(lang().getComponent(key, replacements));
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    /**
     * Groups thousands with a space — readable in every language this plugin ships, and never
     * ambiguous the way a comma or a dot would be.
     */
    private String number(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(' ');
            out.append(digits.charAt(i));
        }
        return (value < 0 ? "-" : "") + out;
    }

    /** Renders a duration with the two largest units that carry information. */
    private String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;

        String d = lang().getMessageString("unit-day");
        String h = lang().getMessageString("unit-hour");
        String m = lang().getMessageString("unit-minute");
        String s = lang().getMessageString("unit-second");

        if (days > 0) return days + d + " " + hours + h;
        if (hours > 0) return hours + h + " " + minutes + m;
        if (minutes > 0) return minutes + m;
        return seconds + s;
    }

    /** The translated label for an addon state. */
    private String stateLabel(AddonState state) {
        return lang().getMessageString(switch (state) {
            case ENABLED -> "addon-state-enabled";
            case LOADED, ENABLING -> "addon-state-loading";
            case DISABLING, DISABLED -> "addon-state-disabled";
            case ERRORED -> "addon-state-errored";
        });
    }

    /** The on/off label used by every status row. */
    private String onOff(boolean on) {
        return lang().getMessageString(on ? "status-on" : "status-off");
    }

    /**
     * Escapes a value that will be interpolated inside a MiniMessage tag argument.
     * A stray quote would silently swallow the rest of the line.
     */
    private String tagSafe(String value) {
        return value == null ? "" : value.replace("'", "").replace("\"", "");
    }

    // -------------------------------------------------------------------------
    // /xcore
    // -------------------------------------------------------------------------

    private void sendOverview(CommandSender sender) {
        AddonManager am = core.getAddonManager();
        Map<String, XAddon> addons = am.getAddons();
        long enabled = addons.keySet().stream().filter(n -> am.getState(n) == AddonState.ENABLED).count();

        send(sender, "bar");
        send(sender, "info-header", "version", core.getPluginMeta().getVersion());
        send(sender, "info-subtitle");
        send(sender, "blank");

        send(sender, "info-server",
            "name", core.getServerName(),
            "software", Bukkit.getVersion());
        send(sender, "info-uptime",
            "value", duration(System.currentTimeMillis() - core.getStartTimeMillis()),
            "players", String.valueOf(Bukkit.getOnlinePlayers().size()));
        send(sender, "info-database",
            "type", core.getDatabaseType().name(),
            "reads", number(core.playerCache().getDbReads()),
            "writes", number(core.playerCache().getDbWrites()));
        send(sender, "info-addons",
            "enabled", String.valueOf(enabled),
            "total", String.valueOf(addons.size()),
            "list", addonHoverList(am, addons));
        send(sender, "info-sync",
            "status", onOff(core.getSyncManager() != null && core.getSyncManager().isRunning()),
            "backend", lang().getMessageString(core.getJedisPool() != null ? "sync-backend-redis" : "sync-backend-database"));

        if (core.getWebPanel() != null) {
            send(sender, "info-dashboard", "url", tagSafe(dashboardUrl()));
        } else {
            send(sender, "info-dashboard-off");
        }

        send(sender, "blank");
        send(sender, "info-commands");
        for (String key : new String[]{"cmd-stats", "cmd-addons", "cmd-player",
                                       "cmd-dashboard", "cmd-profile", "cmd-diag",
                                       "cmd-reload", "cmd-clear-cache"}) {
            send(sender, key);
        }
        send(sender, "bar");
    }

    /** The addon roster shown when hovering the addon counter. */
    private String addonHoverList(AddonManager am, Map<String, XAddon> addons) {
        if (addons.isEmpty()) return tagSafe(lang().getMessageString("info-addons-none"));
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, XAddon> entry : addons.entrySet()) {
            rows.add(lang().getMessage("info-addons-entry",
                "name", entry.getKey(),
                "version", entry.getValue().getDescriptor().getVersion(),
                "state", stateLabel(am.getState(entry.getKey()))));
        }
        return tagSafe(String.join("<newline>", rows));
    }

    // -------------------------------------------------------------------------
    // /xcore profile
    // -------------------------------------------------------------------------

    /**
     * Prints the heaviest event handlers, addon by addon.
     *
     * <p>Every addon runs under XCore's plugin identity, so a timings report blames XCore for
     * everything they do. This is the only place that can say which addon actually spent the time.</p>
     */
    private void handleProfile(CommandSender sender) {
        fr.xyness.XCore.Utils.Profiler profiler = core.profiler();

        send(sender, "bar");
        send(sender, "profile-header");
        send(sender, "blank");

        if (!fr.xyness.XCore.Utils.Profiler.isEnabled()) {
            send(sender, "profile-disabled");
            send(sender, "bar");
            return;
        }

        List<fr.xyness.XCore.Utils.Profiler.Entry> top = profiler.top(12);
        if (top.isEmpty()) {
            send(sender, "profile-empty");
            send(sender, "bar");
            return;
        }

        long window = profiler.windowSeconds();
        send(sender, "profile-window",
            "seconds", number(window),
            "keys", String.valueOf(profiler.size()));
        send(sender, "blank");

        for (fr.xyness.XCore.Utils.Profiler.Entry entry : top) {
            send(sender, "profile-entry",
                "key", entry.key(),
                "calls", number(entry.calls()),
                "total", String.format(java.util.Locale.US, "%.1f", entry.totalMillis()),
                "avg", String.format(java.util.Locale.US, "%.1f", entry.avgMicros()),
                "max", String.format(java.util.Locale.US, "%.1f", entry.maxMicros()),
                "per_second", String.format(java.util.Locale.US, "%.2f", entry.totalMillis() / window));
        }

        send(sender, "blank");
        send(sender, "profile-hint");
        send(sender, "bar");
    }

    /** Switches sampling on or off without a restart. */
    private void handleProfileToggle(CommandSender sender, boolean on) {
        core.profiler().setEnabled(on);
        send(sender, on ? "profile-turned-on" : "profile-turned-off");
    }

    /** Starts a fresh measurement window. */
    private void handleProfileReset(CommandSender sender) {
        core.profiler().reset();
        send(sender, "profile-reset");
    }

    // -------------------------------------------------------------------------
    // /xcore diag
    // -------------------------------------------------------------------------

    /**
     * Writes everything a support request needs into one file, and says where it is.
     *
     * <p>Versions, platform, database, cache and pool state, addon roster, the settings that shape
     * behaviour — the questions that otherwise take four round trips to ask.</p>
     */
    private void handleDiag(CommandSender sender) {
        AddonManager am = core.getAddonManager();
        StringBuilder out = new StringBuilder();
        String stamp = java.time.LocalDateTime.now().format(XCore.FORMATTER);

        out.append("XCore diagnostic — ").append(stamp).append('\n');
        out.append("========================================\n\n");

        out.append("[Platform]\n");
        out.append("  XCore        : ").append(core.getPluginMeta().getVersion()).append('\n');
        out.append("  Server       : ").append(Bukkit.getVersion()).append('\n');
        out.append("  Bukkit       : ").append(Bukkit.getBukkitVersion()).append('\n');
        out.append("  Java         : ").append(Runtime.version()).append('\n');
        out.append("  OS           : ").append(System.getProperty("os.name")).append(' ')
           .append(System.getProperty("os.arch")).append('\n');
        out.append("  CPUs         : ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        Runtime runtime = Runtime.getRuntime();
        out.append("  Memory       : ").append(fr.xyness.XCore.Utils.Formats.bytes(runtime.totalMemory() - runtime.freeMemory()))
           .append(" used / ").append(fr.xyness.XCore.Utils.Formats.bytes(runtime.maxMemory())).append(" max\n");
        out.append("  Uptime       : ").append(duration(System.currentTimeMillis() - core.getStartTimeMillis())).append('\n');
        out.append("  Online       : ").append(Bukkit.getOnlinePlayers().size()).append('\n');
        out.append("  Worlds       : ").append(Bukkit.getWorlds().size()).append('\n');
        out.append('\n');

        out.append("[Storage]\n");
        out.append("  Database     : ").append(core.getDatabaseType().name()).append('\n');
        out.append("  Connections  : ").append(fr.xyness.XCore.Database.GuardedDataSource.getBorrowCount())
           .append(" borrowed, ").append(fr.xyness.XCore.Database.GuardedDataSource.getTickThreadBorrowCount())
           .append(" of them from a tick thread\n");
        out.append("  Pool         : ").append(core.getDataSource().getMaximumPoolSize()).append(" max\n");
        out.append("  Redis        : ").append(core.getJedisPool() != null ? "connected" : "off").append('\n');
        out.append("  Sync         : ").append(core.getSyncManager() != null && core.getSyncManager().isRunning()
                ? "running (" + (core.getJedisPool() != null ? "redis" : "database") + ")" : "off").append('\n');
        out.append("  Server name  : ").append(core.getServerName()).append('\n');
        out.append('\n');

        out.append("[Caches]\n");
        PlayerCache<PlayerData> cache = core.playerCache();
        out.append("  L1 players   : ").append(cache.getL1Size()).append(" entries, ")
           .append(String.format(java.util.Locale.US, "%.1f", cache.getL1HitRate() * 100)).append("% hit rate\n");
        out.append("  DB reads     : ").append(cache.getDbReads()).append('\n');
        out.append("  Buffered     : ").append(core.playerDAO().writeBuffer().getColumnsWritten())
           .append(" column writes in ").append(core.playerDAO().writeBuffer().getStatementCount())
           .append(" statements, ").append(core.playerDAO().writeBuffer().pendingPlayers())
           .append(" player(s) waiting\n");
        out.append("  Rejections   : ").append(fr.xyness.XCore.Utils.RejectedTaskPolicy.getRejectionCount())
           .append(" (").append(fr.xyness.XCore.Utils.RejectedTaskPolicy.getTickRescueCount())
           .append(" kept off a tick thread)\n");
        out.append("  Mojang API   : ").append(cache.getApiCalls()).append(" calls, ")
           .append(cache.getApiFailures()).append(" failures, ")
           .append(cache.getApiRateLimits()).append(" rate limits\n");
        out.append("  Breaker      : ").append(cache.getCircuitBreaker().getState()).append('\n');
        out.append('\n');

        if (core.network() != null) {
            out.append("[Network]\n");
            for (fr.xyness.XCore.Network.ServerInfo info : core.network().servers()) {
                out.append("  ").append(String.format("%-18s", info.name()))
                   .append(info.online()).append('/').append(info.maximum()).append(" players, ")
                   .append(String.format(java.util.Locale.US, "%.1f", info.tps())).append(" TPS")
                   .append(info.local() ? "  (this server)" : "")
                   .append('\n');
            }
            out.append('\n');
        }

        out.append("[Addons]\n");
        Map<String, XAddon> addons = am.getAddons();
        if (addons.isEmpty()) {
            out.append("  (none)\n");
        } else {
            for (Map.Entry<String, XAddon> entry : addons.entrySet()) {
                out.append("  ").append(String.format("%-18s", entry.getKey()))
                   .append(" v").append(entry.getValue().getDescriptor().getVersion())
                   .append("  ").append(am.getState(entry.getKey()))
                   .append("  listeners=").append(core.getListenerRegistry().getListenerCount(entry.getKey()))
                   .append('\n');
            }
        }
        out.append('\n');

        out.append("[Core settings]\n");
        for (String key : new String[]{"language", "debug", "profiling", "database-type", "cross-server.enabled",
                "cross-server.redis.enabled", "web-dashboard.enabled", "economy.enabled",
                "cache.max-size", "cache.ttl-minutes"}) {
            out.append("  ").append(String.format("%-28s", key)).append(" = ")
               .append(core.getConfig().get(key)).append('\n');
        }
        out.append('\n');

        out.append("[Profiler]\n");
        if (!fr.xyness.XCore.Utils.Profiler.isEnabled()) {
            out.append("  off — enable with /xcore profile on\n");
        } else {
            for (fr.xyness.XCore.Utils.Profiler.Entry entry : core.profiler().top(25)) {
                out.append("  ").append(String.format("%-52s", entry.key()))
                   .append(String.format(java.util.Locale.US, " %8d calls  %9.1f ms total  %7.1f µs avg  %8.1f µs max",
                           entry.calls(), entry.totalMillis(), entry.avgMicros(), entry.maxMicros()))
                   .append('\n');
            }
        }

        java.io.File file = new java.io.File(core.getDataFolder(),
                "diagnostic-" + stamp.replace(':', '-').replace(' ', '_') + ".txt");

        // The rest reads the database, so it happens off the server thread — a support report is
        // not worth a stall, and the guarded pool would rightly complain about it.
        core.schedulerAdapter().runAsyncTask(() -> {
            out.append("\n[Players table]\n");
            appendColumnWidths(out);

            try {
                java.nio.file.Files.writeString(file.toPath(), out.toString(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                core.schedulerAdapter().runGlobalTask(() -> send(sender, "diag-failed", "error", e.getMessage()));
                return;
            }

            core.schedulerAdapter().runGlobalTask(() -> {
                send(sender, "bar");
                send(sender, "diag-header");
                send(sender, "blank");
                send(sender, "diag-platform",
                    "version", core.getPluginMeta().getVersion(),
                    "software", Bukkit.getVersion(),
                    "java", Runtime.version().toString());
                send(sender, "diag-storage",
                    "type", core.getDatabaseType().name(),
                    "queries", number(fr.xyness.XCore.Database.GuardedDataSource.getBorrowCount()),
                    "blocking", number(fr.xyness.XCore.Database.GuardedDataSource.getTickThreadBorrowCount()));
                send(sender, "diag-addons",
                    "count", String.valueOf(addons.size()),
                    "enabled", String.valueOf(addons.keySet().stream()
                            .filter(n -> am.getState(n) == AddonState.ENABLED).count()));
                send(sender, "blank");
                send(sender, "diag-written", "file", file.getName());
                send(sender, "bar");
            });
        });
    }

    /**
     * Measures how wide each column of {@code players} really is, and says which ones are worth
     * moving out.
     *
     * <p>Every addon adds its columns to this one table, and the whole row is read and written back
     * to Redis on every change, for every addon. A column holding a serialised list is therefore
     * paid for by everyone. The rule is written down; nothing measured it until now.</p>
     *
     * @param out The report being built.
     */
    private void appendColumnWidths(StringBuilder out) {
        java.util.Map<String, long[]> widths = new java.util.LinkedHashMap<>();
        int rows = 0;

        try (java.sql.Connection conn = core.getDataSource().getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT * FROM players LIMIT 500");
             java.sql.ResultSet rs = ps.executeQuery()) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            int count = meta.getColumnCount();
            while (rs.next()) {
                rows++;
                for (int i = 1; i <= count; i++) {
                    Object value = rs.getObject(i);
                    int length = value == null ? 0 : String.valueOf(value).length();
                    long[] entry = widths.computeIfAbsent(meta.getColumnName(i), k -> new long[2]);
                    entry[0] += length;
                    entry[1] = Math.max(entry[1], length);
                }
            }
        } catch (java.sql.SQLException e) {
            out.append("  (could not be measured : ").append(e.getMessage()).append(")\n");
            return;
        }

        if (rows == 0) {
            out.append("  (no rows yet)\n");
            return;
        }

        out.append("  ").append(widths.size()).append(" columns, measured over ").append(rows).append(" row(s)\n");
        long total = 0;
        java.util.List<String> heavy = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, long[]> entry : widths.entrySet()) {
            long average = entry.getValue()[0] / rows;
            total += average;
            out.append("  ").append(String.format("%-28s", entry.getKey()))
               .append(String.format("%5d avg  %6d max%n", average, entry.getValue()[1]));
            if (average > 128) heavy.add(entry.getKey());
        }
        out.append("  ").append(String.format("%-28s", "row total")).append(String.format("%5d avg%n", total));
        if (!heavy.isEmpty()) {
            out.append("  Wide columns, worth moving to their own table : ")
               .append(String.join(", ", heavy)).append('\n');
        }
    }

    // -------------------------------------------------------------------------
    // /xcore stats
    // -------------------------------------------------------------------------

    private void handleStats(CommandSender sender) {
        PlayerCache<PlayerData> cache = core.playerCache();

        send(sender, "bar");
        send(sender, "stats-header");
        send(sender, "blank");
        send(sender, "stats-database",
            "type", core.getDatabaseType().name(),
            "reads", number(cache.getDbReads()),
            "writes", number(cache.getDbWrites()));
        send(sender, "stats-l1-cache",
            "hit_rate", String.format("%.1f", cache.getL1HitRate() * 100),
            "size", number(cache.getL1Size()));
        send(sender, "stats-redis", "status", onOff(core.getJedisPool() != null));
        send(sender, "stats-cross-server",
            "status", onOff(core.getSyncManager() != null && core.getSyncManager().isRunning()));
        send(sender, "blank");
        send(sender, "stats-mojang-api",
            "calls", number(cache.getApiCalls()),
            "failures", number(cache.getApiFailures()),
            "rate_limits", number(cache.getApiRateLimits()));
        send(sender, "stats-mojang-cache",
            "hits", number(cache.getMojangCacheHits()),
            "misses", number(cache.getMojangCacheMisses()));
        send(sender, "stats-skin-cache",
            "hits", number(cache.getSkinCacheHits()),
            "misses", number(cache.getSkinCacheMisses()));
        send(sender, "stats-circuit-breaker",
            "state", cache.getCircuitBreaker().getState().name());
        send(sender, "bar");
    }

    // -------------------------------------------------------------------------
    // /xcore addons
    // -------------------------------------------------------------------------

    private void handleAddons(CommandSender sender) {
        AddonManager am = core.getAddonManager();
        Map<String, XAddon> addons = am.getAddons();

        send(sender, "bar");
        send(sender, "addons-header", "count", String.valueOf(addons.size()));
        send(sender, "blank");

        if (addons.isEmpty()) {
            send(sender, "addons-empty");
        } else {
            for (Map.Entry<String, XAddon> entry : addons.entrySet()) {
                String name = entry.getKey();
                send(sender, "addons-entry",
                    "name", name,
                    "version", entry.getValue().getDescriptor().getVersion(),
                    "state", stateLabel(am.getState(name)));
            }
        }
        send(sender, "bar");
    }

    // -------------------------------------------------------------------------
    // /xcore dashboard
    // -------------------------------------------------------------------------

    /**
     * The address the dashboard is reachable at.
     *
     * <p>{@code web-dashboard.public-url} wins when set, because only the administrator knows the
     * hostname behind a reverse proxy. Otherwise the bind address is used, and {@code localhost}
     * when the server binds every interface.</p>
     */
    private String dashboardUrl() {
        String configured = core.getConfig().getString("web-dashboard.public-url", "");
        if (configured != null && !configured.isBlank()) {
            String url = configured.trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            // A scheme is not optional: the client refuses to open a link without one, and the
            // administrator writing "panel.example.com" is the likely case.
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
            return url;
        }
        String host = Bukkit.getIp();
        if (host == null || host.isBlank() || "0.0.0.0".equals(host)) host = "localhost";
        return "http://" + host + ":" + core.getConfig().getInt("web-dashboard.port", 8085);
    }

    private void handleDashboard(CommandSender sender) {
        send(sender, "bar");
        send(sender, "dashboard-header");
        send(sender, "blank");

        if (core.getWebPanel() == null) {
            boolean enabledInConfig = core.getConfig().getBoolean("web-dashboard.enabled", false);
            send(sender, "dashboard-status", "status", onOff(false));
            send(sender, enabledInConfig ? "dashboard-failed" : "dashboard-disabled");
            send(sender, "bar");
            return;
        }

        String url = tagSafe(dashboardUrl());
        List<WebModule> modules = core.getWebPanel().getModules();
        List<String> names = new ArrayList<>();
        for (WebModule m : modules) names.add(m.getName());

        send(sender, "dashboard-status", "status", onOff(true));
        send(sender, "dashboard-address", "url", url);
        send(sender, "dashboard-modules",
            "count", String.valueOf(modules.size()),
            "list", tagSafe(names.isEmpty() ? lang().getMessageString("dashboard-modules-none")
                                            : String.join("<newline>", names)));
        send(sender, "blank");

        // A player gets a link that logs them in; nothing has to be configured, and the token is
        // never displayed — it travels inside the URL and the page removes it on arrival.
        if (sender instanceof Player player) {
            String session = core.getWebPanel().createSession(player.getUniqueId(), player.getName());
            long ttl = core.getConfig().getLong("web-dashboard.session-ttl-hours", 24);
            send(sender, "dashboard-login", "url", tagSafe(url + "/?token=" + session));
            send(sender, ttl > 0 ? "dashboard-login-ttl" : "dashboard-login-forever",
                "hours", String.valueOf(ttl));
            send(sender, "dashboard-login-revoke");
        } else {
            // The console has no browser to click into, and echoing a token into the log is the
            // one place it should never end up.
            send(sender, "dashboard-login-console");
        }
        send(sender, "bar");
    }

    /** Closes every dashboard session the caller opened, in case a link went astray. */
    private void handleDashboardRevoke(CommandSender sender) {
        if (core.getWebPanel() == null) {
            send(sender, "dashboard-status", "status", onOff(false));
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "dashboard-login-console");
            return;
        }
        int closed = core.getWebPanel().revokeSessions(player.getUniqueId());
        send(sender, closed > 0 ? "dashboard-revoked" : "dashboard-revoked-none",
            "count", String.valueOf(closed));
    }

    // -------------------------------------------------------------------------
    // /xcore reload
    // -------------------------------------------------------------------------

    /**
     * Reloads everything that can be reloaded without a restart: the core configuration, all
     * language files, the dashboard strings, the economy definitions, and every enabled addon.
     *
     * <p>What it deliberately does not touch is reported to the sender rather than left implicit —
     * the database pool, Redis, the cache sizes and the dashboard socket are built once at startup
     * from values that cannot change under a running server.</p>
     */
    private void handleReload(CommandSender sender) {
        core.reloadConfig();
        core.langManager().reload();
        core.logger().setDebug(core.getConfig().getBoolean("debug", false));

        // The dashboard serves its own strings to the browser; a language change has to reach it
        // too, otherwise the setting looks like it needs a restart.
        if (core.getWebPanel() != null) core.getWebPanel().reloadStrings();
        if (core.getCoinsManager() != null) core.getCoinsManager().reload();

        AddonManager am = core.getAddonManager();
        List<String> reloaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String name : new ArrayList<>(am.getAddons().keySet())) {
            if (am.getState(name) != AddonState.ENABLED) continue;
            try {
                am.reloadAddon(name);
                reloaded.add(name);
            } catch (Throwable t) {
                failed.add(name);
                core.logger().sendError("Error reloading addon '" + name + "': " + t.getMessage());
            }
        }

        send(sender, "reload-core");
        if (!reloaded.isEmpty()) {
            send(sender, "reload-addons",
                "count", String.valueOf(reloaded.size()),
                "list", tagSafe(String.join(", ", reloaded)));
        } else {
            send(sender, "reload-addons-none");
        }
        if (!failed.isEmpty()) {
            send(sender, "reload-addons-failed", "list", tagSafe(String.join(", ", failed)));
        }
        send(sender, RESTART_ONLY_HINT);
    }

    private void handleReloadAddon(CommandSender sender, String addonName) {
        AddonManager am = core.getAddonManager();
        if (am.getAddon(addonName).isEmpty()) {
            send(sender, "addon-not-found", "name", addonName);
            return;
        }
        am.reloadAddon(addonName);
        send(sender, "reload-addon-success", "name", addonName);
    }

    // -------------------------------------------------------------------------
    // /xcore clear-cache
    // -------------------------------------------------------------------------

    private void handleClearCache(CommandSender sender) {
        core.playerCache().clearAll();
        fr.xyness.XCore.Gui.GuiUtils.clearHeadCache();
        send(sender, "clear-cache-success");
    }

    // -------------------------------------------------------------------------
    // /xcore player <name>
    // -------------------------------------------------------------------------

    private void handlePlayer(CommandSender sender, String name) {
        send(sender, "player-loading", "name", name);

        core.playerCache().getPlayer(name).thenAccept(opt -> {
            if (opt.isEmpty()) {
                send(sender, "player-not-found", "name", name);
                return;
            }
            PlayerData data = opt.get();
            boolean online = Bukkit.getPlayerExact(data.getName()) != null;

            send(sender, "bar");
            send(sender, "player-header", "name", data.getName(),
                "status", lang().getMessageString(online ? "player-online" : "player-offline"));
            send(sender, "blank");
            send(sender, "player-server-uuid", "value", data.getUuid().toString());
            send(sender, "player-mojang-uuid", "value", nonNull(data.getMojangUUID()));
            send(sender, "player-texture", "value", nonNull(data.getTexture()));
            send(sender, "player-last-login", "value", nonNull(data.getTargetData("last_login")));
            send(sender, "player-last-logout", "value", nonNull(data.getTargetData("last_logout")));

            Map<String, Object> custom = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : data.getData().entrySet()) {
                if (!"last_login".equals(entry.getKey()) && !"last_logout".equals(entry.getKey())) {
                    custom.put(entry.getKey(), entry.getValue());
                }
            }
            if (!custom.isEmpty()) {
                send(sender, "blank");
                send(sender, "player-data-header", "count", String.valueOf(custom.size()));
                for (Map.Entry<String, Object> entry : custom.entrySet()) {
                    send(sender, "player-data-entry",
                        "key", entry.getKey(),
                        "value", String.valueOf(entry.getValue()));
                }
            }
            send(sender, "bar");
        });
    }

    /** Renders a missing value as the translated placeholder rather than {@code null}. */
    private String nonNull(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() || "null".equals(text) ? lang().getMessageString("value-none") : text;
    }
}
