package fr.xyness.XCore.Commands;

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
import fr.xyness.XCore.Sync.SyncManager;
import fr.xyness.XCore.Utils.LangManager;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Registers the {@code /xcore} command using Paper's Brigadier lifecycle API.
 * <p>
 * Subcommands:
 * <ul>
 *   <li>{@code /xcore} - Show info (version, addons count, database type)</li>
 *   <li>{@code /xcore stats} - Cache stats, DB stats</li>
 *   <li>{@code /xcore addons} - List all addons with state</li>
 *   <li>{@code /xcore reload} - Reload core config + lang</li>
 *   <li>{@code /xcore reload <addon>} - Reload specific addon</li>
 *   <li>{@code /xcore clear-cache} - Clear all cache regions</li>
 *   <li>{@code /xcore player <name>} - Player info</li>
 * </ul>
 * </p>
 */
@SuppressWarnings("UnstableApiUsage")
public class XCoreCommand {

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
                    .executes(ctx -> { sendInfo(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                    .then(Commands.literal("stats")
                        .executes(ctx -> { handleStats(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .then(Commands.literal("addons")
                        .executes(ctx -> { handleAddons(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .then(Commands.literal("reload")
                        .executes(ctx -> { handleReload(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                        .then(Commands.argument("addon", StringArgumentType.word())
                            .suggests(addonSuggestions())
                            .executes(ctx -> { handleReloadAddon(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "addon")); return Command.SINGLE_SUCCESS; })
                        ))
                    .then(Commands.literal("clear-cache")
                        .executes(ctx -> { handleClearCache(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
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

    // -------------------------------------------------------------------------
    // /xcore (info)
    // -------------------------------------------------------------------------

    private void sendInfo(CommandSender sender) {
        AddonManager am = core.getAddonManager();
        int addonCount = am.getAddons().size();
        long enabledCount = am.getAddons().entrySet().stream()
                .filter(e -> am.getState(e.getKey()) == AddonState.ENABLED)
                .count();

        sender.sendMessage(lang().getComponent("stats-title"));
        sender.sendMessage(lang().getComponent("stats-database",
            "type", core.getDatabaseType().name(),
            "reads", String.valueOf(core.playerCache().getDbReads()),
            "writes", String.valueOf(core.playerCache().getDbWrites())));
        sender.sendMessage(lang().getComponent("stats-end"));

        sender.sendMessage(lang().getComponent("usage"));
    }

    // -------------------------------------------------------------------------
    // /xcore stats
    // -------------------------------------------------------------------------

    private void handleStats(CommandSender sender) {
        PlayerCache<PlayerData> cache = core.playerCache();
        sender.sendMessage(lang().getComponent("stats-title"));
        sender.sendMessage(lang().getComponent("stats-database",
            "type", core.getDatabaseType().name(),
            "reads", String.valueOf(cache.getDbReads()),
            "writes", String.valueOf(cache.getDbWrites())));
        sender.sendMessage(lang().getComponent("stats-l1-cache",
            "hit_rate", String.format("%.1f%%", cache.getL1HitRate() * 100),
            "size", String.valueOf(cache.getL1Size())));
        sender.sendMessage(lang().getComponent("stats-redis",
            "status", core.getJedisPool() != null ? "<green>enabled" : "<red>disabled"));
        sender.sendMessage(lang().getComponent("stats-cross-server",
            "status", core.getSyncManager() != null && core.getSyncManager().isRunning() ? "<green>active" : "<red>disabled"));
        sender.sendMessage(lang().getComponent("stats-mojang-api",
            "calls", String.valueOf(cache.getApiCalls()),
            "failures", String.valueOf(cache.getApiFailures()),
            "rate_limits", String.valueOf(cache.getApiRateLimits())));
        sender.sendMessage(lang().getComponent("stats-mojang-cache",
            "hits", String.valueOf(cache.getMojangCacheHits()),
            "misses", String.valueOf(cache.getMojangCacheMisses())));
        sender.sendMessage(lang().getComponent("stats-skin-cache",
            "hits", String.valueOf(cache.getSkinCacheHits()),
            "misses", String.valueOf(cache.getSkinCacheMisses())));
        sender.sendMessage(lang().getComponent("stats-circuit-breaker",
            "state", cache.getCircuitBreaker().getState().name()));
        sender.sendMessage(lang().getComponent("stats-end"));
    }

    // -------------------------------------------------------------------------
    // /xcore addons
    // -------------------------------------------------------------------------

    private void handleAddons(CommandSender sender) {
        AddonManager am = core.getAddonManager();
        Map<String, XAddon> addons = am.getAddons();

        sender.sendMessage(lang().getComponent("stats-title"));
        if (addons.isEmpty()) {
            sender.sendMessage(lang().getComponent("clear-cache-success")
                    .replaceText(b -> b.matchLiteral("All caches cleared.").replacement("No addons loaded.")));
        } else {
            for (Map.Entry<String, XAddon> entry : addons.entrySet()) {
                String name = entry.getKey();
                XAddon addon = entry.getValue();
                AddonState state = am.getState(name);
                String stateColor = switch (state) {
                    case ENABLED -> "<green>";
                    case LOADED, ENABLING -> "<yellow>";
                    case ERRORED -> "<red>";
                    default -> "<gray>";
                };
                sender.sendMessage(lang().getComponent("player-data-entry",
                    "key", name + " v" + addon.getDescriptor().getVersion(),
                    "value", stateColor + state.name()));
            }
        }
        sender.sendMessage(lang().getComponent("stats-end"));
    }

    // -------------------------------------------------------------------------
    // /xcore reload
    // -------------------------------------------------------------------------

    private void handleReload(CommandSender sender) {
        core.reloadConfig();
        core.langManager().reload();
        core.logger().setDebug(core.getConfig().getBoolean("debug", false));
        sender.sendMessage(lang().getComponent("reload-success"));
    }

    // -------------------------------------------------------------------------
    // /xcore reload <addon>
    // -------------------------------------------------------------------------

    private void handleReloadAddon(CommandSender sender, String addonName) {
        AddonManager am = core.getAddonManager();
        if (am.getAddon(addonName).isEmpty()) {
            sender.sendMessage(lang().getComponent("player-not-found", "name", addonName));
            return;
        }
        am.reloadAddon(addonName);
        sender.sendMessage(lang().getComponent("reload-success"));
    }

    // -------------------------------------------------------------------------
    // /xcore clear-cache
    // -------------------------------------------------------------------------

    private void handleClearCache(CommandSender sender) {
        core.playerCache().clearAll();
        core.getCacheManager().shutdown();
        sender.sendMessage(lang().getComponent("clear-cache-success"));
    }

    // -------------------------------------------------------------------------
    // /xcore player <name>
    // -------------------------------------------------------------------------

    private void handlePlayer(CommandSender sender, String name) {
        sender.sendMessage(lang().getComponent("player-loading", "name", name));

        core.playerCache().getPlayer(name).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sender.sendMessage(lang().getComponent("player-not-found", "name", name));
                return;
            }
            PlayerData data = opt.get();
            boolean online = Bukkit.getPlayerExact(data.getName()) != null;
            sender.sendMessage(lang().getComponent("player-title"));
            sender.sendMessage(lang().getComponent("player-name", "value", data.getName()));
            sender.sendMessage(lang().getComponent("player-status", "value", online ? "<green>Online" : "<red>Offline"));
            sender.sendMessage(lang().getComponent("player-server-uuid", "value", data.getUuid().toString()));
            sender.sendMessage(lang().getComponent("player-mojang-uuid", "value", data.getMojangUUID()));
            sender.sendMessage(lang().getComponent("player-texture", "value", data.getTexture()));

            Object lastLogin = data.getTargetData("last_login");
            Object lastLogout = data.getTargetData("last_logout");
            sender.sendMessage(lang().getComponent("player-last-login", "value", lastLogin != null ? lastLogin.toString() : "N/A"));
            sender.sendMessage(lang().getComponent("player-last-logout", "value", lastLogout != null ? lastLogout.toString() : "N/A"));

            Map<String, Object> extraData = data.getData();
            // Filter out last_login/last_logout from custom data display (already shown above)
            Map<String, Object> customData = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : extraData.entrySet()) {
                if (!"last_login".equals(entry.getKey()) && !"last_logout".equals(entry.getKey())) {
                    customData.put(entry.getKey(), entry.getValue());
                }
            }
            if (!customData.isEmpty()) {
                sender.sendMessage(lang().getComponent("player-data-header"));
                for (Map.Entry<String, Object> entry : customData.entrySet()) {
                    sender.sendMessage(lang().getComponent("player-data-entry",
                        "key", entry.getKey(),
                        "value", String.valueOf(entry.getValue())));
                }
            }
            sender.sendMessage(lang().getComponent("player-end"));
        });
    }
}
