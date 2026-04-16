package fr.xyness.XCore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import fr.xyness.XCore.API.ColumnBuilder;
import fr.xyness.XCore.API.ColumnType;
import fr.xyness.XCore.API.DatabaseType;
import fr.xyness.XCore.API.SqlDialect;
import fr.xyness.XCore.API.XCoreApiProvider;
import fr.xyness.XCore.API.XCoreApiService;
import fr.xyness.XCore.Addon.AddonListenerRegistry;
import fr.xyness.XCore.Addon.AddonManager;
import fr.xyness.XCore.Cache.CacheManager;
import fr.xyness.XCore.Cache.PlayerCache;
import fr.xyness.XCore.Commands.XCoreCommand;
import fr.xyness.XCore.DAO.PlayerDAO;
import fr.xyness.XCore.Gui.GuiListener;
import fr.xyness.XCore.Gui.GuiManager;
import fr.xyness.XCore.Integrations.FloodgateHook;
import fr.xyness.XCore.Listeners.PlayerListener;
import fr.xyness.XCore.Models.PlayerData;
import fr.xyness.XCore.Sync.SyncManager;
import fr.xyness.XCore.Utils.LangManager;
import fr.xyness.XCore.Utils.LogFilter;
import fr.xyness.XCore.Utils.Logger;
import fr.xyness.XCore.Utils.Methods;
import fr.xyness.XCore.Utils.SchedulerAdapter;
import fr.xyness.XCore.Economy.CoinsManager;
import fr.xyness.XCore.Economy.CoinsCommand;
import fr.xyness.XCore.Economy.EconomyExpansion;
import fr.xyness.XCore.Economy.EconomyWebModule;
import fr.xyness.XCore.Economy.VaultEconomyProvider;
import fr.xyness.XCore.Lang.LangNamespace;
import fr.xyness.XCore.Web.WebPanel;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Main plugin class for XCore V2.
 * <p>
 * XCore is a shared framework that provides database management, player caching,
 * cross-server sync, GUI management, and an addon system for modular extensions.
 * </p>
 */
public class XCore extends JavaPlugin {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Standard date-time formatter for timestamps stored in the database. */
    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // -------------------------------------------------------------------------
    // Core subsystems
    // -------------------------------------------------------------------------

    private final Logger logger = new Logger("Main");
    private final Methods methods = new Methods(this);
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newCachedThreadPool(
            r -> { Thread t = new Thread(r, "XCore-Thread"); t.setDaemon(true); return t; });

    private SchedulerAdapter schedulerAdapter;
    private PlayerDAO playerDAO;
    private HikariDataSource dataSource;
    private DatabaseType databaseType = DatabaseType.SQLITE;
    private SqlDialect dialect;
    private volatile JedisPool jedisPool;
    private int redisTTL = 3600;
    private PlayerCache<PlayerData> playerCache;
    private LangManager langManager;
    private CacheManager cacheManager;
    private SyncManager syncManager;
    private GuiManager guiManager;
    private AddonManager addonManager;
    private AddonListenerRegistry listenerRegistry;
    private Object redisHealthTask;
    private WebPanel webPanel;
    private CoinsManager coinsManager;
    private LangNamespace economyLang;
    private long startTimeMillis;
    private FileConfiguration addonsConfig;
    private File addonsFile;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onLoad() {
        logger.sendRawBar();
        XCoreApiProvider.register(new XCoreApiService(this));

        // Init addons.yml (needed before loadAddons for addon toggles)
        this.addonsFile = new File(getDataFolder(), "addons.yml");
        if (!addonsFile.exists()) {
            getDataFolder().mkdirs();
            try { addonsFile.createNewFile(); } catch (IOException e) { getLogger().warning("Failed to create addons.yml: " + e.getMessage()); }
        }
        this.addonsConfig = YamlConfiguration.loadConfiguration(addonsFile);

        // Load addons early so their onLoad() runs during server load phase
        // (allows addons to hook into Netty, register protocol handlers, etc.)
        this.addonManager = new AddonManager(this);
        addonManager.loadAddons();
        logger.sendRawBar();
    }

    @Override
    public void onEnable() {
        if (!start()) Bukkit.getServer().getPluginManager().disablePlugin(this);
    }

    @Override
    public void onDisable() {
        stop();
    }

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------

    private int clamp(int value, int min, int max, String name) {
        if (value < min || value > max) {
            logger.sendWarning("Config value '" + name + "' = " + value + " is out of range [" + min + ", " + max + "]. Using " + Math.clamp(value, min, max) + ".");
            return Math.clamp(value, min, max);
        }
        return value;
    }

    /**
     * Main startup sequence.
     *
     * @return {@code true} if startup was successful.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean start() {
        LogFilter.registerFilter();
        logger.sendRawBar();
        logger.sendInfo("Starting the plugin.");
        long startTime = System.nanoTime();
        this.startTimeMillis = System.currentTimeMillis();

        if (Runtime.version().feature() < 21) {
            logger.sendError("XCore requires Java 21 or newer. Current version: " + Runtime.version());
            return false;
        }

        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        // ---- Scheduler ----
        this.schedulerAdapter = new SchedulerAdapter(this);

        // ---- Config ----
        updateConfigWithDefaults();
        FileConfiguration config = getConfig();
        databaseType = DatabaseType.valueOf(config.getString("database-type", "sqlite").toUpperCase());
        this.dialect = SqlDialect.of(databaseType);

        // ---- Lang ----
        langManager = new LangManager(this);
        logger.setDebug(config.getBoolean("debug", false));

        // ---- Database (HikariCP) ----
        try {
            HikariConfig hikaConfig = new HikariConfig();
            switch (databaseType) {
                case MYSQL -> {
                    String host = config.getString("database.host", "localhost");
                    int port = clamp(config.getInt("database.port", 3306), 1, 65535, "database.port");
                    String dbName = config.getString("database.name", "xcore");
                    int poolSize = clamp(config.getInt("database.pool-size", 10), 1, 100, "database.pool-size");
                    hikaConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
                    hikaConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=" + config.getBoolean("database.ssl", false) + "&allowPublicKeyRetrieval=true&characterEncoding=utf8");
                    hikaConfig.setUsername(config.getString("database.username", "root"));
                    hikaConfig.setPassword(config.getString("database.password", ""));
                    hikaConfig.setPoolName("MySQLPool");
                    hikaConfig.setMaximumPoolSize(poolSize);
                    hikaConfig.setMinimumIdle(2); hikaConfig.setIdleTimeout(60000); hikaConfig.setMaxLifetime(600000);
                    hikaConfig.addDataSourceProperty("cachePrepStmts", "true");
                    hikaConfig.addDataSourceProperty("prepStmtCacheSize", "250");
                    hikaConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                    logger.sendInfo("Using MySQL database.");
                }
                case POSTGRESQL -> {
                    String host = config.getString("database.host", "localhost");
                    int port = clamp(config.getInt("database.port", 5432), 1, 65535, "database.port");
                    String dbName = config.getString("database.name", "xcore");
                    int poolSize = clamp(config.getInt("database.pool-size", 10), 1, 100, "database.pool-size");
                    hikaConfig.setDriverClassName("org.postgresql.Driver");
                    hikaConfig.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + dbName + "?ssl=" + config.getBoolean("database.ssl", false));
                    hikaConfig.setUsername(config.getString("database.username", "root"));
                    hikaConfig.setPassword(config.getString("database.password", ""));
                    hikaConfig.setPoolName("PostgreSQLPool");
                    hikaConfig.setMaximumPoolSize(poolSize);
                    hikaConfig.setMinimumIdle(2); hikaConfig.setIdleTimeout(60000); hikaConfig.setMaxLifetime(600000);
                    logger.sendInfo("Using PostgreSQL database.");
                }
                default -> {
                    databaseType = DatabaseType.SQLITE;
                    hikaConfig.setJdbcUrl("jdbc:sqlite:plugins/XCore/storage.db");
                    hikaConfig.setConnectionInitSql("PRAGMA foreign_keys=ON");
                    hikaConfig.setPoolName("SQLitePool");
                    hikaConfig.setMaximumPoolSize(2); hikaConfig.setMinimumIdle(1);
                    hikaConfig.setIdleTimeout(60000); hikaConfig.setMaxLifetime(600000);
                    logger.sendInfo("Using SQLite database.");
                }
            }

            hikaConfig.addDataSourceProperty("socketTimeout", "30000");
            hikaConfig.setConnectionTimeout(10000);

            this.dataSource = new HikariDataSource(hikaConfig);
            try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement()) {
                stmt.setQueryTimeout(10);
                stmt.execute("SELECT 1");
                logger.sendInfo("Database connection successful.");
                switch (databaseType) {
                    case MYSQL -> stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS players (
                            id INT PRIMARY KEY AUTO_INCREMENT, server_uuid CHAR(36) NOT NULL UNIQUE,
                            mojang_uuid CHAR(36) NOT NULL, player_name VARCHAR(16) NOT NULL,
                            head_textures TEXT NOT NULL, created_at VARCHAR(19) NOT NULL,
                            INDEX idx_players_uuid (server_uuid), INDEX idx_players_name (player_name)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
                    case POSTGRESQL -> {
                        stmt.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS players (
                                id SERIAL PRIMARY KEY, server_uuid CHAR(36) NOT NULL UNIQUE,
                                mojang_uuid CHAR(36) NOT NULL, player_name VARCHAR(16) NOT NULL,
                                head_textures TEXT NOT NULL, created_at VARCHAR(19) NOT NULL
                            );
                        """);
                        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_uuid ON players (server_uuid);");
                        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_name ON players (player_name);");
                    }
                    default -> {
                        stmt.addBatch("PRAGMA foreign_keys = ON;");
                        stmt.addBatch("""
                            CREATE TABLE IF NOT EXISTS players (
                                id INTEGER PRIMARY KEY AUTOINCREMENT, server_uuid CHAR(36) NOT NULL UNIQUE,
                                mojang_uuid CHAR(36) NOT NULL, player_name TEXT NOT NULL,
                                head_textures TEXT NOT NULL, created_at TEXT NOT NULL
                            );
                        """);
                        stmt.addBatch("CREATE INDEX IF NOT EXISTS idx_players_uuid ON players (server_uuid);");
                        stmt.addBatch("CREATE INDEX IF NOT EXISTS idx_players_name ON players (player_name);");
                        stmt.executeBatch();
                    }
                }
            }
        } catch (SQLException e) {
            logger.sendError("Failed to initialize database : " + e.getMessage());
            return false;
        }

        // ---- PlayerDAO ----
        this.playerDAO = new PlayerDAO(this, executor);
        playerDAO.loadExtraColumnsFromMetadata();

        // Built-in activity tracking columns
        new ColumnBuilder(this)
            .addColumn("last_login", ColumnType.TEXT)
            .addColumn("last_logout", ColumnType.TEXT)
            .apply();

        // ---- Redis ----
        boolean crossServerEnabled = config.getBoolean("cross-server.enabled", false);
        if (crossServerEnabled && config.getBoolean("cross-server.redis.enabled", false)) {
            try {
                String redisHost = config.getString("cross-server.redis.host", "localhost");
                int redisPort = clamp(config.getInt("cross-server.redis.port", 6379), 1, 65535, "cross-server.redis.port");
                String redisPassword = config.getString("cross-server.redis.password", "");
                int redisDb = config.getInt("cross-server.redis.database", 0);
                redisTTL = clamp(config.getInt("cross-server.redis.ttl", 3600), 60, 86400, "cross-server.redis.ttl");

                JedisPoolConfig poolConfig = new JedisPoolConfig();
                poolConfig.setMaxTotal(16); poolConfig.setMaxIdle(8); poolConfig.setMinIdle(2);

                jedisPool = (redisPassword != null && !redisPassword.isEmpty())
                    ? new JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword, redisDb)
                    : new JedisPool(poolConfig, redisHost, redisPort, 2000, null, redisDb);

                try (Jedis jedis = jedisPool.getResource()) { jedis.ping(); }
                logger.sendInfo("Redis cache enabled (" + redisHost + ":" + redisPort + ").");

                // Redis health check every 30 seconds
                redisHealthTask = schedulerAdapter.runAsyncTaskTimer(() -> {
                    try (Jedis jedis = jedisPool.getResource()) { jedis.ping(); }
                    catch (Exception e) { logger.sendWarning("Redis health check failed : " + e.getMessage()); }
                }, 600, 600);
            } catch (Exception e) {
                logger.sendError("Failed to connect to Redis : " + e.getMessage());
                logger.sendWarning("Falling back to local cache only.");
                if (jedisPool != null) { jedisPool.close(); jedisPool = null; }
            }
        }

        // ---- CacheManager ----
        this.cacheManager = new CacheManager(jedisPool, executor);

        // ---- PlayerCache ----
        int maxCacheSize = clamp(config.getInt("cache.max-size", 100000), 100, 10_000_000, "cache.max-size");
        int cacheTTLMinutes = clamp(config.getInt("cache.ttl-minutes", 60), 1, 1440, "cache.ttl-minutes");
        int mojangCacheSize = clamp(config.getInt("cache.mojang-max-size", 5000), 100, 1_000_000, "cache.mojang-max-size");
        int maxApiConcurrency = clamp(config.getInt("cache.max-api-concurrency", 10), 1, 50, "cache.max-api-concurrency");
        int apiTimeoutMs = clamp(config.getInt("cache.api-timeout-ms", 2000), 500, 30000, "cache.api-timeout-ms");
        int cbThreshold = clamp(config.getInt("cache.circuit-breaker-threshold", 5), 1, 100, "cache.circuit-breaker-threshold");
        int cbOpenMinutes = clamp(config.getInt("cache.circuit-breaker-open-minutes", 5), 1, 60, "cache.circuit-breaker-open-minutes");

        this.playerCache = new PlayerCache.Builder<PlayerData>()
            .executor(executor)
            .jedisPool(jedisPool)
            .redisTTL(redisTTL)
            .maxCacheSize(maxCacheSize)
            .cacheTTLMinutes(cacheTTLMinutes)
            .mojangCacheSize(mojangCacheSize)
            .maxApiConcurrency(maxApiConcurrency)
            .apiTimeoutMs(apiTimeoutMs)
            .circuitBreaker(cbThreshold, cbOpenMinutes)
            .userAgent("XCore/2.0")
            .serializer(p -> gson.toJson(p))
            .deserializer(s -> gson.fromJson(s, PlayerData.class))
            .uuidExtractor(PlayerData::getUuid)
            .nameExtractor(PlayerData::getName)
            .textureExtractor(PlayerData::getTexture)
            .mojangUuidExtractor(PlayerData::getMojangUUID)
            .findByUuidAsync(uuid -> playerDAO.findByServerUuidAsync(uuid))
            .findByNameAsync(name -> playerDAO.findByNameAsync(name))
            .findByUuidsAsync(uuids -> playerDAO.findByServerUuidsAsync(uuids))
            .logDebug(logger::sendDebug)
            .logWarning(logger::sendWarning)
            .logError(logger::sendError)
            .build();

        // ---- SyncManager ----
        int pollSeconds = clamp(config.getInt("cross-server.sync.poll-interval-seconds", 3), 1, 60, "cross-server.sync.poll-interval-seconds");
        int retentionSeconds = clamp(config.getInt("cross-server.sync.retention-seconds", 300), 30, 3600, "cross-server.sync.retention-seconds");

        this.syncManager = new SyncManager(
            jedisPool, dataSource, databaseType, executor,
            pollSeconds * 20, retentionSeconds,
            logger::sendDebug, logger::sendWarning, logger::sendError
        );

        if (crossServerEnabled) {
            syncManager.start();
            logger.sendInfo("Cross-server sync started.");
        }

        // ---- GUI ----
        this.guiManager = new GuiManager();
        getServer().getPluginManager().registerEvents(new GuiListener(guiManager, schedulerAdapter), this);

        // ---- Listeners ----
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // ---- Commands ----
        new XCoreCommand(this).register();

        // ---- Floodgate (Bedrock) ----
        FloodgateHook.init();
        if (FloodgateHook.isAvailable()) {
            logger.sendInfo("Floodgate detected. Bedrock GUI compatibility enabled.");
        }

        // ---- PlaceholderAPI ----
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            // PlaceholderAPI expansion registration will be handled by an addon or integration class
            logger.sendInfo("PlaceholderAPI detected.");
        }

        // ---- Addon Listener Registry ----
        this.listenerRegistry = new AddonListenerRegistry(this);

        // ---- Web dashboard (must start BEFORE addons so they can register modules) ----
        if (config.getBoolean("web-dashboard.enabled", false)) {
            for (String f : new String[]{"index.html", "style.css", "app.js"}) {
                try {
                    if (getResource("web/" + f) != null) saveResource("web/" + f, true);
                } catch (Exception e) {
                    logger.sendDebug("Failed to save web resource '" + f + "': " + e.getMessage());
                }
            }
            int webPort = clamp(config.getInt("web-dashboard.port", 8085), 1, 65535, "web-dashboard.port");
            String webToken = config.getString("web-dashboard.token", "CHANGE_ME_TO_A_SECURE_TOKEN");
            boolean metricsPublic = config.getBoolean("web-dashboard.metrics-public", true);
            webPanel = new WebPanel(getDataFolder(), webPort, webToken, metricsPublic);
            try {
                webPanel.start();
            } catch (Exception e) {
                logger.sendError("Failed to start web dashboard: " + e.getMessage());
                webPanel = null;
            }
            if (webPanel != null && "CHANGE_ME_TO_A_SECURE_TOKEN".equals(webToken)) {
                logger.sendSevere("Web dashboard token is set to default! Change it in config.yml immediately.");
            }
        }

        // ---- Economy (requires Vault) ----
        boolean economyEnabled = config.getBoolean("economy.enabled", true);
        boolean vaultPresent = Bukkit.getPluginManager().getPlugin("Vault") != null;
        if (economyEnabled && vaultPresent) {
            try {
                coinsManager = new CoinsManager(this);

                // Economy lang
                File ecoLangFile = new File(getDataFolder(), "economy-lang.yml");
                if (!ecoLangFile.exists()) saveResource("economy-lang.yml", false);
                economyLang = new LangNamespace();
                economyLang.reload(ecoLangFile, getResource("economy-lang.yml"));

                // Register columns for currencies
                for (var currency : coinsManager.getCurrencies()) {
                    String col = coinsManager.col(currency.getId());
                    XCoreApiProvider.get().columnBuilder()
                        .addColumn(col, ColumnType.DOUBLE).defaultValue(currency.getStartingBalance()).notNull()
                        .apply();
                }

                // Vault provider
                try {
                    var provider = new VaultEconomyProvider(coinsManager);
                    Bukkit.getServicesManager().register(
                        net.milkbowl.vault.economy.Economy.class, provider, this,
                        org.bukkit.plugin.ServicePriority.Highest);
                    logger.sendInfo("Vault economy provider registered.");
                } catch (Throwable e) {
                    logger.sendDebug("Failed to register Vault provider: " + e.getMessage());
                }

                // Commands
                new CoinsCommand(this, coinsManager, economyLang).register();

                // PlaceholderAPI
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    new EconomyExpansion(coinsManager).register();
                    logger.sendInfo("Economy PlaceholderAPI expansion registered.");
                }

                // Web module
                if (webPanel != null) {
                    webPanel.registerModule(new EconomyWebModule(this, coinsManager));
                }

                // Scheduled payouts
                if (config.getBoolean("economy.scheduled-payouts.enabled", false)) {
                    long interval = config.getLong("economy.scheduled-payouts.interval-minutes", 60) * 60 * 20;
                    double amount = config.getDouble("economy.scheduled-payouts.amount", 100);
                    String currency = config.getString("economy.scheduled-payouts.currency", "coins");
                    schedulerAdapter.runAsyncTaskTimer(() -> {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            coinsManager.addBalance(p.getUniqueId(), currency, amount);
                        }
                    }, interval, interval);
                    logger.sendInfo("Scheduled payouts enabled (" + amount + " " + currency + " every " + config.getLong("economy.scheduled-payouts.interval-minutes", 60) + "min).");
                }

                // Interest
                if (config.getBoolean("economy.interest.enabled", false)) {
                    long interval = config.getLong("economy.interest.interval-minutes", 1440) * 60 * 20;
                    double rate = config.getDouble("economy.interest.rate", 0.01);
                    String currency = config.getString("economy.interest.currency", "coins");
                    schedulerAdapter.runAsyncTaskTimer(() -> {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            coinsManager.getBalanceAsync(p.getUniqueId(), currency).thenAccept(bal -> {
                                if (bal > 0) coinsManager.addBalance(p.getUniqueId(), currency, bal * rate);
                            });
                        }
                    }, interval, interval);
                    logger.sendInfo("Interest enabled (" + (rate * 100) + "% on " + currency + ").");
                }

                logger.sendInfo("Economy system enabled with " + coinsManager.getCurrencies().size() + " currency(ies).");
            } catch (Exception e) {
                logger.sendError("Failed to initialize economy: " + e.getMessage());
                coinsManager = null;
            }
        } else if (economyEnabled && !vaultPresent) {
            logger.sendWarning("Economy is enabled but Vault is not installed. Economy system disabled.");
        }

        // ---- Addons ----
        addonManager.enableAddons();
        saveDataConfig();

        // ---- Prefetch online players ----
        List<UUID> onlineUuids = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        if (!onlineUuids.isEmpty()) {
            logger.sendInfo("Prefetching data for " + onlineUuids.size() + " online player(s).");
            playerCache.getPlayers(onlineUuids).thenAccept(map ->
                logger.sendInfo("Prefetched " + map.size() + " player(s) into cache."));
        }

        long end = System.nanoTime();
        long durationInMillis = (end - startTime) / 1_000_000;
        logger.sendInfo("Plugin loaded in <aqua>" + methods.getNumberSeparate(durationInMillis) + "ms</aqua> <green>\u2713");
        logger.sendRawBar();
        return true;
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Main shutdown sequence.
     */
    public void stop() {
        logger.sendRawBar();
        logger.sendInfo("Stopping the plugin.");

        // Addons first (reverse dependency order)
        if (addonManager != null) addonManager.disableAddons();

        // Stop web dashboard
        if (webPanel != null) webPanel.stop();

        // Stop cross-server sync and health check
        if (syncManager != null) syncManager.stop();
        if (redisHealthTask != null) schedulerAdapter.cancelTask(redisHealthTask);

        // Shutdown caches
        if (playerCache != null) playerCache.shutdown();
        if (cacheManager != null) cacheManager.shutdown();

        // Graceful shutdown: wait for in-flight operations
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.sendWarning("Executor did not terminate in 10s, forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (dataSource != null) dataSource.close();
        if (jedisPool != null) jedisPool.close();

        HandlerList.unregisterAll(this);
        XCoreApiProvider.unregister();

        logger.sendInfo("Plugin disabled successfully.");
        logger.sendRawBar();
    }

    // -------------------------------------------------------------------------
    // Config update
    // -------------------------------------------------------------------------

    /**
     * Updates the configuration file by adding missing keys from the default config in the JAR.
     * Existing values are preserved; only new keys are added.
     */
    private void updateConfigWithDefaults() {
        saveDefaultConfig();
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) return;

        FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream defStream = getResource("config.yml")) {
            if (defStream == null) return;
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defStream, StandardCharsets.UTF_8));

            boolean changed = false;
            for (String key : defConfig.getKeys(true)) {
                if (!defConfig.isConfigurationSection(key) && !diskConfig.contains(key)) {
                    diskConfig.set(key, defConfig.get(key));
                    changed = true;
                }
            }

            if (changed) {
                diskConfig.save(configFile);
            }
        } catch (IOException e) {
            logger.sendError("Error updating config with defaults: " + e.getMessage());
        }
        reloadConfig();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** @return The XCore logger instance. */
    public Logger logger() { return logger; }

    /** @return The Bukkit/Folia scheduler adapter. */
    public SchedulerAdapter schedulerAdapter() { return schedulerAdapter; }

    /** @return The shared utility methods. */
    public Methods methods() { return methods; }

    /** @return The shared executor service. */
    public ExecutorService getExecutor() { return executor; }

    /** @return The player data access object. */
    public PlayerDAO playerDAO() { return playerDAO; }

    /** @return The HikariCP data source. */
    public HikariDataSource getDataSource() { return dataSource; }

    /** @return The player data cache. */
    public PlayerCache<PlayerData> playerCache() { return playerCache; }

    /** @return The configured database type. */
    public DatabaseType getDatabaseType() { return databaseType; }

    /** @return The SQL dialect for the configured database. */
    public SqlDialect getDialect() { return dialect; }

    /** @return The Redis connection pool, or {@code null} if Redis is disabled. */
    public JedisPool getJedisPool() { return jedisPool; }

    /** @return The Redis TTL in seconds. */
    public int getRedisTTL() { return redisTTL; }

    /** @return The core language manager. */
    public LangManager langManager() { return langManager; }

    /** @return The central cache manager. */
    public CacheManager getCacheManager() { return cacheManager; }

    /** @return The cross-server sync manager. */
    public SyncManager getSyncManager() { return syncManager; }

    /** @return The GUI manager. */
    public GuiManager getGuiManager() { return guiManager; }

    /** @return The addon manager. */
    public AddonManager getAddonManager() { return addonManager; }

    /** @return The addon listener registry. */
    public AddonListenerRegistry getListenerRegistry() { return listenerRegistry; }

    /** @return The web dashboard panel, or {@code null} if disabled. */
    public WebPanel getWebPanel() { return webPanel; }

    /** @return The economy manager, or {@code null} if economy is disabled. */
    public CoinsManager getCoinsManager() { return coinsManager; }

    /** @return The economy language namespace, or {@code null} if economy is disabled. */
    public LangNamespace getEconomyLang() { return economyLang; }

    /**
     * Checks if cross-server sync is enabled for a specific addon.
     * Returns true only if global sync is enabled AND the addon is toggled on.
     */
    public boolean isSyncEnabledFor(String addonName) {
        return getConfig().getBoolean("cross-server.enabled", false)
            && addonsConfig.getBoolean("sync-addons." + addonName, false);
    }

    /**
     * Registers an addon in the data file (default: true).
     * Called automatically when an addon is detected.
     */
    public void registerAddonToggle(String addonName) {
        String path = "addons." + addonName;
        if (!addonsConfig.contains(path)) {
            addonsConfig.set(path, true);
        }
    }

    /**
     * @return Whether the given addon is enabled.
     */
    public boolean isAddonEnabled(String addonName) {
        return addonsConfig.getBoolean("addons." + addonName, true);
    }

    /**
     * Registers an addon in the sync section of data file (default: false).
     * Called automatically when an addon loads.
     */
    public void registerSyncAddon(String addonName) {
        String path = "sync-addons." + addonName;
        if (!addonsConfig.contains(path)) {
            addonsConfig.set(path, false);
        }
    }

    /** Saves the addons.yml file (called once after all addons are loaded). */
    public void saveDataConfig() {
        try { addonsConfig.save(addonsFile); }
        catch (IOException e) { logger.sendError("Failed to save addons.yml: " + e.getMessage()); }
    }

    /** @return The configured server name for cross-server tagging. */
    public String getServerName() {
        return getConfig().getString("cross-server.server-name", "default");
    }

    /** @return The plugin start time in milliseconds. */
    public long getStartTimeMillis() { return startTimeMillis; }

    /** @return The shared Gson instance. */
    public Gson getGson() { return gson; }
}
