package fr.xyness.XCore.Economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Utils.SchedulerAdapter;

/**
 * One way to move money, whichever plugin holds it.
 *
 * <p>A price names its currency by id. An XCore currency ({@code money}, {@code gems}) is served
 * by {@link CoinsManager}; an empty id or {@value #VAULT} means "the economy registered with
 * Vault", which is XCore's own primary currency on most servers and another plugin's on the
 * others. A plugin that sells things should not have to care which: it asks here.</p>
 *
 * <h2>The three rules</h2>
 * <ol>
 *   <li><b>A withdrawal says whether it happened.</b> {@link #withdraw} completes with
 *       {@code true} only when the full amount left the balance; hand nothing over before it
 *       has.</li>
 *   <li><b>Another plugin's economy is only touched from the server thread.</b> Most of them are
 *       not thread-safe. When the economy behind Vault is XCore's own, the same balance is reached
 *       through {@link CoinsManager} instead, off the server thread.</li>
 *   <li><b>Amounts are brought to what the currency can count before they move</b>, with
 *       {@link #ceil} for what a player pays and {@link #floor} for what a player receives, so
 *       that rounding never creates money.</li>
 * </ol>
 *
 * <p>Reach it with {@code money()} from an {@code XPlugin} or an {@code XAddon}, or
 * {@code XCore#money()}.</p>
 *
 * @since 2.1
 */
public final class Money {

    /** The id that stands for the economy registered with Vault. An empty id means the same. */
    public static final String VAULT = "vault";

    /** Decimals kept for an economy that is not XCore's: Vault does not say, and two is what they all use. */
    private static final int FOREIGN_DECIMALS = 2;

    /** How long the answer to "whose economy is behind Vault" is kept, in nanoseconds. */
    private static final long PROVIDER_TTL_NANOS = 5_000_000_000L;

    private final XCore core;

    /** What Vault hands out, as last looked at. */
    private volatile Provider provider = new Provider(null, false, 0L);

    /**
     * The economy registered with Vault.
     *
     * @param economy  The economy, as an object so that this class loads without Vault; {@code null} for none.
     * @param ownCore  Whether it is XCore's own.
     * @param readAt   When it was looked up, {@link System#nanoTime()}.
     */
    private record Provider(Object economy, boolean ownCore, long readAt) {}

    /**
     * @param core The XCore instance.
     */
    public Money(XCore core) {
        this.core = core;
    }

    // -------------------------------------------------------------------------
    // Which backend
    // -------------------------------------------------------------------------

    /**
     * Whether a currency id stands for the Vault economy.
     *
     * @param currencyId The id.
     * @return {@code true} for {@code null}, a blank id or {@value #VAULT}.
     */
    public static boolean isVault(String currencyId) {
        return currencyId == null || currencyId.isBlank() || currencyId.equalsIgnoreCase(VAULT);
    }

    /** The economy Vault hands out right now, looked up again every few seconds. */
    private Provider provider() {
        Provider current = provider;
        long now = System.nanoTime();
        if (current.readAt() != 0L && now - current.readAt() < PROVIDER_TTL_NANOS) return current;
        Object economy = null;
        boolean own = false;
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
                var registration = Bukkit.getServicesManager()
                        .getRegistration(net.milkbowl.vault.economy.Economy.class);
                if (registration != null) {
                    economy = registration.getProvider();
                    own = economy instanceof VaultEconomyProvider;
                }
            }
        } catch (Throwable ignored) {
            // No Vault classes to look at: there is no Vault economy.
        }
        Provider fresh = new Provider(economy, own, now == 0L ? 1L : now);
        provider = fresh;
        return fresh;
    }

    /**
     * The XCore currency an id stands for.
     *
     * @param currencyId The id a price carries.
     * @return The XCore currency, or {@code null} when the money is held by another plugin's
     *         economy, or by nothing at all.
     */
    private Currency coreCurrency(String currencyId) {
        CoinsManager coins = core.getCoinsManager();
        if (!isVault(currencyId)) return coins == null ? null : coins.getCurrency(currencyId);
        if (coins == null) return null;
        Provider behind = provider();
        // XCore's own economy behind Vault, or no Vault at all: the primary currency either way.
        if (behind.ownCore() || behind.economy() == null) return coins.getVaultCurrency();
        return null;
    }

    /**
     * Another plugin's economy when the id stands for one, or {@code null}. As an object: what
     * touches Vault's classes lives in {@link Foreign}, loaded only when Vault is there, so that
     * a server without Vault never links them (a lambda typed on them threw
     * {@code NoClassDefFoundError} where a refusal was due).
     */
    private Object foreign(String currencyId) {
        if (!isVault(currencyId)) return null;
        Provider behind = provider();
        if (behind.economy() == null || behind.ownCore()) return null;
        return behind.economy();
    }

    /**
     * Whether a currency can be used: an XCore currency that exists, or a Vault economy that is
     * registered.
     *
     * @param currencyId The id.
     * @return {@code true} when money can be moved in it.
     */
    public boolean exists(String currencyId) {
        if (coreCurrency(currencyId) != null) return true;
        return foreign(currencyId) != null;
    }

    /**
     * Whether a currency's balances are XCore's own, and so reachable for a player who is
     * offline and from any thread.
     *
     * @param currencyId The id.
     * @return {@code false} for another plugin's economy, or a currency that does not exist.
     */
    public boolean isCore(String currencyId) {
        return coreCurrency(currencyId) != null;
    }

    /**
     * The id of the balance a currency id moves, the same for every way of writing it. {@value #VAULT}
     * and an empty id are XCore's primary currency when XCore's economy is the one behind Vault,
     * so they answer that currency's id; another plugin's economy answers {@value #VAULT}. Two ids
     * with the same answer move the same money: what a module comparing prices across products
     * must group by, or "vault" and "dollar" pass for two currencies.
     *
     * @param currencyId A currency id, as a configuration writes it.
     * @return The id in lower case.
     * @since 2.1
     */
    public String canonical(String currencyId) {
        Currency currency = coreCurrency(currencyId);
        if (currency != null) return currency.getId().toLowerCase(java.util.Locale.ROOT);
        if (isVault(currencyId)) return VAULT;
        return currencyId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The currencies money can be moved in: XCore's own, then {@value #VAULT} when the economy
     * registered with Vault is another plugin's.
     *
     * @return The ids, XCore's primary currency first.
     */
    public List<String> currencies() {
        List<String> out = new ArrayList<>();
        CoinsManager coins = core.getCoinsManager();
        if (coins != null) {
            Currency primary = coins.getVaultCurrency();
            if (primary != null) out.add(primary.getId());
            for (Currency currency : coins.getCurrencies()) {
                if (!out.contains(currency.getId())) out.add(currency.getId());
            }
        }
        try {
            if (foreign(VAULT) != null) out.add(VAULT);
        } catch (Throwable ignored) {
            // No Vault.
        }
        return out;
    }

    /**
     * The currency a price falls back on when it names none: XCore's primary currency, or
     * {@value #VAULT} when XCore's economy is off and another plugin's is registered.
     *
     * @return The id, or an empty string when the server has no economy at all.
     */
    public String defaultCurrency() {
        CoinsManager coins = core.getCoinsManager();
        if (coins != null && coins.getVaultCurrency() != null) return coins.getVaultCurrency().getId();
        return exists(VAULT) ? VAULT : "";
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    /**
     * A balance as this server knows it, for what to show and for refusing early. Never the
     * answer that counts: that is {@link #withdraw}'s.
     *
     * <p>For another plugin's economy this asks Vault, which is only safe on the server thread;
     * from any other thread it answers {@code 0} rather than touch it.</p>
     *
     * @param player     The player.
     * @param currencyId The currency.
     * @return The balance, {@code 0} when unknown.
     */
    public double balance(UUID player, String currencyId) {
        Currency currency = coreCurrency(currencyId);
        if (currency != null) return core.getCoinsManager().getBalance(player, currency.getId());
        try {
            Object economy = foreign(currencyId);
            if (economy == null || !core.schedulerAdapter().isGlobalThread()) return 0;
            return Foreign.balance(economy, player);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Whether a player has at least this much, as far as this server knows (see {@link #balance}).
     *
     * @param player     The player.
     * @param currencyId The currency.
     * @param amount     The amount.
     * @return {@code true} when the balance shown covers it.
     */
    public boolean has(UUID player, String currencyId, double amount) {
        return balance(player, currencyId) >= amount;
    }

    /**
     * How much a balance can still take before the currency's cap, as far as this server knows.
     *
     * <p>What to check before taking a player's items in exchange for money: a deposit that does
     * not fit under {@code max-balance} is trimmed, and the items would have been sold for
     * nothing.</p>
     *
     * @param player     The player.
     * @param currencyId The currency.
     * @return The room left; {@link Double#MAX_VALUE} when the currency has no cap or is another
     *         plugin's.
     */
    public double room(UUID player, String currencyId) {
        Currency currency = coreCurrency(currencyId);
        if (currency == null || currency.getMaxBalance() <= 0) return Double.MAX_VALUE;
        double cap = currency.floor(currency.getMaxBalance());
        return Math.max(0, cap - core.getCoinsManager().getBalance(player, currency.getId()));
    }

    // -------------------------------------------------------------------------
    // Moving
    // -------------------------------------------------------------------------

    /**
     * Takes an amount from a player and reports whether the money was really collected.
     *
     * @param player     The player.
     * @param currencyId The currency.
     * @param amount     The amount; brought up to the currency's precision here ({@link #ceil}),
     *                   so 2.5 of a currency without decimals takes 3, never 2.
     * @return A future completing with {@code true} only when the full amount was withdrawn.
     *         An amount of zero is nothing to take: {@code true}. An amount that is not a number
     *         is refused: {@code false}, not a purchase made for nothing.
     */
    public CompletableFuture<Boolean> withdraw(UUID player, String currencyId, double amount) {
        if (!Double.isFinite(amount)) return CompletableFuture.completedFuture(false);
        if (amount <= 0) return CompletableFuture.completedFuture(true);
        double exact = ceil(currencyId, amount);
        if (!Double.isFinite(exact)) return CompletableFuture.completedFuture(false);
        Currency currency = coreCurrency(currencyId);
        if (currency != null) return core.getCoinsManager().withdraw(player, currency.getId(), exact);
        Object economy = foreign(currencyId);
        if (economy == null) return CompletableFuture.completedFuture(false);
        return onServerThread(() -> Foreign.withdraw(economy, player, exact));
    }

    /**
     * Credits a player.
     *
     * @param player     The player.
     * @param currencyId The currency.
     * @param amount     The amount; brought down to the currency's precision here ({@link #floor}).
     * @return A future completing with {@code true} when the deposit went through. An amount of
     *         zero is nothing to give: {@code true}. An amount that is not a number is refused:
     *         {@code false}, not a payment reported for nothing.
     */
    public CompletableFuture<Boolean> deposit(UUID player, String currencyId, double amount) {
        if (!Double.isFinite(amount)) return CompletableFuture.completedFuture(false);
        if (amount <= 0) return CompletableFuture.completedFuture(true);
        double exact = floor(currencyId, amount);
        if (!Double.isFinite(exact)) return CompletableFuture.completedFuture(false);
        if (exact <= 0) return CompletableFuture.completedFuture(true);
        Currency currency = coreCurrency(currencyId);
        if (currency != null) {
            return core.getCoinsManager().deposit(player, currency.getId(), exact).thenApply(java.util.Objects::nonNull);
        }
        Object economy = foreign(currencyId);
        if (economy == null) return CompletableFuture.completedFuture(false);
        return onServerThread(() -> Foreign.deposit(economy, player, exact));
    }

    /** How long a call to another plugin's economy may wait for the server thread before it is given up. */
    private static final long FOREIGN_WAIT_SECONDS = 15;

    /**
     * Runs a call to another plugin's economy on the server thread, and reports how it ended.
     *
     * <ul>
     *   <li>Already on that thread: called now. The caller may be waiting for the answer on this
     *       very thread (a plugin's disable), where a task for the next tick would never run.</li>
     *   <li>A task the server refuses (it is stopping) is {@code false} at once.</li>
     *   <li>A task the server took and then dropped (it stopped first) would leave the future
     *       waiting forever: after {@value #FOREIGN_WAIT_SECONDS} seconds it is given up, and the
     *       task, should it still run, moves nothing. Exactly one of the two decides.</li>
     * </ul>
     *
     * @return A future completing with the call's result, {@code false} when it throws.
     */
    private CompletableFuture<Boolean> onServerThread(java.util.function.BooleanSupplier call) {
        SchedulerAdapter scheduler = core.schedulerAdapter();
        if (scheduler.isGlobalThread()) {
            try {
                return CompletableFuture.completedFuture(call.getAsBoolean());
            } catch (Throwable t) {
                core.logger().sendWarning("Vault operation failed: " + t.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean decided = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable work = () -> {
            if (!decided.compareAndSet(false, true)) return;
            try {
                future.complete(call.getAsBoolean());
            } catch (Throwable t) {
                core.logger().sendWarning("Vault operation failed: " + t.getMessage());
                future.complete(false);
            }
        };
        if (!scheduler.tryRunGlobalTask(work)) {
            future.complete(false);
            return future;
        }
        CompletableFuture.runAsync(() -> {
            if (decided.compareAndSet(false, true)) future.complete(false);
        }, CompletableFuture.delayedExecutor(FOREIGN_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
        return future;
    }

    /**
     * Everything that touches Vault's classes. A class of its own, loaded the first time a
     * foreign economy is actually used: on a server without Vault it is never loaded at all.
     */
    private static final class Foreign {

        private Foreign() {}

        static double balance(Object economy, UUID player) {
            return ((net.milkbowl.vault.economy.Economy) economy).getBalance(Bukkit.getOfflinePlayer(player));
        }

        static boolean withdraw(Object economy, UUID player, double amount) {
            net.milkbowl.vault.economy.Economy vault = (net.milkbowl.vault.economy.Economy) economy;
            OfflinePlayer account = Bukkit.getOfflinePlayer(player);
            return vault.has(account, amount) && vault.withdrawPlayer(account, amount).transactionSuccess();
        }

        static boolean deposit(Object economy, UUID player, double amount) {
            return ((net.milkbowl.vault.economy.Economy) economy).depositPlayer(Bukkit.getOfflinePlayer(player), amount)
                    .transactionSuccess();
        }

        static String format(Object economy, double amount) {
            return ((net.milkbowl.vault.economy.Economy) economy).format(amount);
        }
    }

    // -------------------------------------------------------------------------
    // Precision and display
    // -------------------------------------------------------------------------

    /**
     * How many decimals a currency keeps.
     *
     * @param currencyId The currency.
     * @return The XCore currency's own setting, or two for another plugin's economy.
     */
    public int decimals(String currencyId) {
        Currency currency = coreCurrency(currencyId);
        return currency != null ? currency.getDecimals() : FOREIGN_DECIMALS;
    }

    /**
     * The smallest amount a currency counts.
     *
     * @param currencyId The currency.
     * @return {@code 0.01} with two decimals, {@code 1} with none.
     */
    public double unit(String currencyId) {
        Currency currency = coreCurrency(currencyId);
        return currency != null ? currency.unit() : BigDecimal.ONE.movePointLeft(FOREIGN_DECIMALS).doubleValue();
    }

    /**
     * Brings an amount down to what the currency can count: what a player receives.
     *
     * @param currencyId The currency.
     * @param amount     The amount.
     * @return The amount at the currency's precision, never above it; unchanged when it is not a
     *         number, which {@link #deposit} then refuses.
     */
    public double floor(String currencyId, double amount) {
        if (!Double.isFinite(amount)) return amount;
        Currency currency = coreCurrency(currencyId);
        if (currency != null) return currency.floor(amount);
        return scaled(amount, RoundingMode.FLOOR);
    }

    /**
     * Brings an amount up to what the currency can count: what a player pays.
     *
     * @param currencyId The currency.
     * @param amount     The amount.
     * @return The amount at the currency's precision, never below it; unchanged when it is not a
     *         number: a price that is not a number must not become free. {@link #withdraw} refuses it.
     */
    public double ceil(String currencyId, double amount) {
        if (!Double.isFinite(amount)) return amount;
        Currency currency = coreCurrency(currencyId);
        if (currency != null) return currency.ceil(amount);
        return scaled(amount, RoundingMode.CEILING);
    }

    /**
     * Rounds an amount to the nearest the currency can count. For showing a value, never for one
     * about to move.
     *
     * @param currencyId The currency.
     * @param amount     The amount.
     * @return The rounded amount; unchanged when it is not a number.
     */
    public double round(String currencyId, double amount) {
        if (!Double.isFinite(amount)) return amount;
        Currency currency = coreCurrency(currencyId);
        if (currency != null) return currency.round(amount);
        return scaled(amount, RoundingMode.HALF_UP);
    }

    /**
     * An amount of another plugin's economy at two decimals, the noise a double carries in its
     * last bits let go first: {@code 0.29999999999999999} is thirty cents, not twenty-nine.
     */
    private static double scaled(double amount, RoundingMode mode) {
        BigDecimal exact = BigDecimal.valueOf(amount);
        BigDecimal nearest = exact.setScale(FOREIGN_DECIMALS, RoundingMode.HALF_UP);
        // Never more than a thousandth of a cent: past a few thousand billion, eight ulps are a cent.
        if (Math.abs(nearest.doubleValue() - amount) <= Math.min(8 * Math.ulp(amount), 0.00001)) return nearest.doubleValue();
        return exact.setScale(FOREIGN_DECIMALS, mode).doubleValue();
    }

    /**
     * An amount as the currency writes it: its symbol, its separators, its decimals.
     *
     * @param currencyId The currency.
     * @param amount     The amount.
     * @return The text to show.
     */
    public String format(String currencyId, double amount) {
        Currency currency = coreCurrency(currencyId);
        if (currency != null) return currency.format(amount);
        try {
            // Another plugin's economy is asked on the server thread only, like every call to it.
            Object economy = foreign(currencyId);
            if (economy != null && core.schedulerAdapter().isGlobalThread()) return Foreign.format(economy, amount);
        } catch (Throwable ignored) {
            // Fall through to the bare number.
        }
        return BigDecimal.valueOf(amount).setScale(FOREIGN_DECIMALS, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The symbol of a currency, for a short label.
     *
     * @param currencyId The currency.
     * @return The XCore currency's symbol, or an empty string for another plugin's economy.
     */
    public String symbol(String currencyId) {
        Currency currency = coreCurrency(currencyId);
        return currency != null ? currency.getSymbol() : "";
    }
}
