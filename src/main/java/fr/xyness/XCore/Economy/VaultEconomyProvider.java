package fr.xyness.XCore.Economy;

import java.util.Collections;
import java.util.List;

import org.bukkit.OfflinePlayer;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;

/**
 * Vault {@link Economy} provider backed by XCore's economy system.
 * <p>
 * All operations delegate to the vault primary currency.
 * </p>
 */
@SuppressWarnings("deprecation")
public class VaultEconomyProvider implements Economy {

    private final CoinsManager coins;

    public VaultEconomyProvider(CoinsManager coins) {
        this.coins = coins;
    }

    private String vaultId() { return coins.getVaultCurrency().getId(); }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "XCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return coins.getDecimals(); }
    @Override public String format(double amount) { return coins.format(amount); }
    @Override public String currencyNamePlural() { return vaultId(); }
    @Override public String currencyNameSingular() { return vaultId(); }

    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return true; }

    @Override
    public double getBalance(OfflinePlayer player) {
        return coins.getBalance(player.getUniqueId());
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return coins.has(player.getUniqueId(), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Cannot withdraw negative amount.");
        }
        if (!Double.isFinite(amount)) {
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Invalid amount.");
        }
        // What is taken is the amount at the currency's precision, rounded down, and that is what the
        // answer says: 0.015 takes 0.01, never 0.02. Less than the currency can count is refused.
        double taken = coins.amountOf(vaultId(), amount);
        if (amount > 0 && taken <= 0) {
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Amount below the currency's precision.");
        }
        // Vault's contract is synchronous: a plugin hands over goods based on what this returns.
        // The withdrawal is therefore awaited (bounded) and its real outcome reported, rather than
        // checking has() and firing an update that may quietly take nothing.
        java.util.concurrent.CompletableFuture<Boolean> withdrawal = coins.withdraw(player.getUniqueId(), taken);
        Boolean ok = await(withdrawal);
        if (ok == null) {
            // Still running past the bound. The caller is told it failed, so whatever lands after
            // all is given back: otherwise the money would be gone with nothing in return.
            withdrawal.thenAccept(done -> {
                if (!Boolean.TRUE.equals(done) || taken <= 0) return;
                coins.deposit(player.getUniqueId(), taken);
                org.bukkit.Bukkit.getLogger().warning("[XCore] Vault: a late withdrawal of " + taken
                        + " for " + player.getUniqueId() + " was given back (already answered as failed).");
            });
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Economy timed out.");
        }
        if (!ok) {
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Insufficient funds.");
        }
        return new EconomyResponse(taken, getBalance(player), ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Cannot deposit negative amount.");
        }
        if (!Double.isFinite(amount)) {
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Invalid amount.");
        }
        // Credited at the currency's precision, rounded down — the same amount a withdrawal of it
        // takes, so that a plugin moving money from one player to another through Vault never pays
        // out more than it collected.
        double given = coins.amountOf(vaultId(), amount);
        if (amount > 0 && given <= 0) {
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Amount below the currency's precision.");
        }
        java.util.concurrent.CompletableFuture<Double> deposit = coins.deposit(player.getUniqueId(), given);
        Double newBalance = await(deposit);
        if (newBalance == null) {
            // Same as above: a deposit answered as failed must not land afterwards, or a caller that
            // retries or refunds on failure would pay twice.
            deposit.thenAccept(balance -> {
                if (balance == null || given <= 0) return;
                coins.withdraw(player.getUniqueId(), given);
                org.bukkit.Bukkit.getLogger().warning("[XCore] Vault: a late deposit of " + given
                        + " for " + player.getUniqueId() + " was taken back (already answered as failed).");
            });
            // Answered already: refused — no balance row for this player — or failed, which the
            // console says. Only an operation still running is a time-out.
            String why = deposit.isDone() ? "Deposit could not be applied." : "Economy timed out.";
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, why);
        }
        return new EconomyResponse(given, newBalance, ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    /**
     * Awaits an economy future for at most ten seconds.
     *
     * <p>Two seconds used to be enough to answer "failed" while a busy database queue was still
     * about to apply the operation. The bound is now generous, and the callers above undo anything
     * that lands after it, so a failure answer is always true.</p>
     *
     * @return The result, or {@code null} when it failed or did not complete in time.
     */
    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final EconomyResponse NOT_IMPL = new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "XCore economy does not support banks.");

    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return NOT_IMPL; }
    @Override public EconomyResponse deleteBank(String name) { return NOT_IMPL; }
    @Override public EconomyResponse bankBalance(String name) { return NOT_IMPL; }
    @Override public EconomyResponse bankHas(String name, double amount) { return NOT_IMPL; }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return NOT_IMPL; }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return NOT_IMPL; }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return NOT_IMPL; }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return NOT_IMPL; }
    @Override public List<String> getBanks() { return Collections.emptyList(); }

    @Override public boolean hasAccount(String playerName) { return true; }
    @Override public boolean hasAccount(String playerName, String worldName) { return true; }
    @Override public double getBalance(String playerName) { return 0; }
    @Override public double getBalance(String playerName, String world) { return 0; }
    @Override public boolean has(String playerName, double amount) { return false; }
    @Override public boolean has(String playerName, String worldName, double amount) { return false; }
    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return NOT_IMPL; }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return NOT_IMPL; }
    @Override public EconomyResponse depositPlayer(String playerName, double amount) { return NOT_IMPL; }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return NOT_IMPL; }
    @Override public boolean createPlayerAccount(String playerName) { return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
    @Override public EconomyResponse createBank(String name, String player) { return NOT_IMPL; }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return NOT_IMPL; }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return NOT_IMPL; }
}
