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
        if (!has(player, amount)) {
            return new EconomyResponse(0, getBalance(player), ResponseType.FAILURE, "Insufficient funds.");
        }
        coins.removeBalance(player.getUniqueId(), amount);
        double newBalance = getBalance(player);
        return new EconomyResponse(amount, newBalance, ResponseType.SUCCESS, null);
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
        coins.addBalance(player.getUniqueId(), amount);
        double newBalance = getBalance(player);
        return new EconomyResponse(amount, newBalance, ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
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
