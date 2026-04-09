package fr.xyness.XCore.Integrations;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.economy.Economy;

/**
 * Centralized Vault economy hook for XCore and its addons.
 * <p>
 * Provides a simple interface to the Vault economy API. Call {@link #setup()}
 * during plugin enable to initialize the hook, then use the convenience
 * methods to check balances, withdraw, deposit, etc.
 * </p>
 */
@SuppressWarnings("deprecation")
public class VaultHook {

    /** The Vault economy provider, or {@code null} if not available. */
    private Economy economy;

    /**
     * Attempts to hook into the Vault economy.
     *
     * @return {@code true} if the economy was found and set up successfully.
     */
    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    /**
     * Returns whether the Vault economy is available.
     *
     * @return {@code true} if an economy provider has been resolved.
     */
    public boolean isAvailable() {
        return economy != null;
    }

    /**
     * Returns the balance of a player.
     *
     * @param player The player to query.
     * @return The player's balance, or {@code 0} if the economy is unavailable.
     */
    public double getBalance(OfflinePlayer player) {
        if (economy == null) return 0;
        return economy.getBalance(player);
    }

    /**
     * Withdraws an amount from a player's balance.
     *
     * @param player The player to withdraw from.
     * @param amount The amount to withdraw.
     * @return {@code true} if the withdrawal was successful.
     */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    /**
     * Deposits an amount into a player's balance.
     *
     * @param player The player to deposit to.
     * @param amount The amount to deposit.
     * @return {@code true} if the deposit was successful.
     */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    /**
     * Checks whether a player has at least the specified amount.
     *
     * @param player The player to check.
     * @param amount The amount to check for.
     * @return {@code true} if the player has at least the given amount.
     */
    public boolean has(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        return economy.has(player, amount);
    }

    /**
     * Formats a monetary amount into a human-readable string using Vault's formatter.
     *
     * @param amount The amount to format.
     * @return The formatted currency string, or the raw number if economy is unavailable.
     */
    public String format(double amount) {
        if (economy == null) return String.valueOf(amount);
        return economy.format(amount);
    }
}
