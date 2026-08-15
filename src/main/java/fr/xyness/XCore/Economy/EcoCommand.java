package fr.xyness.XCore.Economy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.API.XCoreApi;
import fr.xyness.XCore.API.XCoreApiProvider;
import fr.xyness.XCore.Utils.LangManager;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.stream.Collectors;

/**
 * Registers the {@code /eco} command using Paper's Brigadier lifecycle API.
 */
public class EcoCommand {

    private final XCore plugin;
    private final CoinsManager coinsManager;
    private final LangManager lang;

    public EcoCommand(XCore plugin, CoinsManager coinsManager, LangManager lang) {
        this.plugin = plugin;
        this.coinsManager = coinsManager;
        this.lang = lang;
    }

    private final SuggestionProvider<CommandSourceStack> playerSuggestions = (ctx, builder) -> {
        String input = builder.getRemainingLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(input)) {
                builder.suggest(p.getName());
            }
        }
        return builder.buildFuture();
    };

    /** Currency suggestions plus the {@code all} keyword used by the reset commands. */
    private SuggestionProvider<CommandSourceStack> currencyOrAllSuggestions() {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            if ("all".startsWith(input)) builder.suggest("all");
            for (Currency c : coinsManager.getCurrencies()) {
                if (c.getId().toLowerCase().startsWith(input)) {
                    builder.suggest(c.getId());
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> currencySuggestions() {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            for (Currency c : coinsManager.getCurrencies()) {
                if (c.getId().toLowerCase().startsWith(input)) {
                    builder.suggest(c.getId());
                }
            }
            return builder.buildFuture();
        };
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(
                Commands.literal("eco")
                    .executes(ctx -> { handleBalanceSelf(ctx.getSource().getSender(), null); return Command.SINGLE_SUCCESS; })

                    // /eco balance [player] [currency]
                    .then(Commands.literal("balance")
                        .executes(ctx -> { handleBalanceSelf(ctx.getSource().getSender(), null); return Command.SINGLE_SUCCESS; })
                        .then(Commands.argument("player", StringArgumentType.word())
                            .requires(src -> src.getSender().hasPermission("xcore.economy.balance.others"))
                            .suggests(playerSuggestions)
                            .executes(ctx -> {
                                handleBalanceOther(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "player"), null);
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(currencySuggestions())
                                .executes(ctx -> {
                                    handleBalanceOther(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "currency"));
                                    return Command.SINGLE_SUCCESS;
                                })
                            )
                        )
                    )

                    // /eco pay <player> <amount> [currency]
                    .then(Commands.literal("pay")
                        .requires(src -> src.getSender() instanceof Player)
                        .then(Commands.argument("target", StringArgumentType.word())
                            .suggests(playerSuggestions)
                            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    handlePay(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "target"),
                                        DoubleArgumentType.getDouble(ctx, "amount"), null);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("currency", StringArgumentType.word())
                                    .suggests(currencySuggestions())
                                    .executes(ctx -> {
                                        handlePay(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "target"),
                                            DoubleArgumentType.getDouble(ctx, "amount"),
                                            StringArgumentType.getString(ctx, "currency"));
                                        return Command.SINGLE_SUCCESS;
                                    })
                                )
                            )
                        )
                    )

                    // /eco set <player> <amount> [currency]
                    .then(Commands.literal("set")
                        .requires(src -> src.getSender().hasPermission("xcore.economy.admin") || src.getSender().hasPermission("xcore.economy.admin.set"))
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(playerSuggestions)
                            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(ctx -> {
                                    handleSet(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        DoubleArgumentType.getDouble(ctx, "amount"), null);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("currency", StringArgumentType.word())
                                    .suggests(currencySuggestions())
                                    .executes(ctx -> {
                                        handleSet(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "player"),
                                            DoubleArgumentType.getDouble(ctx, "amount"),
                                            StringArgumentType.getString(ctx, "currency"));
                                        return Command.SINGLE_SUCCESS;
                                    })
                                )
                            )
                        )
                    )

                    // /eco add <player> <amount> [currency]
                    .then(Commands.literal("add")
                        .requires(src -> src.getSender().hasPermission("xcore.economy.admin") || src.getSender().hasPermission("xcore.economy.admin.add"))
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(playerSuggestions)
                            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    handleAdd(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        DoubleArgumentType.getDouble(ctx, "amount"), null);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("currency", StringArgumentType.word())
                                    .suggests(currencySuggestions())
                                    .executes(ctx -> {
                                        handleAdd(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "player"),
                                            DoubleArgumentType.getDouble(ctx, "amount"),
                                            StringArgumentType.getString(ctx, "currency"));
                                        return Command.SINGLE_SUCCESS;
                                    })
                                )
                            )
                        )
                    )

                    // /eco remove <player> <amount> [currency]
                    .then(Commands.literal("remove")
                        .requires(src -> src.getSender().hasPermission("xcore.economy.admin") || src.getSender().hasPermission("xcore.economy.admin.remove"))
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(playerSuggestions)
                            .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    handleRemove(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        DoubleArgumentType.getDouble(ctx, "amount"), null);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("currency", StringArgumentType.word())
                                    .suggests(currencySuggestions())
                                    .executes(ctx -> {
                                        handleRemove(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "player"),
                                            DoubleArgumentType.getDouble(ctx, "amount"),
                                            StringArgumentType.getString(ctx, "currency"));
                                        return Command.SINGLE_SUCCESS;
                                    })
                                )
                            )
                        )
                    )

                    // /eco reset <player> [currency]
                    .then(Commands.literal("reset")
                        .requires(src -> src.getSender().hasPermission("xcore.economy.admin") || src.getSender().hasPermission("xcore.economy.admin.reset"))
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(playerSuggestions)
                            .executes(ctx -> {
                                handleReset(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "player"), null);
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(currencyOrAllSuggestions())
                                .executes(ctx -> {
                                    handleReset(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "currency"));
                                    return Command.SINGLE_SUCCESS;
                                })
                            )
                        )
                    )

                    // /eco resetall confirm [currency]
                    .then(Commands.literal("resetall")
                        .requires(src -> src.getSender().hasPermission("xcore.economy.admin") || src.getSender().hasPermission("xcore.economy.admin.resetall"))
                        .executes(ctx -> { handleResetAllWarning(ctx.getSource().getSender(), null); return Command.SINGLE_SUCCESS; })
                        .then(Commands.argument("currency", StringArgumentType.word())
                            .suggests(currencyOrAllSuggestions())
                            .executes(ctx -> {
                                handleResetAllWarning(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "currency"));
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.literal("confirm")
                                .executes(ctx -> {
                                    handleResetAll(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "currency"));
                                    return Command.SINGLE_SUCCESS;
                                })
                            )
                        )
                    )

                    // /eco exchange <from> <to> <amount>
                    .then(Commands.literal("exchange")
                        .requires(src -> src.getSender() instanceof Player)
                        .then(Commands.argument("from", StringArgumentType.word())
                            .suggests(currencySuggestions())
                            .then(Commands.argument("to", StringArgumentType.word())
                                .suggests(currencySuggestions())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                    .executes(ctx -> {
                                        handleExchange(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "from"),
                                            StringArgumentType.getString(ctx, "to"),
                                            DoubleArgumentType.getDouble(ctx, "amount"));
                                        return Command.SINGLE_SUCCESS;
                                    })
                                )
                            )
                        )
                    )

                    // /eco history [player] [currency] [page]
                    .then(Commands.literal("history")
                        .executes(ctx -> { handleHistory(ctx.getSource().getSender(), null, null, 1); return Command.SINGLE_SUCCESS; })
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(playerSuggestions)
                            .executes(ctx -> {
                                handleHistory(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "player"), null, 1);
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(currencySuggestions())
                                .executes(ctx -> {
                                    handleHistory(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "currency"), 1);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes(ctx -> {
                                        handleHistory(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "player"),
                                            StringArgumentType.getString(ctx, "currency"),
                                            IntegerArgumentType.getInteger(ctx, "page"));
                                        return Command.SINGLE_SUCCESS;
                                    })
                                )
                            )
                        )
                    )

                    // /eco top [currency]
                    .then(Commands.literal("top")
                        .executes(ctx -> { handleTop(ctx.getSource().getSender(), null); return Command.SINGLE_SUCCESS; })
                        .then(Commands.argument("currency", StringArgumentType.word())
                            .suggests(currencySuggestions())
                            .executes(ctx -> {
                                handleTop(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "currency"));
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                    )

                    // /eco help
                    .then(Commands.literal("help")
                        .executes(ctx -> { handleHelp(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                    )

                    // /eco reload
                    .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("xcore.economy.admin") || src.getSender().hasPermission("xcore.economy.admin.reload"))
                        .executes(ctx -> { handleReload(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                    )

                    .build(),
                "Economy commands",
                // /bal is deliberately absent: it belongs to EssentialsX on most servers and
                // claiming it would break their setup.
                java.util.List.of("economy", "money")
            );
        });
    }

    private LangManager lang() { return lang; }
    private CoinsManager coins() { return coinsManager; }
    private XCoreApi api() { return XCoreApiProvider.get(); }

    /**
     * Returns the resolved currency ID, or null if invalid (error already sent).
     */
    private String resolveCurrency(CommandSender sender, String input) {
        if (input == null) return coins().getVaultCurrency().getId();
        if (coins().getCurrency(input) != null) return input;
        String available = coins().getCurrencies().stream()
            .map(Currency::getId)
            .collect(Collectors.joining(", "));
        sender.sendMessage(lang().getComponent("eco-invalid-currency", "currency", input, "currencies", available));
        return null;
    }

    private void handleBalanceSelf(CommandSender sender, String currencyId) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang().getComponent("player-only"));
            return;
        }

        if (currencyId != null) {
            // Single currency
            String resolved = resolveCurrency(sender, currencyId);
            if (resolved == null) return;
            double balance = coins().getBalance(player.getUniqueId(), resolved);
            sender.sendMessage(lang().getComponent("eco-balance-self-single",
                "balance", coins().format(resolved, balance), "currency", resolved));
            return;
        }

        // All currencies
        sender.sendMessage(lang().getComponent("eco-balance-title"));
        for (Currency c : coins().getCurrencies()) {
            double balance = coins().getBalance(player.getUniqueId(), c.getId());
            sender.sendMessage(lang().getComponent("eco-balance-entry",
                "balance", c.format(balance), "currency", c.getId()));
        }
        sender.sendMessage(lang().getComponent("eco-balance-end"));
    }

    private void handleBalanceOther(CommandSender sender, String name, String currencyId) {
        if (currencyId != null) {
            String resolved = resolveCurrency(sender, currencyId);
            if (resolved == null) return;
            coins().getBalanceAsync(name, resolved).thenAccept(balance ->
                sender.sendMessage(lang().getComponent("eco-balance-other-single",
                    "name", name, "balance", coins().format(resolved, balance), "currency", resolved))
            ).exceptionally(ex -> {
                plugin.logger().sendWarning("Failed to fetch balance for " + name + ": " + ex.getMessage());
                sender.sendMessage(lang().getComponent("error"));
                return null;
            });
            return;
        }

        // All currencies for that player
        api().getPlayerAsync(name).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sender.sendMessage(lang().getComponent("player-not-found", "name", name));
                return;
            }
            var data = opt.get();
            sender.sendMessage(lang().getComponent("eco-balance-title"));
            for (Currency c : coins().getCurrencies()) {
                Double value = data.getTargetData(coinsManager.col(c.getId()), Double.class);
                double balance = value != null ? value : c.getStartingBalance();
                sender.sendMessage(lang().getComponent("eco-balance-entry",
                    "balance", c.format(balance), "currency", c.getId()));
            }
            sender.sendMessage(lang().getComponent("eco-balance-end"));
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("Failed to fetch balances for " + name + ": " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handlePay(CommandSender sender, String targetName, double amount, String currencyId) {
        Player player = (Player) sender;
        String resolved = resolveCurrency(sender, currencyId);
        if (resolved == null) return;

        if (targetName.equalsIgnoreCase(player.getName())) {
            sender.sendMessage(lang().getComponent("eco-pay-self"));
            return;
        }

        api().getPlayerAsync(targetName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sender.sendMessage(lang().getComponent("player-not-found", "name", targetName));
                return;
            }

            // Check balance right before modifying to avoid race conditions
            if (!coins().has(player.getUniqueId(), resolved, amount)) {
                sender.sendMessage(lang().getComponent("eco-insufficient-funds",
                    "balance", coins().format(resolved, coins().getBalance(player.getUniqueId(), resolved))));
                return;
            }

            var targetData = opt.get();

            // Fire event for sender (remove) — events are dispatched synchronously by CoinsManager
            coins().getBalanceAsync(player.getUniqueId(), resolved).thenCompose(oldSenderBal ->
                coins().fireBalanceChangeEvent(player.getUniqueId(), resolved, oldSenderBal, oldSenderBal - amount, BalanceChangeEvent.ChangeType.PAY).thenCompose(senderCancelled -> {
                    if (senderCancelled) return java.util.concurrent.CompletableFuture.completedFuture((Void) null);

                    return coins().removeBalance(player.getUniqueId(), resolved, amount)
                        .thenCompose(v ->
                            // Fire event for receiver (add)
                            coins().getBalanceAsync(targetData.getUuid(), resolved).thenCompose(oldTargetBal ->
                                coins().fireBalanceChangeEvent(targetData.getUuid(), resolved, oldTargetBal, oldTargetBal + amount, BalanceChangeEvent.ChangeType.PAY).thenCompose(targetCancelled -> {
                                    if (targetCancelled) return java.util.concurrent.CompletableFuture.completedFuture((Void) null);

                                    return coins().addBalance(targetData.getUuid(), resolved, amount);
                                })))
                        .thenRun(() -> {
                            String formatted = coins().format(resolved, amount);
                            sender.sendMessage(lang().getComponent("eco-pay-sent",
                                "amount", formatted, "target", targetData.getName(), "currency", resolved));

                            Player target = Bukkit.getPlayer(targetData.getUuid());
                            if (target != null && target.isOnline()) {
                                target.sendMessage(lang().getComponent("eco-pay-received",
                                    "amount", formatted, "sender", player.getName(), "currency", resolved));
                            }

                            // Log transactions
                            coins().logTransaction(player.getUniqueId(), player.getName(), resolved, -amount, "PAY", targetData.getName(), "Paid " + formatted + " to " + targetData.getName());
                            coins().logTransaction(targetData.getUuid(), targetData.getName(), resolved, amount, "PAY", player.getName(), "Received " + formatted + " from " + player.getName());
                        });
                })
            ).exceptionally(ex -> {
                plugin.logger().sendWarning("Pay command failed for " + player.getName() + ": " + ex.getMessage());
                sender.sendMessage(lang().getComponent("error"));
                return null;
            });
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("Pay command failed to resolve target " + targetName + ": " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handleSet(CommandSender sender, String name, double amount, String currencyId) {
        String resolved = resolveCurrency(sender, currencyId);
        if (resolved == null) return;

        api().getPlayerAsync(name).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sender.sendMessage(lang().getComponent("player-not-found", "name", name));
                return;
            }
            var data = opt.get();
            coins().setBalanceWithEvent(data.getUuid(), resolved, amount, BalanceChangeEvent.ChangeType.SET).thenAccept(capped -> {
                sender.sendMessage(lang().getComponent("eco-set-success",
                    "name", data.getName(), "amount", coins().format(resolved, amount), "currency", resolved));
                if (capped) {
                    sender.sendMessage(lang().getComponent("eco-balance-capped", "currency", resolved,
                        "max", coins().format(resolved, coins().getCurrency(resolved).getMaxBalance())));
                }

                // Log transaction
                coins().logTransaction(data.getUuid(), data.getName(), resolved, amount, "SET", sender.getName(), "Balance set to " + coins().format(resolved, amount) + " by " + sender.getName());
            }).exceptionally(ex -> {
                plugin.logger().sendWarning("Set command failed for " + name + ": " + ex.getMessage());
                sender.sendMessage(lang().getComponent("error"));
                return null;
            });
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("Set command failed to resolve player " + name + ": " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handleAdd(CommandSender sender, String name, double amount, String currencyId) {
        String resolved = resolveCurrency(sender, currencyId);
        if (resolved == null) return;

        api().getPlayerAsync(name).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sender.sendMessage(lang().getComponent("player-not-found", "name", name));
                return;
            }
            var data = opt.get();
            coins().getBalanceAsync(data.getUuid(), resolved).thenCompose(oldBalance -> {
                double newAmount = oldBalance + amount;
                return coins().setBalanceWithEvent(data.getUuid(), resolved, newAmount, BalanceChangeEvent.ChangeType.ADD);
            }).thenCompose(capped ->
                coins().getBalanceAsync(data.getUuid(), resolved).thenAccept(newBalance -> {
                    sender.sendMessage(lang().getComponent("eco-add-success",
                        "name", data.getName(), "amount", coins().format(resolved, amount),
                        "balance", coins().format(resolved, newBalance), "currency", resolved));
                    if (capped) {
                        sender.sendMessage(lang().getComponent("eco-balance-capped", "currency", resolved,
                            "max", coins().format(resolved, coins().getCurrency(resolved).getMaxBalance())));
                    }

                    // Log transaction
                    coins().logTransaction(data.getUuid(), data.getName(), resolved, amount, "ADD", sender.getName(), "Added " + coins().format(resolved, amount) + " by " + sender.getName());
                })
            ).exceptionally(ex -> {
                plugin.logger().sendWarning("Add command failed for " + name + ": " + ex.getMessage());
                sender.sendMessage(lang().getComponent("error"));
                return null;
            });
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("Add command failed to resolve player " + name + ": " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handleRemove(CommandSender sender, String name, double amount, String currencyId) {
        String resolved = resolveCurrency(sender, currencyId);
        if (resolved == null) return;

        api().getPlayerAsync(name).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sender.sendMessage(lang().getComponent("player-not-found", "name", name));
                return;
            }
            var data = opt.get();
            coins().getBalanceAsync(data.getUuid(), resolved).thenCompose(oldBalance -> {
                double newAmount = Math.max(0, oldBalance - amount);
                return coins().setBalanceWithEvent(data.getUuid(), resolved, newAmount, BalanceChangeEvent.ChangeType.REMOVE);
            }).thenCompose(capped ->
                coins().getBalanceAsync(data.getUuid(), resolved).thenAccept(newBalance -> {
                    sender.sendMessage(lang().getComponent("eco-remove-success",
                        "name", data.getName(), "amount", coins().format(resolved, amount),
                        "balance", coins().format(resolved, newBalance), "currency", resolved));

                    // Log transaction
                    coins().logTransaction(data.getUuid(), data.getName(), resolved, -amount, "REMOVE", sender.getName(), "Removed " + coins().format(resolved, amount) + " by " + sender.getName());
                })
            ).exceptionally(ex -> {
                plugin.logger().sendWarning("Remove command failed for " + name + ": " + ex.getMessage());
                sender.sendMessage(lang().getComponent("error"));
                return null;
            });
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("Remove command failed to resolve player " + name + ": " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    /**
     * Resolves the currencies targeted by a reset command.
     * {@code null} means the vault currency, {@code "all"} means every configured currency.
     *
     * @return The currency IDs, or {@code null} if the input was invalid (error already sent).
     */
    private java.util.List<String> resolveResetCurrencies(CommandSender sender, String input) {
        if (input == null) {
            Currency vault = coins().getVaultCurrency();
            if (vault == null) {
                sender.sendMessage(lang().getComponent("eco-reload-warning-no-vault"));
                return null;
            }
            return java.util.List.of(vault.getId());
        }
        if ("all".equalsIgnoreCase(input)) {
            return coins().getCurrencies().stream().map(Currency::getId).toList();
        }
        String resolved = resolveCurrency(sender, input);
        return resolved == null ? null : java.util.List.of(resolved);
    }

    private void handleReset(CommandSender sender, String name, String currencyId) {
        java.util.List<String> targets = resolveResetCurrencies(sender, currencyId);
        if (targets == null) return;

        api().getPlayerAsync(name).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sender.sendMessage(lang().getComponent("player-not-found", "name", name));
                return;
            }
            var data = opt.get();
            for (String currency : targets) {
                coins().resetBalance(data.getUuid(), currency).thenAccept(start -> {
                    sender.sendMessage(lang().getComponent("eco-reset-success",
                        "name", data.getName(), "currency", currency,
                        "amount", coins().format(currency, start)));
                    coins().logTransaction(data.getUuid(), data.getName(), currency, start, "RESET",
                        sender.getName(), "Balance reset to " + coins().format(currency, start) + " by " + sender.getName());
                });
            }
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("Reset command failed to resolve player " + name + ": " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handleResetAllWarning(CommandSender sender, String currencyId) {
        java.util.List<String> targets = resolveResetCurrencies(sender, currencyId);
        if (targets == null) return;
        String label = currencyId != null ? currencyId : targets.get(0);
        sender.sendMessage(lang().getComponent("eco-resetall-warning",
            "currency", String.join(", ", targets)));
        sender.sendMessage(lang().getComponent("eco-resetall-confirm-usage", "currency", label));
    }

    private void handleResetAll(CommandSender sender, String currencyId) {
        java.util.List<String> targets = resolveResetCurrencies(sender, currencyId);
        if (targets == null) return;

        sender.sendMessage(lang().getComponent("eco-resetall-started", "currency", String.join(", ", targets)));
        coins().resetAllBalances(targets).thenAccept(rows -> {
            if (rows < 0) {
                sender.sendMessage(lang().getComponent("error"));
                return;
            }
            sender.sendMessage(lang().getComponent("eco-resetall-success",
                "count", String.valueOf(rows), "currency", String.join(", ", targets)));
            plugin.logger().sendInfo("Economy: " + sender.getName() + " reset " + rows
                + " player balance(s) for [" + String.join(", ", targets) + "].");
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("Reset-all command failed: " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handleExchange(CommandSender sender, String from, String to, double amount) {
        Player player = (Player) sender;

        if (!coins().isExchangeEnabled()) {
            sender.sendMessage(lang().getComponent("eco-exchange-disabled"));
            return;
        }

        String resolvedFrom = resolveCurrency(sender, from);
        if (resolvedFrom == null) return;
        String resolvedTo = resolveCurrency(sender, to);
        if (resolvedTo == null) return;

        if (resolvedFrom.equals(resolvedTo)) {
            sender.sendMessage(lang().getComponent("eco-exchange-no-rate"));
            return;
        }

        double rate = coins().getExchangeRate(resolvedFrom, resolvedTo);
        if (rate <= 0) {
            sender.sendMessage(lang().getComponent("eco-exchange-no-rate"));
            return;
        }

        double destinationAmount = Math.floor(amount / rate);
        if (destinationAmount <= 0) {
            sender.sendMessage(lang().getComponent("eco-exchange-no-rate"));
            return;
        }

        if (!coins().has(player.getUniqueId(), resolvedFrom, amount)) {
            sender.sendMessage(lang().getComponent("eco-insufficient-funds",
                "balance", coins().format(resolvedFrom, coins().getBalance(player.getUniqueId(), resolvedFrom))));
            return;
        }

        // Fire events for both currencies — events are dispatched synchronously by CoinsManager
        coins().getBalanceAsync(player.getUniqueId(), resolvedFrom).thenCompose(oldFromBal ->
            coins().fireBalanceChangeEvent(player.getUniqueId(), resolvedFrom, oldFromBal, oldFromBal - amount, BalanceChangeEvent.ChangeType.EXCHANGE).thenCompose(fromCancelled -> {
                if (fromCancelled) return java.util.concurrent.CompletableFuture.completedFuture((Void) null);

                return coins().removeBalance(player.getUniqueId(), resolvedFrom, amount)
                    .thenCompose(v -> coins().getBalanceAsync(player.getUniqueId(), resolvedTo))
                    .thenCompose(oldToBal ->
                        coins().fireBalanceChangeEvent(player.getUniqueId(), resolvedTo, oldToBal, oldToBal + destinationAmount, BalanceChangeEvent.ChangeType.EXCHANGE).thenCompose(toCancelled -> {
                            if (toCancelled) return java.util.concurrent.CompletableFuture.completedFuture((Void) null);

                            return coins().addBalance(player.getUniqueId(), resolvedTo, destinationAmount);
                        }))
                    .thenRun(() -> {
                        String formattedFrom = coins().format(resolvedFrom, amount);
                        String formattedTo = coins().format(resolvedTo, destinationAmount);
                        sender.sendMessage(lang().getComponent("eco-exchange-success",
                            "from_amount", formattedFrom, "from_currency", resolvedFrom,
                            "to_amount", formattedTo, "to_currency", resolvedTo));

                        // Log transactions
                        coins().logTransaction(player.getUniqueId(), player.getName(), resolvedFrom, -amount, "EXCHANGE", null, "Exchanged " + formattedFrom + " to " + formattedTo);
                        coins().logTransaction(player.getUniqueId(), player.getName(), resolvedTo, destinationAmount, "EXCHANGE", null, "Received " + formattedTo + " from exchange of " + formattedFrom);
                    });
            })
        ).exceptionally(ex -> {
            plugin.logger().sendWarning("Exchange command failed for " + player.getName() + ": " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handleHistory(CommandSender sender, String playerName, String currency, int page) {
        // Determine whose history to show
        String targetName;
        if (playerName == null) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang().getComponent("player-only"));
                return;
            }
            targetName = player.getName();
        } else {
            // Check if looking at someone else's history
            boolean isSelf = sender instanceof Player p && p.getName().equalsIgnoreCase(playerName);
            if (!isSelf && !sender.hasPermission("xcore.economy.admin")) {
                sender.sendMessage(lang().getComponent("no-permission"));
                return;
            }
            targetName = playerName;
        }

        // Validate currency if provided
        String resolvedCurrency = null;
        if (currency != null) {
            if (coins().getCurrency(currency) != null) {
                resolvedCurrency = currency;
            }
            // If currency is not valid, treat it as null (show all)
        }

        int limit = 10;
        final String finalCurrency = resolvedCurrency;
        coins().getTransactionCount(targetName, finalCurrency).thenCompose(total -> {
            int maxPage = Math.max(1, (int) Math.ceil((double) total / limit));
            int safePage = Math.min(page, maxPage);

            return coins().getTransactions(targetName, finalCurrency, safePage, limit).thenAccept(records -> {
                sender.sendMessage(lang().getComponent("eco-history-title", "name", targetName, "page", String.valueOf(safePage), "max_page", String.valueOf(maxPage)));

                if (records.isEmpty()) {
                    sender.sendMessage(lang().getComponent("eco-history-empty"));
                } else {
                    for (var record : records) {
                        sender.sendMessage(lang().getComponent("eco-history-entry",
                            "type", record.type(),
                            "currency", record.currency(),
                            "amount", String.valueOf(record.amount()),
                            "target", record.targetName() != null ? record.targetName() : "-",
                            "date", record.createdAt()));
                    }
                }

                sender.sendMessage(lang().getComponent("eco-history-end"));
            });
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("History command failed: " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handleHelp(CommandSender sender) {
        String raw = lang().getMessageString("eco-help-message");
        if (raw == null || raw.isBlank()) return;
        for (String line : raw.split("\n")) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(line));
        }
    }

    private void handleTop(CommandSender sender, String currencyId) {
        String resolved = resolveCurrency(sender, currencyId);
        if (resolved == null) return;

        api().getPlayersAsync(
            Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList()
        ).thenAccept(map -> {
            sender.sendMessage(lang().getComponent("eco-top-title", "currency", resolved));

            String colName = coinsManager.col(resolved);
            var sorted = map.entrySet().stream()
                .filter(e -> e.getValue().isPresent())
                .map(e -> e.getValue().get())
                .sorted((a, b) -> {
                    Double ca = a.getTargetData(colName, Double.class);
                    Double cb = b.getTargetData(colName, Double.class);
                    return Double.compare(cb != null ? cb : 0, ca != null ? ca : 0);
                })
                .limit(10)
                .toList();

            if (sorted.isEmpty()) {
                sender.sendMessage(lang().getComponent("eco-top-empty"));
            } else {
                for (int i = 0; i < sorted.size(); i++) {
                    var data = sorted.get(i);
                    Double c = data.getTargetData(colName, Double.class);
                    sender.sendMessage(lang().getComponent("eco-top-entry",
                        "rank", String.valueOf(i + 1),
                        "name", data.getName(),
                        "balance", coins().format(resolved, c != null ? c : 0)));
                }
            }

            sender.sendMessage(lang().getComponent("eco-top-end"));
        }).exceptionally(ex -> {
            plugin.logger().sendWarning("Top command failed: " + ex.getMessage());
            sender.sendMessage(lang().getComponent("error"));
            return null;
        });
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        coinsManager.reload();
        lang.reload();
        if (coins().getVaultCurrency() == null) {
            plugin.logger().sendWarning("No vault currency found after reload! Check your economy currencies config.");
            sender.sendMessage(lang().getComponent("eco-reload-warning-no-vault"));
        }
        sender.sendMessage(lang().getComponent("eco-reload-success"));
    }
}
