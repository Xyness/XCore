package fr.xyness.XCore.Economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Represents a single currency definition loaded from config.
 */
public class Currency {

    private final String id;
    private final String symbol;
    private final boolean symbolBefore;
    private final int decimals;
    private final double startingBalance;
    private final boolean vaultPrimary;
    private final double maxBalance;

    public Currency(String id, String symbol, boolean symbolBefore, int decimals, double startingBalance, boolean vaultPrimary, double maxBalance) {
        this.id = id;
        this.symbol = symbol;
        this.symbolBefore = symbolBefore;
        this.decimals = decimals;
        this.startingBalance = startingBalance;
        this.vaultPrimary = vaultPrimary;
        this.maxBalance = maxBalance;
    }

    /**
     * Formats the given amount using this currency's symbol, position, and decimal settings.
     *
     * @param amount The amount to format.
     * @return The formatted string (e.g. "$1,234.56" or "100\u2726").
     */
    public String format(double amount) {
        BigDecimal bd = BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.HALF_UP);
        String formatted = formatWithSeparators(bd);
        return symbolBefore ? symbol + formatted : formatted + symbol;
    }

    /**
     * Rounds the given value to this currency's decimal precision.
     *
     * @param value The value to round.
     * @return The rounded value.
     */
    public double round(double value) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    private String formatWithSeparators(BigDecimal bd) {
        String[] parts = bd.toPlainString().split("\\.");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = sb.length() - 3; i > 0; i -= 3) {
            sb.insert(i, ',');
        }
        if (parts.length > 1) {
            sb.append('.').append(parts[1]);
        }
        return sb.toString();
    }

    public String getId() { return id; }
    public String getSymbol() { return symbol; }
    public boolean isSymbolBefore() { return symbolBefore; }
    public int getDecimals() { return decimals; }
    public double getStartingBalance() { return startingBalance; }
    public boolean isVaultPrimary() { return vaultPrimary; }
    public double getMaxBalance() { return maxBalance; }
}
