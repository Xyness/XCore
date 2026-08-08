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
import fr.xyness.XCore.API.DatabaseType;
import fr.xyness.XCore.API.XCoreApi;
import fr.xyness.XCore.API.XCoreApiProvider;
import fr.xyness.XCore.Database.SqlUtils;
import fr.xyness.XCore.Models.PlayerData;
import fr.xyness.XCore.Sync.SyncMessage;
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
    private static final java.util.regex.Pattern SAFE_COLUMN = java.util.regex.Pattern.compile("[a-zA-Z0-9_]+");

    private final XCore plugin;
    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private final Map<String, Double> exchangeRates = new HashMap<>();
    private Currency vaultCurrency;
    private boolean exchangeEnabled;
    private String columnSuffix;

    public CoinsManager(XCore plugin) {
        this.plugin = plugin;
        // Cross-server suffix
        boolean perServer = plugin.getConfig().getBoolean("economy.per-server-balances", false);
        String serverName = plugin.getConfig().getString("cross-server.server-name", "default");
        this.columnSuffix = perServer ? "_" + serverName : "";
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
     * Creates the xcore_transactions table if it doesn't exist.
     */
    private void initTransactionsTable() {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = api().getDataSource().getConnection()) {
                String autoInc = switch (api().getDatabaseType()) {
                    case MYSQL -> "INT AUTO_INCREMENT PRIMARY KEY";
                    case POSTGRESQL -> "SERIAL PRIMARY KEY";
                    case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
                };
                String engine = api().getDatabaseType() == DatabaseType.MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
                conn.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS xcore_transactions (" +
                    "id " + autoInc + ", " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "player_name VARCHAR(17) NOT NULL, " +
                    "currency VARCHAR(32) NOT NULL, " +
                    "amount DOUBLE PRECISION NOT NULL, " +
                    "type VARCHAR(20) NOT NULL, " +
                    "target_name VARCHAR(17), " +
                    "details TEXT, " +
                    "created_at TEXT NOT NULL" +
                    ")" + engine
                );
                SqlUtils.createIndexIfNotExists(conn, api().getDatabaseType(),
                    "idx_xcore_transactions_uuid", "xcore_transactions", "player_uuid");
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
                     "INSERT INTO xcore_transactions (player_uuid, player_name, currency, amount, type, target_name, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
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
                     "SELECT * FROM xcore_transactions WHERE player_name = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
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
                ? "SELECT COUNT(*) FROM xcore_transactions WHERE player_name = ? AND currency = ?"
                : "SELECT COUNT(*) FROM xcore_transactions WHERE player_name = ?";
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
                ? "SELECT * FROM xcore_transactions WHERE player_name = ? AND currency = ? ORDER BY id DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM xcore_transactions WHERE player_name = ? ORDER BY id DESC LIMIT ? OFFSET ?";
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

            double newAmount = finalAmount;
            boolean wasCapped = capped;
            return fireBalanceChangeEvent(playerId, currencyId, oldBalance, newAmount, type).thenCompose(cancelled -> {
                if (cancelled) {
                    return CompletableFuture.completedFuture(false);
                }
                return api().updatePlayerDataAsync(playerId, col(currencyId), currency.round(newAmount))
                    .thenApply(v -> wasCapped);
            });
        });
    }

    /**
     * Fires a {@link BalanceChangeEvent} on the main thread and returns a future that
     * completes with whether the event was cancelled.
     * <p>
     * Balance operations run asynchronously, but Bukkit events tied to gameplay must be
     * triggered synchronously. This bridges back to the global region thread for the
     * {@code callEvent} call, then resumes the async chain with the cancellation result.
     * </p>
     *
     * @return A future completing with {@code true} if a listener cancelled the event.
     */
    public CompletableFuture<Boolean> fireBalanceChangeEvent(UUID playerId, String currencyId, double oldBalance, double newBalance, BalanceChangeEvent.ChangeType type) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        plugin.schedulerAdapter().runGlobalTask(() -> {
            BalanceChangeEvent event = new BalanceChangeEvent(playerId, currencyId, oldBalance, newBalance, type);
            Bukkit.getPluginManager().callEvent(event);
            future.complete(event.isCancelled());
        });
        return future;
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

    /**
     * Resets a single player's balance back to the currency's starting balance.
     * Works for offline players: the UUID is all that is needed.
     *
     * @param playerId   The player's server UUID.
     * @param currencyId The currency to reset.
     * @return A future completing with the starting balance that was applied.
     */
    public CompletableFuture<Double> resetBalance(UUID playerId, String currencyId) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(0.0);
        double start = currency.getStartingBalance();
        return setBalanceWithEvent(playerId, currencyId, start, BalanceChangeEvent.ChangeType.SET)
            .thenApply(capped -> start);
    }

    /**
     * Resets the balance of <b>every</b> player — online and offline — back to the starting
     * balance of the given currencies, in a single bulk database statement.
     * <p>
     * Because this bypasses the per-player write path, all cache layers are dropped afterwards:
     * L1 locally, L2 (Redis), and L1 on the other servers through the {@code xcore} sync channel.
     * No {@link BalanceChangeEvent} is fired — the operation is not per-player.
     *
     * @param currencyIds The currencies to reset. Unknown IDs are ignored.
     * @return A future completing with the number of rows updated, or -1 on failure.
     */
    public CompletableFuture<Integer> resetAllBalances(Collection<String> currencyIds) {
        List<Currency> targets = new ArrayList<>();
        for (String id : currencyIds) {
            Currency currency = currencies.get(id);
            if (currency != null && !targets.contains(currency)) targets.add(currency);
        }
        if (targets.isEmpty()) return CompletableFuture.completedFuture(0);

        StringBuilder sql = new StringBuilder("UPDATE players SET ");
        for (int i = 0; i < targets.size(); i++) {
            String column = col(targets.get(i).getId());
            if (!SAFE_COLUMN.matcher(column).matches()) {
                logger().sendError("Refusing to reset balances: unsafe column name '" + column + "'.");
                return CompletableFuture.completedFuture(-1);
            }
            if (i > 0) sql.append(", ");
            sql.append(column).append(" = ?");
        }

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = api().getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < targets.size(); i++) {
                    Currency currency = targets.get(i);
                    ps.setDouble(i + 1, currency.round(currency.getStartingBalance()));
                }
                return ps.executeUpdate();
            } catch (SQLException e) {
                logger().sendError("Failed to reset all balances: " + e.getMessage());
                return -1;
            }
        }).thenApply(rows -> {
            if (rows >= 0) invalidateAllPlayerCaches();
            return rows;
        });
    }

    /**
     * Drops every cached copy of player data after a bulk write: L1 here, L2 (Redis),
     * and L1 on the other servers via the {@code xcore} sync channel.
     */
    private void invalidateAllPlayerCaches() {
        plugin.playerCache().clearRedis();
        plugin.clearAndWarmPlayerCache();
        if (plugin.getSyncManager() != null && plugin.getSyncManager().isRunning()) {
            plugin.getSyncManager().publish(XCore.SYNC_CHANNEL, new SyncMessage("CACHE_CLEAR", "players"));
        }
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
        boolean perServer = plugin.getConfig().getBoolean("economy.per-server-balances", false);
        String serverName = plugin.getConfig().getString("cross-server.server-name", "default");
        this.columnSuffix = perServer ? "_" + serverName : "";
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
