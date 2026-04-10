package fr.xyness.XCore.Economy;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * PlaceholderAPI expansion for XCore economy.
 * <p>
 * Available placeholders:
 * <ul>
 *   <li>{@code %xcore_balance%} -- Formatted balance of vault primary currency</li>
 *   <li>{@code %xcore_balance_raw%} -- Raw balance of vault primary currency</li>
 *   <li>{@code %xcore_balance_<currencyId>%} -- Formatted balance of specific currency</li>
 *   <li>{@code %xcore_balance_<currencyId>_raw%} -- Raw balance of specific currency</li>
 * </ul>
 * </p>
 */
public class EconomyExpansion extends PlaceholderExpansion {

    private final CoinsManager coinsManager;

    public EconomyExpansion(CoinsManager coinsManager) {
        this.coinsManager = coinsManager;
    }

    @Override public @NotNull String getIdentifier() { return "xcore"; }
    @Override public @NotNull String getAuthor() { return "Xyness"; }
    @Override public @NotNull String getVersion() { return "2.0.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        String lower = params.toLowerCase();

        // %xcore_balance% -> vault primary formatted
        if (lower.equals("balance")) {
            return coinsManager.format(coinsManager.getBalance(player.getUniqueId()));
        }

        // %xcore_balance_raw% -> vault primary raw
        if (lower.equals("balance_raw")) {
            return String.valueOf(coinsManager.getBalance(player.getUniqueId()));
        }

        // %xcore_balance_<currencyId>_raw% -> specific currency raw
        if (lower.startsWith("balance_") && lower.endsWith("_raw")) {
            String currencyId = lower.substring("balance_".length(), lower.length() - "_raw".length());
            Currency currency = coinsManager.getCurrency(currencyId);
            if (currency != null) {
                return String.valueOf(coinsManager.getBalance(player.getUniqueId(), currencyId));
            }
            return null;
        }

        // %xcore_balance_<currencyId>% -> specific currency formatted
        if (lower.startsWith("balance_")) {
            String currencyId = lower.substring("balance_".length());
            Currency currency = coinsManager.getCurrency(currencyId);
            if (currency != null) {
                return coinsManager.format(currencyId, coinsManager.getBalance(player.getUniqueId(), currencyId));
            }
            return null;
        }

        return null;
    }
}
