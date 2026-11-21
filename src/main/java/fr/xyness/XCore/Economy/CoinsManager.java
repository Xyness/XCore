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

    /** How long a balance seen here is taken as current: past it, the database is asked again. */
    private static final long FRESH_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    /** How long a player waits before their balances are read in the background again. */
    private static final java.time.Duration READ_SPACING = java.time.Duration.ofSeconds(5);

    private final XCore plugin;
    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private final Map<String, Double> exchangeRates = new HashMap<>();
    private final Object[] locks = new Object[LOCK_STRIPES];

    /**
     * A balance as this server last saw it: read from the database, or left by an operation made here.
     *
     * @param value The balance.
     * @param at    When, on the {@link System#nanoTime()} clock. A read counts from the moment it was
     *              sent, so it never replaces a value an operation wrote while it was on its way.
     */
    private record Known(double value, long at) {
        boolean fresh(long now) {
            return now - at < FRESH_NANOS;
        }
    }

    /**
     * The balances of the players connected to this server, by player then currency: read when they
     * join, followed by every operation made here, dropped when they leave.
     *
     * <p>Their balances are asked for on the server thread all the time — a menu deciding what its
     * viewer can afford, a scoreboard, Vault's {@code getBalance}. The player cache alone could not
     * promise an answer: its copy of a player expires when nothing reads it, and goes whenever
     * another server writes the row, and the next read went to the database on the thread that
     * asked.</p>
     */
    private final Map<UUID, Map<String, Known>> online = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The balances of everyone else this server has read or changed lately, by {@code uuid:currency}.
     *
     * <p>Bounded and self-expiring. It used to be a plain map with the age checked on read and
     * nothing ever removing an entry: one line per (player, currency) ever queried, kept for the
     * lifetime of the server — a slow leak that only showed after days of uptime. An entry outlives
     * its freshness on purpose: a server thread asking again is answered with it while it is read
     * anew in the background, instead of with nothing.</p>
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Known> remembered =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(20_000)
                    .expireAfterWrite(java.time.Duration.ofMinutes(5))
                    .build();

    /**
     * The players whose balances were sent for in the background moments ago. A server thread asks on
     * every frame of a menu or a scoreboard: one read is enough, and a database that cannot answer
     * must not be asked twenty times a second.
     */
    private final com.github.benmanes.caffeine.cache.Cache<UUID, Boolean> reading =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(READ_SPACING)
                    .build();

    /** Transactions waiting to be written, flushed as one batch. */
    private final java.util.Queue<Object[]> pendingTransactions = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private Currency vaultCurrency;
    private boolean exchangeEnabled;
    private String columnSuffix;

    public CoinsManager(XCore plugin) {
        this.plugin = plugin;
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
        // Cross-server suffix
        boolean perServer = plugin.getConfig().getBoolean("economy.per-server-balances", false);
        String serverName = plugin.getServerName();
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
        }, plugin.getDbExecutor());
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
        // Queued rather than written on the spot: a busy shop turns every purchase into its own
        // connection borrow, statement preparation and round trip. The flush below writes them as a
        // single batch, and the timestamp is taken here so the order and the times stay exact.
        pendingTransactions.add(new Object[]{
            playerId.toString(), playerName, currency, amount, type, targetName, details,
            LocalDateTime.now().format(DT_FORMAT)
        });
        if (pendingTransactions.size() >= 200) flushTransactions();
    }

    /**
     * Writes every queued transaction as one batch.
     *
     * <p>Called on a timer, when the queue grows past its threshold, and on shutdown.</p>
     */
    public void flushTransactions() {
        if (pendingTransactions.isEmpty()) return;
        java.util.List<Object[]> batch = new ArrayList<>();
        for (Object[] row = pendingTransactions.poll(); row != null; row = pendingTransactions.poll()) {
            batch.add(row);
            if (batch.size() >= 500) break;
        }
        if (batch.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try (Connection conn = api().getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO xcore_transactions (player_uuid, player_name, currency, amount, type, target_name, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Object[] row : batch) {
                    ps.setString(1, (String) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setDouble(4, (Double) row[3]);
                    ps.setString(5, (String) row[4]);
                    ps.setString(6, (String) row[5]);
                    ps.setString(7, (String) row[6]);
                    ps.setString(8, (String) row[7]);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                logger().sendWarning("Failed to log " + batch.size() + " transaction(s): " + e.getMessage());
            }
        }, plugin.getDbExecutor());
    }

    /**
     * Credits the same amount to a set of players in a single statement.
     *
     * <p>The scheduled payout used to walk the online players and issue one full balance operation
     * each — three statements per player, per payout. This is one {@code UPDATE}, one read-back, and
     * the caches refreshed from it.</p>
     *
     * <p>The amount is brought down to the currency's precision first, like every other amount
     * ({@link #amountOf(String, double)}); one that comes to less than the currency can count
     * credits nobody. Each balance is then settled as a single deposit's is ({@link #settle}).</p>
     *
     * @param playerIds  Who to credit.
     * @param currencyId The currency.
     * @param amount     The amount to add to each balance.
     * @return A future completing with the resulting balance of each player that was updated.
     */
    public CompletableFuture<Map<UUID, Double>> depositAll(Collection<UUID> playerIds, String currencyId, double amount) {
        Map<UUID, Double> empty = Map.of();
        Currency currency = currencies.get(currencyId);
        double given = amountOf(currencyId, amount);
        if (currency == null || given <= 0 || playerIds == null || playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(empty);
        }
        String column = col(currencyId);
        if (!SAFE_COLUMN.matcher(column).matches()) {
            logger().sendError("Refusing bulk balance operation: unsafe column name '" + column + "'.");
            return CompletableFuture.completedFuture(empty);
        }
        List<UUID> targets = new ArrayList<>(playerIds);

        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, Double> results = new HashMap<>();
            Map<UUID, Double> surpluses = new HashMap<>();
            String placeholders = String.join(",", java.util.Collections.nCopies(targets.size(), "?"));
            try (Connection conn = api().getDataSource().getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET " + column + " = COALESCE(" + column + ", 0) + ?"
                        + " WHERE server_uuid IN (" + placeholders + ")")) {
                    ps.setDouble(1, given);
                    for (int i = 0; i < targets.size(); i++) ps.setString(i + 2, targets.get(i).toString());
                    ps.executeUpdate();
                }
                // Read back whole before settling anything: SQLite does not take a write on a table
                // while a read of it is still open on the same connection.
                Map<UUID, Double> applied = new HashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT server_uuid, COALESCE(" + column + ", 0) FROM players"
                        + " WHERE server_uuid IN (" + placeholders + ")")) {
                    for (int i = 0; i < targets.size(); i++) ps.setString(i + 1, targets.get(i).toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) applied.put(UUID.fromString(rs.getString(1)), rs.getDouble(2));
                    }
                }
                for (Map.Entry<UUID, Double> entry : applied.entrySet()) {
                    Settled settled = settle(conn, currency, column, entry.getKey(), entry.getValue());
                    results.put(entry.getKey(), settled.balance());
                    if (settled.surplus() > 0) surpluses.put(entry.getKey(), settled.surplus());
                }
            } catch (SQLException e) {
                logger().sendError("Bulk balance operation failed: " + e.getMessage());
                return empty;
            }
            results.forEach((id, value) -> refreshCachedBalance(id, currencyId, value));
            surpluses.forEach((id, surplus) -> offerSurplus(id, currencyId, surplus));
            return results;
        }, plugin.getDbExecutor());
    }

    /**
     * What an interest payment did for one player.
     *
     * @param amount  What it added to the balance (taken from it, for a negative rate).
     * @param balance The balance it left.
     */
    public record Credit(double amount, double balance) {}

    /**
     * Pays interest to a set of players.
     *
     * @param playerIds  Who to credit.
     * @param currencyId The currency.
     * @param rate       The interest rate ({@code 0.01} adds one percent).
     * @return A future completing with the resulting balance of each player that was paid.
     * @see #creditInterest(Collection, String, double)
     */
    public CompletableFuture<Map<UUID, Double>> applyInterest(Collection<UUID> playerIds, String currencyId, double rate) {
        return creditInterest(playerIds, currencyId, rate).thenApply(credits -> {
            Map<UUID, Double> balances = new HashMap<>();
            credits.forEach((id, credit) -> balances.put(id, credit.balance()));
            return balances;
        });
    }

    /**
     * Pays interest to a set of players, and says what each one received.
     *
     * <p>The interest of each player is an amount like any other: worked out from their balance,
     * brought down to the currency's precision, and added. It used to be a multiplication made by
     * the database, whose result was left off precision there while the caches were shown it
     * rounded to the nearest. Each balance is written only if it is still the one the interest was
     * worked out from: a player whose balance changed meanwhile is paid at the next interval.</p>
     *
     * @param playerIds  Who to credit.
     * @param currencyId The currency.
     * @param rate       The interest rate ({@code 0.01} adds one percent; a negative rate takes).
     * @return A future completing with what each player paid was credited and left with.
     */
    public CompletableFuture<Map<UUID, Credit>> creditInterest(Collection<UUID> playerIds, String currencyId, double rate) {
        Map<UUID, Credit> empty = Map.of();
        Currency currency = currencies.get(currencyId);
        if (currency == null || playerIds == null || playerIds.isEmpty() || !Double.isFinite(rate) || rate == 0) {
            return CompletableFuture.completedFuture(empty);
        }
        String column = col(currencyId);
        if (!SAFE_COLUMN.matcher(column).matches()) {
            logger().sendError("Refusing bulk balance operation: unsafe column name '" + column + "'.");
            return CompletableFuture.completedFuture(empty);
        }
        List<UUID> targets = new ArrayList<>(playerIds);

        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, Credit> credits = new HashMap<>();
            Map<UUID, Double> surpluses = new HashMap<>();
            String placeholders = String.join(",", java.util.Collections.nCopies(targets.size(), "?"));
            try (Connection conn = api().getDataSource().getConnection()) {
                Map<UUID, Double> before = new LinkedHashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT server_uuid, " + column + " FROM players WHERE server_uuid IN (" + placeholders + ")")) {
                    for (int i = 0; i < targets.size(); i++) ps.setString(i + 1, targets.get(i).toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            double value = rs.getDouble(2);
                            // Interest on what is there, none on an empty or negative balance.
                            if (rs.wasNull() || !(value > 0)) continue;
                            before.put(UUID.fromString(rs.getString(1)), value);
                        }
                    }
                }
                double max = cap(currency);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET " + column + " = ? WHERE server_uuid = ? AND " + column + " = ?")) {
                    for (Map.Entry<UUID, Double> entry : before.entrySet()) {
                        double stored = entry.getValue();
                        // A balance stored off precision earns on what counts of it.
                        double held = currency.floor(stored);
                        double owed = held * rate;
                        // Down for what is credited; down in size for what a negative rate takes.
                        double amount = owed >= 0 ? currency.floor(owed) : -Math.min(held, currency.floor(-owed));
                        if (amount == 0) continue;
                        // Settled as a deposit is: over the cap, the balance stops at it and what is
                        // above is offered on, not kept as well.
                        double after = currency.floor(held + amount);
                        double surplus = 0;
                        if (max > 0 && after > max) {
                            surplus = currency.floor(after - max);
                            after = max;
                        }
                        ps.setDouble(1, after);
                        ps.setString(2, entry.getKey().toString());
                        ps.setDouble(3, stored);
                        if (ps.executeUpdate() == 0) continue;
                        credits.put(entry.getKey(), new Credit(currency.floor(after - held), after));
                        if (surplus > 0) surpluses.put(entry.getKey(), surplus);
                    }
                }
            } catch (SQLException e) {
                logger().sendError("Interest payment failed: " + e.getMessage());
                return credits;
            }
            credits.forEach((id, credit) -> refreshCachedBalance(id, currencyId, credit.balance()));
            surpluses.forEach((id, surplus) -> offerSurplus(id, currencyId, surplus));
            return credits;
        }, plugin.getDbExecutor());
    }

    /**
     * Offers what did not fit under the cap to whoever registered to catch it, before it is trimmed.
     *
     * @param playerId   The player.
     * @param currencyId The currency.
     * @param surplus    The part that did not fit, strictly positive.
     */
    private void offerSurplus(UUID playerId, String currencyId, double surplus) {
        OverflowHandler handler = overflowHandler;
        if (handler == null) return;
        try {
            handler.absorb(playerId, currencyId, surplus);
        } catch (Throwable throwable) {
            logger().sendWarning("The part of a balance over the cap was not taken in for " + playerId
                    + " : " + throwable.getClass().getSimpleName());
        }
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
        }, plugin.getDbExecutor());
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
        }, plugin.getDbExecutor());
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
     * A balance as the player cache holds it, in the player's row.
     *
     * @return The balance, or {@code null} when the cache does not have the player or the column.
     */
    private Double cachedBalance(UUID playerId, String currencyId) {
        Optional<PlayerData> opt = api().getPlayer(playerId);
        return opt.isPresent() ? opt.get().getTargetData(col(currencyId), Double.class) : null;
    }

    /**
     * A balance as this server last saw it, whatever its age.
     *
     * @return The more recent of the connected player's copy and the remembered one, or {@code null}
     *         when this server has seen neither.
     */
    private Known known(UUID playerId, String currencyId) {
        Map<String, Known> wallet = online.get(playerId);
        Known held = wallet == null ? null : wallet.get(currencyId);
        Known seen = remembered.getIfPresent(playerId + ":" + currencyId);
        if (held == null) return seen;
        return seen == null ? held : newer(held, seen);
    }

    /**
     * Takes note of a balance seen at a given moment.
     *
     * <p>A value already there and more recent is kept: a read sent before an operation finished
     * cannot bring the old balance back after it.</p>
     *
     * @param at When the value was true, on the {@link System#nanoTime()} clock: when the read was
     *           sent, or when the operation that left it finished.
     */
    private void learn(UUID playerId, String currencyId, double value, long at) {
        Known seen = new Known(value, at);
        Map<String, Known> wallet = online.get(playerId);
        if (wallet != null) {
            wallet.merge(currencyId, seen, CoinsManager::newer);
            // Still held: done. Released meanwhile, and what release() copied may predate this.
            if (online.get(playerId) == wallet) return;
        }
        remembered.asMap().merge(playerId + ":" + currencyId, seen, CoinsManager::newer);
    }

    /** The more recent of two sightings; the one just given when they are as old as each other. */
    private static Known newer(Known current, Known given) {
        return given.at() >= current.at() ? given : current;
    }

    /** Notes a balance an operation of this server has just left in the database. */
    private void rememberBalance(UUID playerId, String currencyId, double value) {
        learn(playerId, currencyId, value, System.nanoTime());
    }

    /**
     * Reads a single balance column straight from the database, on the calling thread.
     *
     * @return The stored value, or {@code null} when the row or the column has none.
     */
    private Double readColumnFromDb(UUID playerId, String column) {
        if (!SAFE_COLUMN.matcher(column).matches()) return null;
        try (Connection conn = api().getDataSource().getConnection()) {
            return readColumn(conn, playerId, column);
        } catch (SQLException e) {
            logger().sendWarning("Failed to read balance column '" + column + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads one balance column of one player on a connection the caller holds.
     *
     * @return The stored value, or {@code null} when the row or the column has none.
     * @throws SQLException When the database refuses the read.
     */
    private static Double readColumn(Connection conn, UUID playerId, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + column + " FROM players WHERE server_uuid = ?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                double value = rs.getDouble(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    /**
     * Reads every balance of a player on the database pool, for the next caller to find in memory.
     *
     * <p>A player without a row, or a column without a value, reads as the starting balance, as
     * {@link #getBalance(UUID, String)} always answered for them. A column the database refuses to
     * read is left as it was: the next attempt comes a few seconds later at the earliest.</p>
     *
     * @param playerId The player's server UUID.
     */
    private void readBalances(UUID playerId) {
        if (currencies.isEmpty()) return;
        reading.put(playerId, Boolean.TRUE);
        long sent = System.nanoTime();
        // Everything below runs on the pool, the copy of the currencies included: this is called from
        // the join handler, and a reload rewriting them meanwhile must not cut that handler short.
        CompletableFuture.runAsync(() -> {
            try (Connection conn = api().getDataSource().getConnection()) {
                for (Currency currency : new ArrayList<>(currencies.values())) {
                    String column = col(currency.getId());
                    if (!SAFE_COLUMN.matcher(column).matches()) continue;
                    try {
                        Double value = readColumn(conn, playerId, column);
                        learn(playerId, currency.getId(), value != null ? value : currency.getStartingBalance(), sent);
                    } catch (SQLException e) {
                        logger().sendWarning("Failed to read balance column '" + column + "': " + e.getMessage());
                    }
                }
            } catch (SQLException e) {
                logger().sendWarning("Failed to read the balances of " + playerId + ": " + e.getMessage());
            }
        }, plugin.getDbExecutor());
    }

    /** {@link #readBalances(UUID)}, unless they were already sent for moments ago. */
    private void readBalancesSoon(UUID playerId) {
        if (reading.asMap().putIfAbsent(playerId, Boolean.TRUE) != null) return;
        readBalances(playerId);
    }

    /**
     * Keeps a player's balances in memory for as long as they are connected. Called at join.
     *
     * <p>From here on a balance asked for on a server thread is answered from memory: the read sent
     * now fills it, and every operation of this server keeps it current. Until the read is back, a
     * known player is answered from their cached row, loaded before they joined.</p>
     *
     * @param playerId The player's server UUID.
     */
    public void hold(UUID playerId) {
        online.computeIfAbsent(playerId, id -> new java.util.concurrent.ConcurrentHashMap<>());
        readBalances(playerId);
    }

    /**
     * Reads a connected player's balances again, now.
     *
     * <p>For a new player, whose row is written while their join goes on: a read sent at join can
     * arrive before the row does.</p>
     *
     * @param playerId The player's server UUID.
     */
    public void reread(UUID playerId) {
        if (online.containsKey(playerId)) readBalances(playerId);
    }

    /**
     * Stops holding a player's balances. Called at quit.
     *
     * <p>What was held is remembered a few minutes more, like any other balance seen here: a plugin
     * saving the player on its own quit listener, after this one, still finds it.</p>
     *
     * @param playerId The player's server UUID.
     */
    public void release(UUID playerId) {
        Map<String, Known> wallet = online.remove(playerId);
        if (wallet == null) return;
        wallet.forEach((currencyId, seen) ->
                remembered.asMap().merge(playerId + ":" + currencyId, seen, CoinsManager::newer));
    }

    /** Whether the calling thread is one of the server's: the main thread, the global or a region thread on Folia. */
    private static boolean onTickThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return false;
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
     * A balance, right now.
     *
     * <p>It is the shape Vault demands — {@code Economy#getBalance} returns a number, not a future —
     * so it cannot be made asynchronous without breaking every plugin that reads a balance through
     * Vault. The answer comes from, in order: the player's row in the player cache; what this server
     * last saw of the balance — always there for a connected player (see {@link #hold(UUID)}), kept a
     * few minutes for anybody else; and only then the database.</p>
     *
     * <h2>On a server thread it never reaches the database</h2>
     * The main thread, and on Folia the global and region threads. A balance seen more than thirty
     * seconds ago is answered as it was and read again in the background. A balance this server has
     * not seen at all is answered {@code 0}, so {@link #has(UUID, String, double)} refuses rather
     * than approves, and read in the background for the next call. This method used to read the
     * column on the spot instead, and a menu checking what its viewer could afford held the tick
     * for as long as the database took. Any other thread still waits for the read, as before.
     *
     * <p>Whatever this answers decides nothing. {@link #withdraw(UUID, String, double)} takes the
     * money with a statement the database refuses when the funds are not in the row: a balance a
     * moment old can make a button read "affordable", it cannot let a payment through. The earlier
     * version returned {@code starting-balance} on a miss, which invented money for anybody whose
     * row was not cached; {@code 0} invents none.</p>
     *
     * @param playerId   The player's server UUID.
     * @param currencyId The currency.
     * @return The balance; the currency's starting balance when the player has no row; {@code 0} on
     *         a server thread when this server knows nothing of it yet.
     */
    public double getBalance(UUID playerId, String currencyId) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return 0;

        Double cached = cachedBalance(playerId, currencyId);
        if (cached != null) return cached;

        long now = System.nanoTime();
        Known known = known(playerId, currencyId);
        if (known != null && known.fresh(now)) return known.value();

        if (onTickThread()) {
            readBalancesSoon(playerId);
            return known != null ? known.value() : 0;
        }

        Double fromDb = readColumnFromDb(playerId, col(currencyId));
        if (fromDb == null) return currency.getStartingBalance();
        learn(playerId, currencyId, fromDb, now);
        return fromDb;
    }

    /**
     * A balance, as a future.
     *
     * <p>Completes at once when the player's cached row holds it. Otherwise the column is read on the
     * database pool — never on the thread that completed the lookup, which for a player in the cache
     * is the caller's own, often the server's.</p>
     *
     * @param playerId   The player's server UUID.
     * @param currencyId The currency.
     * @return A future completing with the balance, or the starting balance when the player has no row.
     */
    public CompletableFuture<Double> getBalanceAsync(UUID playerId, String currencyId) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(0.0);
        return api().getPlayerAsync(playerId).thenCompose(opt -> {
            if (opt.isPresent()) {
                Double value = opt.get().getTargetData(col(currencyId), Double.class);
                if (value != null) return CompletableFuture.completedFuture(value);
            }
            long sent = System.nanoTime();
            return CompletableFuture.supplyAsync(() -> {
                Double fromDb = readColumnFromDb(playerId, col(currencyId));
                if (fromDb == null) return currency.getStartingBalance();
                learn(playerId, currencyId, fromDb, sent);
                return fromDb;
            }, plugin.getDbExecutor());
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

    /**
     * Sets a balance, capped and brought down to the currency's precision: never stored above the
     * amount given ({@link Currency#floor(double)}).
     */
    public CompletableFuture<Void> setBalance(UUID playerId, String currencyId, double amount) {
        Currency currency = currencies.get(currencyId);
        if (currency == null) return CompletableFuture.completedFuture(null);
        double target = currency.getMaxBalance() > 0
            ? Math.min(amount, currency.getMaxBalance())
            : amount;
        double rounded = currency.floor(target);
        // Chained, not waited on: blocking here holds a pool thread while another pool task runs.
        return api().updatePlayerDataAsync(playerId, col(currencyId), rounded)
                .thenRun(() -> rememberBalance(playerId, currencyId, rounded));
    }

    /** The stripe guarding every balance mutation for this player. */
    private Object lockFor(UUID playerId) {
        return locks[Math.floorMod(playerId.hashCode(), LOCK_STRIPES)];
    }

    /** A balance put back to precision: what it now stands at, and what the cap trimmed off it. */
    private record Settled(double balance, double surplus) {}

    /** The cap of a currency at its own precision; {@code 0} for none. */
    private static double cap(Currency currency) {
        return currency.getMaxBalance() > 0 ? currency.floor(currency.getMaxBalance()) : 0;
    }

    /**
     * Puts a balance just changed back to the currency's precision, and under its cap.
     *
     * <p>Down to the precision ({@link Currency#floor(double)}), so that settling a balance never
     * adds to it. The write is conditional on the value just read: a change another server made to
     * the row in between is never overwritten — the operation that made it settles the row after
     * it. An unconditional write here put back a balance from before that change.</p>
     *
     * @param conn     The connection the change was made on.
     * @param currency The currency.
     * @param column   Its column, already checked.
     * @param playerId The player.
     * @param applied  The balance as read back after the change.
     * @return The balance as it stands, and what the cap took off when this call trimmed it.
     * @throws SQLException When the database refuses the write or the read.
     */
    private Settled settle(Connection conn, Currency currency, String column, UUID playerId, double applied)
            throws SQLException {
        double balance = currency.floor(applied);
        double surplus = 0;
        double max = cap(currency);
        if (max > 0 && balance > max) {
            surplus = currency.floor(balance - max);
            balance = max;
        }
        if (Double.compare(balance, applied) == 0) return new Settled(balance, 0);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE players SET " + column + " = ? WHERE server_uuid = ? AND " + column + " = ?")) {
            ps.setDouble(1, balance);
            ps.setString(2, playerId.toString());
            ps.setDouble(3, applied);
            if (ps.executeUpdate() > 0) return new Settled(balance, surplus);
        }
        // Changed in between: what stands now, which the change that made it settles itself.
        Double now = readColumn(conn, playerId, column);
        return new Settled(now != null ? currency.floor(now) : balance, 0);
    }

    /**
     * Applies a signed delta to a balance atomically.
     *
     * <p>The arithmetic happens inside the database ({@code col = col + ?}) rather than in Java, so
     * two servers hitting the same row cannot both read the old value and write back a total that
     * silently drops the other's change. A withdrawal additionally guards on {@code col >= amount},
     * which is what makes "insufficient funds" a real answer instead of a silent clamp to zero.</p>
     *
     * <p>After the update the value is read back, brought back to precision and capped (see
     * {@link #settle}), and pushed into the caches. Any part of a deposit that does not fit under
     * {@code max-balance} is offered to the {@link OverflowHandler} before being trimmed.</p>
     *
     * @param playerId     The player's server UUID.
     * @param currencyId   The currency.
     * @param delta        The signed amount to apply, already at the currency's precision
     *                     ({@link #amountOf(String, double)}).
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
                        // Either the funds guard rejected it, or the player has no row yet. Refused,
                        // the balance shown here was more than the row holds — a moment old, or
                        // changed by another server: the copies are put right from the row, so the
                        // next "can afford" says no as well instead of offering the same failure.
                        if (requireFunds) {
                            try {
                                Double actual = readColumn(conn, playerId, column);
                                if (actual != null) refreshCachedBalance(playerId, currencyId, actual);
                            } catch (SQLException ignored) {
                                // The refusal stands either way; the next read puts the copies right.
                            }
                        }
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

                    // Back to the currency's precision, and under its cap. The delta was already at
                    // precision, so this only takes off what a double adds in its last bits — or,
                    // for a balance stored off precision before, the part that never counted.
                    // Rounded to the nearest, as it was, that part became money.
                    Settled settled = settle(conn, currency, column, playerId, applied);
                    normalised = settled.balance();
                    surplus = settled.surplus();
                } catch (SQLException e) {
                    logger().sendError("Balance update failed for " + playerId + ": " + e.getMessage());
                    return null;
                }

                refreshCachedBalance(playerId, currencyId, normalised);

                // The surplus is offered to whoever registered to catch it (XTools' bank) before
                // being trimmed. Never at the cost of the payment itself.
                if (surplus > 0) offerSurplus(playerId, currencyId, surplus);
                return normalised;
            }
        }, plugin.getDbExecutor());
    }

    /**
     * An amount as this currency can move it: brought down to its precision.
     *
     * <h2>Why down, and why before</h2>
     * Amounts used to be applied as given, and the balance on each side rounded to the nearest
     * afterwards. A payment of {@code 0.015} left the payer at {@code 9.985}, rounded up to
     * {@code 9.99}, and the payee at {@code 0.015}, rounded up to {@code 0.02}: a cent made with every
     * payment, and with a currency without decimals, {@code /eco pay alt 0.5} cost nothing and gave
     * one. Every amount is now brought down to the precision before anything is applied, the same
     * amount is what both sides of a transfer see, and nothing is rounded to the nearest any more.
     * Down, so that no operation credits more than it was asked to, nor takes more.
     *
     * @param currencyId The currency.
     * @param amount     The amount asked for.
     * @return The amount at the currency's precision, rounded down; {@code 0} when it is less than
     *         the currency can count, not a positive number, or the currency is unknown.
     */
    public double amountOf(String currencyId, double amount) {
        Currency currency = currencies.get(currencyId);
        if (currency == null || !(amount > 0) || !Double.isFinite(amount)) return 0;
        return Math.max(0, currency.floor(amount));
    }

    /**
     * Takes an amount from a player, refusing the operation when the funds are not there.
     *
     * <p>Prefer this over {@link #removeBalance(UUID, String, double)} whenever the caller acts on
     * the outcome — handing over an item, confirming a purchase — because it is the only variant
     * that can tell you the money was actually collected.</p>
     *
     * <p>What is taken is the amount brought down to the currency's precision
     * ({@link #amountOf(String, double)}): {@code 0.015} takes {@code 0.01}. An amount that comes to
     * less than the currency can count is refused, never taken as nothing.</p>
     *
     * @return A future completing with {@code true} when the amount was withdrawn.
     */
    public CompletableFuture<Boolean> withdraw(UUID playerId, String currencyId, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(true);
        double taken = amountOf(currencyId, amount);
        if (taken <= 0) return CompletableFuture.completedFuture(false);
        return applyDelta(playerId, currencyId, -taken, true).thenApply(Objects::nonNull);
    }

    /**
     * Adds an amount to a player's balance atomically.
     *
     * <p>What is credited is the amount brought down to the currency's precision
     * ({@link #amountOf(String, double)}); an amount that comes to less than the currency can count is
     * refused.</p>
     *
     * @return A future completing with the new balance, or {@code null} if it could not be applied.
     */
    public CompletableFuture<Double> deposit(UUID playerId, String currencyId, double amount) {
        if (amount <= 0) return getBalanceAsync(playerId, currencyId);
        double given = amountOf(currencyId, amount);
        if (given <= 0) return CompletableFuture.completedFuture(null);
        return applyDelta(playerId, currencyId, given, false);
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

            // Down to the precision, never to the nearest, and the event told what is stored.
            double newAmount = currency.floor(finalAmount);
            boolean wasCapped = capped;
            return fireBalanceChangeEvent(playerId, currencyId, oldBalance, newAmount, type).thenCompose(cancelled -> {
                if (cancelled) {
                    return CompletableFuture.completedFuture(false);
                }
                double rounded = newAmount;
                return api().updatePlayerDataAsync(playerId, col(currencyId), rounded)
                    .thenApply(v -> {
                        rememberBalance(playerId, currencyId, rounded);
                        return wasCapped;
                    });
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
        // Less than the currency counts: nothing to take. The withdrawal refuses such an amount, and
        // taken for a shortfall, that refusal would empty the balance below.
        if (amountOf(currencyId, amount) <= 0) return CompletableFuture.completedFuture(null);
        return withdraw(playerId, currencyId, amount).thenCompose(ok -> {
            if (ok) return CompletableFuture.<Void>completedFuture(null);
            return getBalanceAsync(playerId, currencyId).thenCompose(current ->
                current > 0
                    ? setBalance(playerId, currencyId, 0.0)
                    : CompletableFuture.<Void>completedFuture(null));
        });
    }

    /**
     * Whether a player has at least this much, as far as this server knows.
     *
     * <p>For what to show — a price in red, a button that says "cannot afford" — and for refusing
     * early. Never for taking the money: on a server thread an unknown balance reads as {@code 0},
     * and any balance can be a moment old (see {@link #getBalance(UUID, String)}). The answer that
     * counts is {@link #withdraw(UUID, String, double)}'s.</p>
     */
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
                    ps.setDouble(i + 1, currency.floor(currency.getStartingBalance()));
                }
                return ps.executeUpdate();
            } catch (SQLException e) {
                logger().sendError("Failed to reset all balances: " + e.getMessage());
                return -1;
            }
        }, plugin.getDbExecutor()).thenApply(rows -> {
            if (rows >= 0) invalidateAllPlayerCaches();
            return rows;
        });
    }

    /**
     * Drops every cached copy of player data after a bulk write: L1 here, L2 (Redis),
     * and L1 on the other servers via the {@code xcore} sync channel. The balances this server
     * keeps itself go too, and those of the connected players are read again.
     */
    private void invalidateAllPlayerCaches() {
        remembered.invalidateAll();
        online.keySet().forEach(this::readBalances);
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
        String serverName = plugin.getServerName();
        this.columnSuffix = perServer ? "_" + serverName : "";
        loadCurrencies();
        loadExchangeRates();

        // The currencies, or the columns behind them, may not be the same any more: what was seen
        // of them goes, and the connected players' balances are read again.
        remembered.invalidateAll();
        online.forEach((playerId, wallet) -> {
            wallet.clear();
            readBalances(playerId);
        });
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
