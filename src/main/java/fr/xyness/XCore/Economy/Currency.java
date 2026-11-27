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
     * Rounds the given value to this currency's decimal precision, to the nearest.
     *
     * <p>For showing a value. Never for one about to be applied or stored: rounding a balance to
     * the nearest is how money used to appear — a payment of {@code 0.015} took {@code 0.01} from
     * one side and gave {@code 0.02} to the other. See {@link #floor(double)}.</p>
     *
     * @param value The value to round.
     * @return The rounded value.
     */
    public double round(double value) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * How far from a step of the precision a value may sit and still be read as that step, in units
     * in the last place of the value. A double carries noise in its last bits: {@code 0.3 - 0.1} is
     * {@code 0.19999999999999998}, which is {@code 0.20} and not {@code 0.19}.
     */
    private static final int NOISE_ULPS = 8;

    /**
     * Brings a value down to this currency's precision: the largest amount it can count that is not
     * above the value.
     *
     * <p>What every amount is brought to before it is applied, and every balance after: rounded
     * this way, an amount can never credit more than was asked, a debit never take more, and a
     * balance put back to precision never gains anything. A value within a few units in the last
     * place below a step is that step — noise, not a smaller amount.</p>
     *
     * @param value The value.
     * @return The value at this currency's precision, never above it but for that noise; {@code 0}
     *         for a value that is not a number.
     */
    public double floor(double value) {
        if (!Double.isFinite(value)) return 0;
        BigDecimal exact = BigDecimal.valueOf(value);
        BigDecimal down = exact.setScale(decimals, RoundingMode.FLOOR);
        BigDecimal next = down.add(step());
        if (next.subtract(exact).doubleValue() <= noise(value)) return next.doubleValue();
        return down.doubleValue();
    }

    /**
     * Brings a value up to this currency's precision: the smallest amount it can count that is not
     * below the value. For a price worked out from another currency, which must never be charged
     * for less than it is worth; the same noise as in {@link #floor(double)} is let go.
     *
     * @param value The value.
     * @return The value at this currency's precision, never below it but for that noise; {@code 0}
     *         for a value that is not a number.
     */
    public double ceil(double value) {
        if (!Double.isFinite(value)) return 0;
        BigDecimal exact = BigDecimal.valueOf(value);
        BigDecimal up = exact.setScale(decimals, RoundingMode.CEILING);
        BigDecimal previous = up.subtract(step());
        if (exact.subtract(previous).doubleValue() <= noise(value)) return previous.doubleValue();
        return up.doubleValue();
    }

    /**
     * The noise a double carries in its last bits, never more than a thousandth of a step: past a
     * few thousand billion, the last bits alone are worth a cent, and floor would have rounded up.
     */
    private double noise(double value) {
        return Math.min(NOISE_ULPS * Math.ulp(value), step().doubleValue() * 0.001);
    }

    /** @return The smallest amount this currency counts: {@code 0.01} with two decimals, {@code 1} with none. */
    public double unit() {
        return step().doubleValue();
    }

    private BigDecimal step() {
        return BigDecimal.ONE.movePointLeft(decimals);
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
