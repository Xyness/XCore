package fr.xyness.XCore.Economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.API.XCoreApi;
import fr.xyness.XCore.API.XCoreApiProvider;
import fr.xyness.XCore.Models.PlayerData;
import fr.xyness.XCore.Utils.Logger;

/**
 * Central manager for all currency operations.
 * <p>
 * Supports multiple currencies loaded from config.
 * Delegates storage and cross-server sync entirely to XCore.
 * When a balance is updated, XCore propagates the change to
 * the database and invalidates caches on other servers.
 * </p>
 */
public class CoinsManager {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final XCore plugin;
    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private final Map<String, Double> exchangeRates = new HashMap<>();
    private Currency vaultCurrency;
    private boolean exchangeEnabled;
    private String columnSuffix;

    public CoinsManager(XCore plugin) {
        this.plugin = plugin;
        // Cross-server suffix
        boolean crossServer = plugin.getConfig().getBoolean("economy.cross-server.enabled", false);
        String serverName = plugin.getConfig().getString("economy.cross-server.server-name", "default");
        this.columnSuffix = crossServer ? "" : "_" + serverName;
        loadCurrencies();
        loadExchangeRates();
        initTransactionsTable();
    }

    /**
     * Returns the database column name for a given currency ID, including any cross-server suffix.
     *
     * @param currencyId The currency ID.
     * @return The column name.
     */
    public String col(String currencyId) {
        return currencyId + columnSuffix;
    }

    private XCoreApi api() {
        return XCoreApiProvider.get();
    }

    private Logger logger() {
        return plugin.logger();
    }

    /**
     * Loads all currencies from the config {@code economy.currencies} section.
     */
    private void loadCurrencies() {
        currencies.clear();
        vaultCurrency = null;

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("economy.currencies");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            ConfigurationSection cs = section.getConfigurationSection(id);
            if (cs == null) continue;

            String symbol = cs.getString("symbol", "$");
            boolean symbolBefore = "BEFORE".equalsIgnoreCase(cs.getString("symbol-position", "BEFORE"));
            int decimals = cs.getInt("decimals", 2);
            double startingBalance = cs.getDouble("starting-balance", 0.00);
            boolean vault = cs.getBoolean("vault", false);
            double maxBalance = cs.getDouble("max-balance", 0);

            Currency currency = new Currency(id, symbol, symbolBefore, decimals, startingBalance, vault, maxBalance);
            currencies.put(id, currency);

            if (vault) {
                if (vaultCurrency != null) {
                    logger().sendError("Multiple currencies have vault: true. Only '" + vaultCurrency.getId() + "' will be used.");
                } else {
                    vaultCurrency = currency;
                }
            }
        }

        // Fallback: if no vault currency set, use the first one
        if (vaultCurrency == null && !currencies.isEmpty()) {
            vaultCurrency = currencies.values().iterator().next();
            logger().sendWarning("No currency has vault: true. Defaulting to '" + vaultCurrency.getId() + "'.");
        }
    }

    /**
     * Loads exchange rates from the config {@code economy.exchange} section.
     */
    private void loadExchangeRates() {
        exchangeRates.clear();
        exchangeEnabled = plugin.getConfig().getBoolean("economy.exchange.enabled", false);

        ConfigurationSection rates = plugin.getConfig().getConfigurationSection("economy.exchange.rates");
        if (rates == null) return;

        for (String key : rates.getKeys(false)) {
            exchangeRates.put(key, rates.getDouble(key));
        }
    }

    /**
     * Creates the xcoins_transactions table if it doesn't exist.
     */
    private void initTransactionsTable() {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = api().getDataSource().getConnection()) {
                conn.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS xcoins_transactions (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "player_name VARCHAR(17) NOT NULL, " +
                    "currency VARCHAR(32) NOT NULL, " +
                    "amount DOUBLE NOT NULL, " +
                    "type VARCHAR(20) NOT NULL, " +
                    "target_name VARCHAR(17), " +
                    "details TEXT, " +
                    "created_at TEXT NOT NULL" +
                    ")"
                );
                conn.createStatement().executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_xcoins_transactions_uuid ON xcoins_transactions (player_uuid)"
                );
            } catch (SQLException e) {
                logger().sendWarning("Failed to create transactions table: " + e.getMessage());
            }
        });
    }

    // ********************************
    // *  Transaction History methods  *
    // ********************************

    /**
     * Logs a transaction to the database.
     */
    public void logTransaction(UUID playerId, String playerName, String currency, double amount, String type, String targetName, String details) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = api().getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO xcoins_transactions (player_uuid, player_name, currency, amount, type, target_name, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, playerId.toString());
                ps.setString(2, playerName);
                ps.setString(3, currency);
                ps.setDouble(4, amount);
                ps.setString(5, type);
                ps.setString(6, targetName);
                ps.setString(7, details);
                ps.setString(8, LocalDateTime.now().format(DT_FORMAT));
                ps.executeUpdate();
            } catch (SQLException e) {
                logger().sendWarning("Failed to log transaction: " + e.getMessage());
            }
        });
    }

    /**
     * Fetches transaction history for a player (paginated).
     *
     * @param playerName The player name.
     * @param page       The page number (1-based).
     * @param limit      Entries per page.
     * @return A future containing the list of transactions.
     */
    public CompletableFuture<List<TransactionRecord>> getTransactions(String playerName, int page, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionRecord> records = new ArrayList<>();
            int offset = (page - 1) * limit;
            try (Connection conn = api().getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM xcoins_transactions WHERE player_name = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, playerName);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        records.add(new TransactionRecord(
                            rs.getInt("id"),
                            rs.getString("player_uuid"),
                            rs.getString("player_name"),
                            rs.getString("currency"),
                            rs.getDouble("amount"),
                            rs.getString("type"),
                            rs.getString("target_name"),
                            rs.getString("details"),
                            rs.getString("created_at")
                        ));
                    }
                }
            } catch (SQLException e) {
                logger().sendWarning("Failed to fetch transactions: " + e.getMessage());
            }
            return records;
        });
    }

    /**
     * Fetches total transaction count for a player.
     */
    public CompletableFuture<Integer> getTransactionCount(String playerName) {
        return getTransactionCount(playerName, null);
    }

    /**
     * Fetches total transaction count for a player, optionally filtered by currency.
     */
    public CompletableFuture<Integer> getTransactionCount(String playerName, String currency) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = currency != null
                ? "SELECT COUNT(*) FROM xcoins_transactions WHERE player_name = ? AND currency = ?"
                : "SELECT COUNT(*) FROM xcoins_transactions WHERE player_name = ?";
            try (Connection conn = api().getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName);
                if (currency != null) ps.setString(2, currency);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) {
                logger().sendWarning("Failed to count transactions: " + e.getMessage());
            }
            return 0;
        });
    }

    /**
     * Fetches transaction history for a player (paginated), optionally filtered by currency.
     */
    public CompletableFuture<List<TransactionRecord>> getTransactions(String playerName, String currency, int page, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionRecord> records = new ArrayList<>();
            int offset = (page - 1) * limit;
            String sql = currency != null
                ? "SELECT * FROM xcoins_transactions WHERE player_name = ? AND currency = ? ORDER BY id DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM xcoins_transactions WHERE player_name = ? ORDER BY id DESC LIMIT ? OFFSET ?";
            try (Connection conn = api().getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName);
                if (currency != null) {
                    ps.setString(2, currency);
                    ps.setInt(3, limit);
                    ps.setInt(4, offset);
                } else {
                    ps.setInt(2, limit);
                    ps.setInt(3, offset);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        records.add(new TransactionRecord(
                            rs.getInt("id"),
                            rs.getString("player_uuid"),
                            rs.getString("player_name"),
                            rs.getString("currency"),
                            rs.getDouble("amount"),
                            rs.getString("type"),
                            rs.getString("target_name"),
                            rs.getString("details"),
                            rs.getString("created_at")
                        ));
                    }
                }
            } catch (SQLException e) {
                logger().sendWarning("Failed to fetch transactions: " + e.getMessage());
            }
            return records;
        });
    }

    // ****************************
    // *  Exchange methods        *
    // ****************************

    public boolean isExchangeEnabled() { return exchangeEnabled; }

    /**
     * Gets the exchange rate for the given key (e.g. "coins-to-gems").
     *
     * @return The rate, or -1 if not found.
     */
    public double getExchangeRate(String from, String to) {
        String key = from + "-to-" + to;
        return exchangeRates.getOrDefault(key, -1.0);
    }

    // ****************************
    // *  Currency-aware methods  *
    // ****************************

    public double getBalance(UUID playerId, String currencyId) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return 0;
        Optional<PlayerData> opt = api().getPlayer(playerId);
        if (opt.isEmpty()) return currency.getStartingBalance();
        Double value = opt.get().getTargetData(col(currencyId), Double.class);
        return value != null ? value : currency.getStartingBalance();
    }

    public CompletableFuture<Double> getBalanceAsync(UUID playerId, String currencyId) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(0.0);
        return api().getPlayerAsync(playerId).thenApply(opt -> {
            if (opt.isEmpty()) return currency.getStartingBalance();
            Double value = opt.get().getTargetData(col(currencyId), Double.class);
            return value != null ? value : currency.getStartingBalance();
        });
    }

    public CompletableFuture<Double> getBalanceAsync(String name, String currencyId) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(0.0);
        return api().getPlayerAsync(name).thenApply(opt -> {
            if (opt.isEmpty()) return currency.getStartingBalance();
            Double value = opt.get().getTargetData(col(currencyId), Double.class);
            return value != null ? value : currency.getStartingBalance();
        });
    }

    public CompletableFuture<Void> setBalance(UUID playerId, String currencyId, double amount) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(null);
        if (currency.getMaxBalance() > 0) {
            amount = Math.min(amount, currency.getMaxBalance());
        }
        return api().updatePlayerDataAsync(playerId, col(currencyId), currency.round(amount));
    }

    /**
     * Sets a balance with event firing and optional cap notification.
     *
     * @return A future containing true if the balance was capped, false otherwise.
     */
    public CompletableFuture<Boolean> setBalanceWithEvent(UUID playerId, String currencyId, double amount, BalanceChangeEvent.ChangeType type) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(false);

        return getBalanceAsync(playerId, currencyId).thenCompose(oldBalance -> {
            double finalAmount = amount;
            boolean capped = false;
            if (currency.getMaxBalance() > 0 && finalAmount > currency.getMaxBalance()) {
                finalAmount = currency.getMaxBalance();
                capped = true;
            }

            BalanceChangeEvent event = new BalanceChangeEvent(playerId, currencyId, oldBalance, finalAmount, type);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return CompletableFuture.completedFuture(false);
            }

            boolean wasCapped = capped;
            return api().updatePlayerDataAsync(playerId, col(currencyId), currency.round(finalAmount))
                .thenApply(v -> wasCapped);
        });
    }

    public CompletableFuture<Void> addBalance(UUID playerId, String currencyId, double amount) {
        return getBalanceAsync(playerId, currencyId)
            .thenCompose(current -> setBalance(playerId, currencyId, current + amount));
    }

    public CompletableFuture<Void> removeBalance(UUID playerId, String currencyId, double amount) {
        return getBalanceAsync(playerId, currencyId)
            .thenCompose(current -> setBalance(playerId, currencyId, Math.max(0, current - amount)));
    }

    public boolean has(UUID playerId, String currencyId, double amount) {
        return getBalance(playerId, currencyId) >= amount;
    }

    public String format(String currencyId, double amount) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return String.valueOf(amount);
        return currency.format(amount);
    }

    // ******************************************
    // *  Backward-compatible (vault currency)  *
    // ******************************************

    public double getBalance(UUID playerId) {
        if (vaultCurrency == null) return 0;
        return getBalance(playerId, vaultCurrency.getId());
    }

    public CompletableFuture<Double> getBalanceAsync(UUID playerId) {
        if (vaultCurrency == null) return CompletableFuture.completedFuture(0.0);
        return getBalanceAsync(playerId, vaultCurrency.getId());
    }

    public CompletableFuture<Double> getBalanceAsync(String name) {
        if (vaultCurrency == null) return CompletableFuture.completedFuture(0.0);
        return getBalanceAsync(name, vaultCurrency.getId());
    }

    public CompletableFuture<Void> setBalance(UUID playerId, double amount) {
        if (vaultCurrency == null) return CompletableFuture.completedFuture(null);
        return setBalance(playerId, vaultCurrency.getId(), amount);
    }

    public CompletableFuture<Void> addBalance(UUID playerId, double amount) {
        if (vaultCurrency == null) return CompletableFuture.completedFuture(null);
        return addBalance(playerId, vaultCurrency.getId(), amount);
    }

    public CompletableFuture<Void> removeBalance(UUID playerId, double amount) {
        if (vaultCurrency == null) return CompletableFuture.completedFuture(null);
        return removeBalance(playerId, vaultCurrency.getId(), amount);
    }

    public boolean has(UUID playerId, double amount) {
        if (vaultCurrency == null) return false;
        return has(playerId, vaultCurrency.getId(), amount);
    }

    public String format(double amount) {
        if (vaultCurrency == null) return String.valueOf(amount);
        return format(vaultCurrency.getId(), amount);
    }

    // *********************
    // *  Currency access  *
    // *********************

    public Currency getCurrency(String id) {
        return currencies.get(id);
    }

    public Collection<Currency> getCurrencies() {
        return Collections.unmodifiableCollection(currencies.values());
    }

    public Currency getVaultCurrency() {
        return vaultCurrency;
    }

    public double getStartingBalance() {
        return vaultCurrency != null ? vaultCurrency.getStartingBalance() : 0;
    }

    public String getSymbol() {
        return vaultCurrency != null ? vaultCurrency.getSymbol() : "$";
    }

    public int getDecimals() {
        return vaultCurrency != null ? vaultCurrency.getDecimals() : 2;
    }

    /**
     * Reloads all currencies and exchange rates from the config.
     */
    public void reload() {
        // Re-read cross-server suffix
        boolean crossServer = plugin.getConfig().getBoolean("economy.cross-server.enabled", false);
        String serverName = plugin.getConfig().getString("economy.cross-server.server-name", "default");
        this.columnSuffix = crossServer ? "" : "_" + serverName;
        loadCurrencies();
        loadExchangeRates();
    }

    // *************************
    // *  Transaction Record   *
    // *************************

    /**
     * Simple record for a transaction entry.
     */
    public record TransactionRecord(int id, String playerUuid, String playerName, String currency, double amount,
                                    String type, String targetName, String details, String createdAt) {}
}
