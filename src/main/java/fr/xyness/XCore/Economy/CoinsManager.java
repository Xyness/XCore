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
import java.util.Objects;
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

    /**
     * Reçoit ce qui dépasse le plafond de solde, au lieu de le laisser disparaître.
     *
     * <p>Sans ce crochet, un gain qui fait franchir {@code max-balance} était simplement rogné :
     * l'argent était détruit en silence, et Vault répondait « versement réussi » au plugin
     * appelant, qui journalisait donc un paiement que le joueur n'a jamais reçu. Un addon —
     * XTools et sa banque — se déclare ici pour absorber le surplus.
     *
     * <p>Volontairement synchrone et sans dépendance : le cœur ignore ce qu'est une banque.
     */
    public interface OverflowHandler {
        /**
         * @param surplus la part qui ne tient pas dans la poche, strictement positive
         * @return la part réellement absorbée ; le reste sera rogné comme avant
         */
        double absorb(java.util.UUID playerId, String currencyId, double surplus);
    }

    private volatile OverflowHandler overflowHandler;

    /** Déclare le récepteur du surplus. {@code null} rétablit l'écrêtage sec. */
    public void setOverflowHandler(OverflowHandler handler) {
        this.overflowHandler = handler;
    }

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.util.regex.Pattern SAFE_COLUMN = java.util.regex.Pattern.compile("[a-zA-Z0-9_]+");

    /**
     * Number of stripes guarding balance mutations.
     *
     * <p>Every read-modify-write on a balance runs under the stripe of its player UUID, so two
     * concurrent operations on the same player are serialized instead of racing (one of them
     * reading a balance the other is about to overwrite — which creates or destroys money). The
     * database statements are conditional on top of that, which covers the cross-server case the
     * stripe cannot see.</p>
     */
    private static final int LOCK_STRIPES = 64;

    /** Short-lived fallback for balances read straight from the database on an L1 miss. */
    private static final long DB_FALLBACK_TTL_MS = 30_000L;

    private final XCore plugin;
    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private final Map<String, Double> exchangeRates = new HashMap<>();
    private final Object[] locks = new Object[LOCK_STRIPES];
    private final Map<String, double[]> dbFallback = new java.util.concurrent.ConcurrentHashMap<>();
    private Currency vaultCurrency;
    private boolean exchangeEnabled;
    private String columnSuffix;

    public CoinsManager(XCore plugin) {
        this.plugin = plugin;
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
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
            // NOTE: the body below runs on the plugin executor (see the trailing argument).
            try (Connection conn = api().getDataSource().getConnection()) {
                String autoInc = switch (api().getDatabaseType()) {
                    case MYSQL -> "INT AUTO_INCREMENT PRIMARY KEY";
                    case POSTGRESQL -> "SERIAL PRIMARY KEY";
                    case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
                };
                String engine = api().getDatabaseType() == DatabaseType.MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate(
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
                }
                SqlUtils.createIndexIfNotExists(conn, api().getDatabaseType(),
                    "idx_xcore_transactions_uuid", "xcore_transactions", "player_uuid");
                // The history is read by UUID, but the name-based fallback still needs to stay off
                // a full table scan for players whose UUID cannot be resolved.
                SqlUtils.createIndexIfNotExists(conn, api().getDatabaseType(),
                    "idx_xcore_transactions_name", "xcore_transactions", "player_name");
            } catch (SQLException e) {
                logger().sendWarning("Failed to create transactions table: " + e.getMessage());
            }
        }, plugin.getExecutor());
    }

    /**
     * Resolves a player name to the server UUID recorded in the {@code players} table.
     *
     * <p>Transaction history is keyed on the UUID, so a player who renames keeps their history and
     * whoever takes the freed name does not inherit it.</p>
     *
     * @return The UUID string, or {@code null} when the name is unknown.
     */
    private String resolveUuid(String playerName) {
        Optional<PlayerData> cached = api().getPlayer(playerName);
        if (cached.isPresent()) return cached.get().getUuid().toString();
        try (Connection conn = api().getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT server_uuid FROM players WHERE LOWER(player_name) = LOWER(?)")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
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
        }, plugin.getExecutor());
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
        return getTransactions(playerName, null, page, limit);
    }

    /**
     * Builds the owner predicate for a history query: by UUID when the name resolves, by name
     * otherwise (a player who only ever appears in the transaction log).
     *
     * @return {@code {"player_uuid = ?", value}} or {@code {"player_name = ?", value}}.
     */
    private String[] ownerPredicate(String playerName) {
        String uuid = resolveUuid(playerName);
        return uuid != null
            ? new String[]{"player_uuid = ?", uuid}
            : new String[]{"player_name = ?", playerName};
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
            String[] owner = ownerPredicate(playerName);
            String sql = "SELECT COUNT(*) FROM xcore_transactions WHERE " + owner[0]
                + (currency != null ? " AND currency = ?" : "");
            try (Connection conn = api().getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, owner[1]);
                if (currency != null) ps.setString(2, currency);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) {
                logger().sendWarning("Failed to count transactions: " + e.getMessage());
            }
            return 0;
        }, plugin.getExecutor());
    }

    /**
     * Fetches transaction history for a player (paginated), optionally filtered by currency.
     */
    public CompletableFuture<List<TransactionRecord>> getTransactions(String playerName, String currency, int page, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionRecord> records = new ArrayList<>();
            int offset = Math.max(0, (page - 1) * limit);
            String[] owner = ownerPredicate(playerName);
            String sql = "SELECT * FROM xcore_transactions WHERE " + owner[0]
                + (currency != null ? " AND currency = ?" : "")
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
            try (Connection conn = api().getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, owner[1]);
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
        }, plugin.getExecutor());
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

    /**
     * Reads a balance without going to the database.
     *
     * <p>Returns {@code null} when the player is not in the L1 cache <b>and</b> has no fresh
     * database fallback — the caller must then decide whether to pay for a database read. This
     * distinction matters: reporting the starting balance for an unknown player (which is what
     * this method used to do) makes {@code has()} approve purchases nobody can afford.</p>
     *
     * @param playerId   The player's server UUID.
     * @param currencyId The currency.
     * @return The balance, or {@code null} when it is genuinely unknown.
     */
    private Double peekBalance(UUID playerId, String currencyId) {
        Optional<PlayerData> opt = api().getPlayer(playerId);
        if (opt.isPresent()) {
            Double value = opt.get().getTargetData(col(currencyId), Double.class);
            if (value != null) return value;
        }
        double[] cached = dbFallback.get(playerId + ":" + currencyId);
        if (cached != null && System.currentTimeMillis() - (long) cached[1] < DB_FALLBACK_TTL_MS) {
            return cached[0];
        }
        return null;
    }

    /** Stores a database-sourced balance in the short-lived fallback cache. */
    private void rememberBalance(UUID playerId, String currencyId, double value) {
        dbFallback.put(playerId + ":" + currencyId, new double[]{value, System.currentTimeMillis()});
    }

    /**
     * Reads a single balance column straight from the database.
     *
     * @return The stored value, or {@code null} when the row or the column has none.
     */
    private Double readColumnFromDb(UUID playerId, String column) {
        if (!SAFE_COLUMN.matcher(column).matches()) return null;
        try (Connection conn = api().getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM players WHERE server_uuid = ?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                double value = rs.getDouble(1);
                return rs.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            logger().sendWarning("Failed to read balance column '" + column + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Pushes an authoritative balance back into every cache layer that already knows the player.
     */
    private void refreshCachedBalance(UUID playerId, String currencyId, double value) {
        rememberBalance(playerId, currencyId, value);
        Optional<PlayerData> opt = api().getPlayer(playerId);
        if (opt.isPresent()) {
            opt.get().setTargetData(col(currencyId), value);
            plugin.playerCache().addOrUpdateToCache(opt.get());
        }
    }

    /**
     * Returns a player's balance.
     *
     * <p>The L1 cache answers whenever it can; otherwise the value is read from the database and
     * memoised briefly. An offline or evicted player therefore reports their <b>real</b> balance,
     * not the currency's starting balance.</p>
     *
     * @param playerId   The player's server UUID.
     * @param currencyId The currency.
     * @return The balance, or the currency's starting balance when the player has no row at all.
     */
    public double getBalance(UUID playerId, String currencyId) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return 0;

        Double cached = peekBalance(playerId, currencyId);
        if (cached != null) return cached;

        Double fromDb = readColumnFromDb(playerId, col(currencyId));
        if (fromDb == null) return currency.getStartingBalance();
        rememberBalance(playerId, currencyId, fromDb);
        return fromDb;
    }

    public CompletableFuture<Double> getBalanceAsync(UUID playerId, String currencyId) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(0.0);
        return api().getPlayerAsync(playerId).thenApply(opt -> {
            if (opt.isPresent()) {
                Double value = opt.get().getTargetData(col(currencyId), Double.class);
                if (value != null) return value;
            }
            Double fromDb = readColumnFromDb(playerId, col(currencyId));
            if (fromDb == null) return currency.getStartingBalance();
            rememberBalance(playerId, currencyId, fromDb);
            return fromDb;
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
        double target = currency.getMaxBalance() > 0
            ? Math.min(amount, currency.getMaxBalance())
            : amount;
        double rounded = currency.round(target);
        return CompletableFuture.runAsync(() -> {
            synchronized (lockFor(playerId)) {
                api().updatePlayerDataAsync(playerId, col(currencyId), rounded).join();
                rememberBalance(playerId, currencyId, rounded);
            }
        }, plugin.getExecutor());
    }

    /** The stripe guarding every balance mutation for this player. */
    private Object lockFor(UUID playerId) {
        return locks[Math.floorMod(playerId.hashCode(), LOCK_STRIPES)];
    }

    /**
     * Applies a signed delta to a balance atomically.
     *
     * <p>The arithmetic happens inside the database ({@code col = col + ?}) rather than in Java, so
     * two servers hitting the same row cannot both read the old value and write back a total that
     * silently drops the other's change. A withdrawal additionally guards on {@code col >= amount},
     * which is what makes "insufficient funds" a real answer instead of a silent clamp to zero.</p>
     *
     * <p>After the update the value is read back, rounded and capped, and pushed into the caches.
     * Any part of a deposit that does not fit under {@code max-balance} is offered to the
     * {@link OverflowHandler} before being trimmed.</p>
     *
     * @param playerId     The player's server UUID.
     * @param currencyId   The currency.
     * @param delta        The signed amount to apply.
     * @param requireFunds {@code true} to refuse the operation when the balance would go negative.
     * @return A future completing with the new balance, or {@code null} when the operation was
     *         refused (insufficient funds) or could not be applied.
     */
    private CompletableFuture<Double> applyDelta(UUID playerId, String currencyId, double delta, boolean requireFunds) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(null);
        String column = col(currencyId);
        if (!SAFE_COLUMN.matcher(column).matches()) {
            logger().sendError("Refusing balance operation: unsafe column name '" + column + "'.");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            synchronized (lockFor(playerId)) {
                double normalised;
                double surplus = 0;

                // One connection for the whole operation. Nesting a second borrow inside the first
                // would deadlock the SQLite pool, which only holds two.
                try (Connection conn = api().getDataSource().getConnection()) {
                    // COALESCE: a currency column added to an existing table can be NULL on rows
                    // written before it existed, and NULL + n is NULL.
                    String guarded = "COALESCE(" + column + ", 0)";
                    String sql = requireFunds
                        ? "UPDATE players SET " + column + " = " + guarded + " - ? WHERE server_uuid = ? AND " + guarded + " >= ?"
                        : "UPDATE players SET " + column + " = " + guarded + " + ? WHERE server_uuid = ?";
                    int rows;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        if (requireFunds) {
                            ps.setDouble(1, -delta);          // delta is negative on a withdrawal
                            ps.setString(2, playerId.toString());
                            ps.setDouble(3, -delta);
                        } else {
                            ps.setDouble(1, delta);
                            ps.setString(2, playerId.toString());
                        }
                        rows = ps.executeUpdate();
                    }
                    if (rows == 0) {
                        // Either the funds guard rejected it, or the player has no row yet.
                        return null;
                    }

                    double applied;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT " + guarded + " FROM players WHERE server_uuid = ?")) {
                        ps.setString(1, playerId.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) return null;
                            applied = rs.getDouble(1);
                        }
                    }

                    // Normalise: round to the currency precision, then cap.
                    normalised = currency.round(applied);
                    double max = currency.getMaxBalance();
                    if (max > 0 && normalised > max) {
                        surplus = normalised - max;
                        normalised = max;
                    }
                    if (normalised != applied) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE players SET " + column + " = ? WHERE server_uuid = ?")) {
                            ps.setDouble(1, normalised);
                            ps.setString(2, playerId.toString());
                            ps.executeUpdate();
                        }
                    }
                } catch (SQLException e) {
                    logger().sendError("Balance update failed for " + playerId + ": " + e.getMessage());
                    return null;
                }

                refreshCachedBalance(playerId, currencyId, normalised);

                // The surplus is offered to whoever registered to catch it (XTools' bank) before
                // being trimmed. Never at the cost of the payment itself.
                if (surplus > 0) {
                    OverflowHandler handler = overflowHandler;
                    if (handler != null) {
                        try {
                            handler.absorb(playerId, currencyId, surplus);
                        } catch (Throwable throwable) {
                            logger().sendWarning("Débordement de solde non absorbé pour " + playerId
                                    + " : " + throwable.getClass().getSimpleName());
                        }
                    }
                }
                return normalised;
            }
        }, plugin.getExecutor());
    }

    /**
     * Takes an amount from a player, refusing the operation when the funds are not there.
     *
     * <p>Prefer this over {@link #removeBalance(UUID, String, double)} whenever the caller acts on
     * the outcome — handing over an item, confirming a purchase — because it is the only variant
     * that can tell you the money was actually collected.</p>
     *
     * @return A future completing with {@code true} when the full amount was withdrawn.
     */
    public CompletableFuture<Boolean> withdraw(UUID playerId, String currencyId, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(true);
        return applyDelta(playerId, currencyId, -amount, true).thenApply(Objects::nonNull);
    }

    /**
     * Adds an amount to a player's balance atomically.
     *
     * @return A future completing with the new balance, or {@code null} if it could not be applied.
     */
    public CompletableFuture<Double> deposit(UUID playerId, String currencyId, double amount) {
        if (amount <= 0) return getBalanceAsync(playerId, currencyId);
        return applyDelta(playerId, currencyId, amount, false);
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

    /**
     * Adds an amount to a player's balance. Kept returning {@code Void} for the addons that already
     * chain on it; {@link #deposit(UUID, String, double)} exposes the resulting balance.
     */
    public CompletableFuture<Void> addBalance(UUID playerId, String currencyId, double amount) {
        return deposit(playerId, currencyId, amount).thenApply(v -> null);
    }

    /**
     * Removes an amount from a player's balance.
     *
     * <p>Retained for callers that cannot act on a failure. It first attempts a real withdrawal;
     * when the funds fall short it empties the balance instead, matching the historical clamp —
     * destroying money is recoverable, creating it is not. New code should call
     * {@link #withdraw(UUID, String, double)} and honour its result.</p>
     */
    public CompletableFuture<Void> removeBalance(UUID playerId, String currencyId, double amount) {
        return withdraw(playerId, currencyId, amount).thenCompose(ok -> {
            if (ok) return CompletableFuture.<Void>completedFuture(null);
            return getBalanceAsync(playerId, currencyId).thenCompose(current ->
                current > 0
                    ? setBalance(playerId, currencyId, 0.0)
                    : CompletableFuture.<Void>completedFuture(null));
        });
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
        }, plugin.getExecutor()).thenApply(rows -> {
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

    /** Withdraws from the Vault currency, reporting whether the funds were actually there. */
    public CompletableFuture<Boolean> withdraw(UUID playerId, double amount) {
        if (vaultCurrency == null) return CompletableFuture.completedFuture(false);
        return withdraw(playerId, vaultCurrency.getId(), amount);
    }

    /** Deposits into the Vault currency, returning the resulting balance. */
    public CompletableFuture<Double> deposit(UUID playerId, double amount) {
        if (vaultCurrency == null) return CompletableFuture.completedFuture(null);
        return deposit(playerId, vaultCurrency.getId(), amount);
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
