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
import fr.xyness.XCore.Extension.ExtensionRegistry;
import fr.xyness.XCore.Cache.PlayerCache;
import fr.xyness.XCore.Commands.XCoreCommand;
import fr.xyness.XCore.DAO.PlayerDAO;
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
import fr.xyness.XCore.Economy.EcoCommand;
import fr.xyness.XCore.Economy.EconomyExpansion;
import fr.xyness.XCore.Economy.EconomyWebModule;
import fr.xyness.XCore.Economy.VaultEconomyProvider;
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
 * <p>
 * Since 2.0 the four official addons are plugins of their own, built on
 * {@link fr.xyness.XCore.Extension.XPlugin}: they live in {@code plugins/}, depend on XCore, and
 * register with {@link #extensions()} so that everything XCore lists, reloads and measures still
 * covers them. Third-party addons keep loading from {@code plugins/XCore/addons/}.
 * </p>
 */
public class XCore extends JavaPlugin {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Standard date-time formatter for timestamps stored in the database. */
    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Sync channel used by XCore itself (cache invalidation after bulk writes). */
    public static final String SYNC_CHANNEL = "xcore";

    /**
     * Version of the messages on {@link #SYNC_CHANNEL}.
     *
     * <p>0 is the format XCore 1.x speaks, and 2.0 has not changed it: a network can be updated one
     * server at a time and keep its caches in step all along. Raise it the day {@code CACHE_CLEAR}
     * or {@code PLAYER_UPDATE} change meaning, so a server left on the old format ignores the new
     * messages instead of misreading them.</p>
     */
    public static final int SYNC_PROTOCOL = 0;

    /** Where the start of the current session is kept, on a player's temporary data. */
    public static final String SESSION_START_KEY = "xcore:session-start";

    // -------------------------------------------------------------------------
    // Core subsystems
    // -------------------------------------------------------------------------

    private final Logger logger = new Logger("Main");
    private final Methods methods = new Methods(this);
    private final Gson gson = new Gson();

    /** Kept so their overflow threads can be stopped with the pools. */
    private final java.util.List<fr.xyness.XCore.Utils.RejectedTaskPolicy> rejectionPolicies = new java.util.ArrayList<>();

    /**
     * General worker pool, for everything that is not a database call.
     * <p>
     * Bounded on purpose. A cached pool grows a thread per queued task and would happily create
     * thousands of them under a burst, far past the point where they can do anything useful. Excess
     * work queues instead.
     * </p>
     */
    private final ExecutorService executor = buildPool("XCore-Work", Math.max(4, Runtime.getRuntime().availableProcessors()) * 2);

    /**
     * Database pool, kept apart from the one above so a task waiting on a query is never waiting
     * on a task queued behind itself. Sized after the connection pool: more threads than
     * connections only means more threads waiting for one.
     */
    private volatile boolean migrateUuidChanges = true;
    private ExecutorService dbExecutor;

    private ExecutorService buildPool(String name, int size) {
        fr.xyness.XCore.Utils.RejectedTaskPolicy policy = new fr.xyness.XCore.Utils.RejectedTaskPolicy(name, logger);
        rejectionPolicies.add(policy);
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
            Math.max(2, size / 2), size,
            60L, TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(10_000),
            r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; },
            policy);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private SchedulerAdapter schedulerAdapter;
    private PlayerDAO playerDAO;
    private HikariDataSource dataSource;

    /** Per-addon timings, off unless {@code profiling} or {@code debug} is on. */
    private final fr.xyness.XCore.Utils.Profiler profiler = new fr.xyness.XCore.Utils.Profiler();
    private DatabaseType databaseType = DatabaseType.SQLITE;
    private SqlDialect dialect;
    private volatile JedisPool jedisPool;
    private int redisTTL = 3600;
    private PlayerCache<PlayerData> playerCache;
    private LangManager langManager;
    private SyncManager syncManager;
    private AddonManager addonManager;
    private AddonListenerRegistry listenerRegistry;

    /** Addons and plugins built on XCore, as one list. Exists from construction: plugins register in their onEnable. */
    private final ExtensionRegistry extensions = new ExtensionRegistry(this);

    /** Set once {@link #start()} has gone through, cleared when {@link #stop()} begins. */
    private volatile boolean running;

    /** The extension that authenticates players: XCore keeps the door shut until it is up. */
    private static final String LOGIN_EXTENSION = "XLogin";

    /** Refusing every connection until XLogin is up; {@code null} once it is, or when not needed. */
    private volatile org.bukkit.event.Listener loginLockdown;

    /** Whether XLogin has started since XCore did. */
    private volatile boolean loginUp;

    /** Whether addons.yml gained a key since it was last written. */
    private boolean dataConfigDirty;
    private Object redisHealthTask;
    private WebPanel webPanel;
    private CoinsManager coinsManager;
    private long startTimeMillis;
    private FileConfiguration addonsConfig;
    private File addonsFile;

    /** Where {@code addons.yml} stops being YAML and why, or {@code null} when it was read. */
    private volatile String addonsProblem;

    /** Set when a failed start has already released everything: only the login lock is left. */
    private boolean halted;

    /** The database API addons build their tables and queries with. */
    private fr.xyness.XCore.Database.TableManager tableManager;

    /** The mailbox addons hand things to when a player cannot take them right away. */
    private fr.xyness.XCore.Delivery.DeliveryService deliveryService;

    /** One way to move money, whichever plugin holds it. Built with the instance: it holds no state of the start. */
    private final fr.xyness.XCore.Economy.Money money = new fr.xyness.XCore.Economy.Money(this);

    /** One queue for every Discord webhook in the installation. */
    private fr.xyness.XCore.Integrations.DiscordNotifier discordNotifier;

    /** The rankings addons declare, refreshed on one timer. */
    private fr.xyness.XCore.Leaderboards.LeaderboardService leaderboards;

    /** Who else is up, and where the players are. */
    private fr.xyness.XCore.Network.NetworkRegistry network;

    /** Group and rank lookups, whichever permission plugin is installed. */
    private fr.xyness.XCore.Integrations.RankResolver ranks;

    /** The plugins that follow a returning player onto a new UUID. */
    private fr.xyness.XCore.Identity.UuidChanges uuidChanges;

    /** Sends the buffered player columns to the database. */
    private Object writeFlushTask;

    /** Tells the other servers which players changed. */
    private Object invalidationTask;

    /** Prints the counts the tick-thread alarm owes the console. */
    private Object tickThreadReportTask;

    /** Players whose caches elsewhere still have to be dropped. */
    private final java.util.Set<String> pendingInvalidations = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        // Read anyway, a file that is not valid YAML came back empty: every switch then looked
        // new, was written with its default over the file, and a plugin switched off ran again
        // while one that shared its data across servers stopped doing so. It is left alone now, and
        // XCore says why it does not start (see start()).
        this.addonsProblem = fr.xyness.XCore.Utils.YamlFiles.problem(addonsFile);
        this.addonsConfig = addonsProblem == null
                ? YamlConfiguration.loadConfiguration(addonsFile) : new YamlConfiguration();

        // Load addons early so their onLoad() runs during server load phase
        // (allows addons to hook into Netty, register protocol handlers, etc.)
        this.addonManager = new AddonManager(this);
        addonManager.loadAddons();
        logger.sendRawBar();
    }

    @Override
    public void onEnable() {
        if (start()) return;
        if (addonManager != null && addonManager.foundJarOf("XBans") && isAddonEnabled("XBans")) {
            logger.sendSevere("XBans is still the 1.x addon here, which runs inside XCore: no ban is enforced"
                    + " until XCore starts.");
        }
        if (closedAfterFailedStart()) return;
        Bukkit.getServer().getPluginManager().disablePlugin(this);
    }

    @Override
    public void onDisable() {
        if (!halted) stop();
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
     * Copies the web dashboard out of the jar into {@code plugins/XCore/web/}, every file of it.
     *
     * <p>The dashboard used to be three files, named here one by one. It is now a folder —
     * stylesheets, script modules, fonts — and a file added to it must not depend on someone
     * remembering to list it here: the jar is read for everything under {@code web/}. Every start
     * overwrites them, so an update is served as soon as the server is back.</p>
     *
     * <p>The two files the previous dashboard was made of are removed when the jar no longer
     * carries them, so nothing stale is left for the panel to serve.</p>
     */
    private void extractDashboard() {
        java.util.Set<String> shipped = new java.util.HashSet<>();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(getFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("web/")) continue;
                shipped.add(name);
                try {
                    saveResource(name, true);
                } catch (Exception e) {
                    logger.sendDebug("Failed to save web resource '" + name + "': " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.sendWarning("Could not read the web dashboard from the jar: " + e.getMessage());
            return;
        }
        for (String old : new String[]{"web/app.js", "web/style.css"}) {
            if (shipped.contains(old)) continue;
            File stale = new File(getDataFolder(), old);
            if (stale.isFile() && !stale.delete()) logger.sendDebug("Could not remove the old " + old + ".");
        }
    }

    /**
     * Main startup sequence.
     *
     * @return {@code true} if startup was successful.
     */
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
        // Both files are looked at before refusing, so that one start names every line to fix.
        boolean addonsRead = addonsFileRead();
        if (!updateConfigWithDefaults() || !addonsRead) return false;
        FileConfiguration config = getConfig();
        String configuredType = config.getString("database-type", "sqlite");
        try {
            databaseType = DatabaseType.valueOf(configuredType.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.sendWarning("Unknown database-type '" + configuredType + "'. Falling back to SQLITE.");
            databaseType = DatabaseType.SQLITE;
        }
        this.dialect = SqlDialect.of(databaseType);

        // ---- Lang ----
        langManager = new LangManager(this);
        logger.setDebug(config.getBoolean("debug", false));
        migrateUuidChanges = config.getBoolean("migrate-uuid-changes", true);
        profiler.setEnabled(config.getBoolean("profiling", false));

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
                    // SQLite serialises writers, and without busy_timeout a blocked writer fails
                    // outright with SQLITE_BUSY instead of waiting — which is how an addon
                    // creating its tables while another one writes can refuse to start.
                    //
                    // journal_mode is deliberately NOT set here: it would be negotiated by every
                    // pooled connection at once, and they collide. It is applied once below, on a
                    // single connection, and persists in the file from then on.
                    hikaConfig.setJdbcUrl("jdbc:sqlite:plugins/XCore/storage.db"
                        + "?busy_timeout=10000&synchronous=NORMAL&foreign_keys=on");
                    hikaConfig.setPoolName("SQLitePool");
                    hikaConfig.setMaximumPoolSize(4); hikaConfig.setMinimumIdle(1);
                    hikaConfig.setIdleTimeout(60000); hikaConfig.setMaxLifetime(600000);
                    logger.sendInfo("Using SQLite database.");
                }
            }

            hikaConfig.addDataSourceProperty("socketTimeout", "30000");
            hikaConfig.setConnectionTimeout(10000);

            this.dataSource = new fr.xyness.XCore.Database.GuardedDataSource(hikaConfig, logger);
            // The pool names whoever queries the database from a tick thread while the server runs,
            // always. Debug mode also names what does it while the server starts or stops.
            fr.xyness.XCore.Database.GuardedDataSource.setWarnOnTickThread(
                    config.getBoolean("debug", false) || config.getBoolean("profiling", false));

            if (databaseType == DatabaseType.SQLITE) {
                // Once, before any addon opens a connection. WAL is a property of the database
                // file, so every later connection inherits it: readers stop blocking the writer.
                try (Connection connection = dataSource.getConnection();
                     Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                } catch (SQLException e) {
                    logger.sendWarning("Could not switch SQLite to WAL: " + e.getMessage()
                        + ". Concurrent writes may be refused under load.");
                }
            }
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
                        // Case-insensitive lookups go through lower(player_name) on this engine, and
                        // only a functional index can serve them.
                        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_name_lower ON players (lower(player_name));");
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
                        // Name lookups compare with COLLATE NOCASE; the index has to carry the same
                        // collation or it simply is not used.
                        stmt.addBatch("CREATE INDEX IF NOT EXISTS idx_players_name_nocase ON players (player_name COLLATE NOCASE);");
                        stmt.executeBatch();
                    }
                }
            }
        } catch (SQLException e) {
            logger.sendError("Failed to initialize database : " + e.getMessage());
            return false;
        } catch (Throwable t) {
            // A missing JDBC driver surfaces as an Error, not an Exception. Say what to do about it
            // rather than printing a stack trace nobody can act on.
            logger.sendError("Failed to initialize the " + databaseType.name() + " database : " + t);
            logger.sendError("If you have just changed 'database-type', restart once so the driver can be downloaded.");
            return false;
        }

        // ---- Database pool ----
        // Two threads per connection: enough to keep every connection busy while one of them is
        // being handed back, and not so many that they queue up waiting for one.
        this.dbExecutor = buildPool("XCore-DB", Math.max(4, dataSource.getMaximumPoolSize() * 2));
        this.tableManager = new fr.xyness.XCore.Database.TableManager(dataSource, databaseType, dbExecutor);

        // ---- PlayerDAO ----
        this.playerDAO = new PlayerDAO(this, dbExecutor);
        playerDAO.loadExtraColumnsFromMetadata();

        // Built-in activity tracking columns
        new ColumnBuilder(this)
            .addColumn("last_login", ColumnType.TEXT)
            .addColumn("last_logout", ColumnType.TEXT)
            .addColumn("playtime", ColumnType.BIGINT).defaultValue(0)
            .apply();

        // The dashboard lists players most recently seen first, which is a sort of the whole table
        // without this.
        try (Connection connection = dataSource.getConnection()) {
            fr.xyness.XCore.Database.SqlUtils.createIndexIfNotExists(connection, databaseType,
                    "idx_players_last_login", "players", "last_login");
        } catch (SQLException e) {
            logger.sendWarning("Could not index players.last_login : " + e.getMessage());
        }
        // A player unknown under their UUID is looked for by Mojang UUID, during their login and with
        // the login waiting: a scan of the whole table per new player without this.
        try (Connection connection = dataSource.getConnection()) {
            fr.xyness.XCore.Database.SqlUtils.createIndexIfNotExists(connection, databaseType,
                    "idx_players_mojang", "players", "mojang_uuid");
        } catch (SQLException e) {
            logger.sendWarning("Could not index players.mojang_uuid : " + e.getMessage());
        }

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
            } catch (Throwable e) {
                // Throwable, not Exception: with the driver now downloaded on demand, a Redis block
                // switched on after the fact fails with NoClassDefFoundError — an Error — and that
                // must degrade to "no Redis", never take the server down.
                logger.sendError("Failed to connect to Redis : " + e.getMessage());
                logger.sendWarning("Falling back to local cache only. If you have just enabled Redis, restart once so the driver can be downloaded.");
                if (jedisPool != null) { jedisPool.close(); jedisPool = null; }
            }
        }

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

        // Messages are handled on the work pool and written out on the database pool: the database
        // transport opens a connection for every batch it sends.
        this.syncManager = new SyncManager(
            jedisPool, dataSource, databaseType, executor, dbExecutor,
            pollSeconds * 20, retentionSeconds,
            getServerName(),
            logger::sendDebug, logger::sendWarning, logger::sendError
        );

        // XCore's own channel. CACHE_CLEAR follows a bulk write such as /eco resetall, PLAYER_UPDATE
        // names the players whose row just changed so the other servers drop their copy.
        syncManager.registerChannel(SYNC_CHANNEL, SYNC_PROTOCOL, message -> {
            switch (message.action()) {
                case "CACHE_CLEAR" -> {
                    clearAndWarmPlayerCache();
                    logger.sendDebug("Player caches cleared by a cross-server request.");
                }
                case "PLAYER_UPDATE" -> invalidatePlayers(message.key());
                default -> { }
            }
        });

        // The write buffer says which players reached the database, so the notice never goes out
        // before the value is readable.
        playerDAO.setFlushListener(uuids -> {
            if (syncManager == null || !syncManager.isRunning()) return;
            pendingInvalidations.addAll(uuids);
        });

        if (crossServerEnabled) {
            syncManager.start();
            logger.sendInfo("Cross-server sync started.");
        }

        // ---- GUI ----
        getServer().getPluginManager().registerEvents(new fr.xyness.XCore.Gui.PagedGuiListener(), this);

        // ---- Listeners ----
        // Before the listener that calls it, and before any addon or plugin enables and registers.
        this.uuidChanges = new fr.xyness.XCore.Identity.UuidChanges(executor, logger);
        // Registered now, before every addon and plugin built on XCore: within a priority the server
        // calls listeners in the order they were registered, so XCore's pre-login pass is the first
        // of its priority. See PlayerListener for why that priority is LOW.
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // A server that has XLogin takes no one until XLogin is up — the 1.x addon, enabled below, or
        // the 2.0 plugin, enabled after XCore — and keeps taking no one if it never comes up.
        armLoginLockdown();

        // Addons are not Bukkit plugins, so /plugins cannot see them. Ours takes the command's
        // place in the map — short and bukkit: forms alike — and prints Paper's own list with one
        // more section. Set plugins-command.override to false to hand it back.
        if (config.getBoolean("plugins-command.override", true)) {
            fr.xyness.XCore.Commands.PluginsCommand.install(this);
        }

        // ---- Commands ----
        new XCoreCommand(this).register();

        // ---- Floodgate (Bedrock) ----
        FloodgateHook.init();

        // ---- Addon Listener Registry ----
        this.listenerRegistry = new AddonListenerRegistry(this);

        // ---- Web dashboard (must start BEFORE addons so they can register modules) ----
        if (config.getBoolean("web-dashboard.enabled", false)) {
            extractDashboard();
            int webPort = clamp(config.getInt("web-dashboard.port", 8085), 1, 65535, "web-dashboard.port");
            boolean metricsPublic = config.getBoolean("web-dashboard.metrics-public", true);

            // There is no token to configure: /xcore dashboard issues one, scoped to the player who
            // asked and revocable. A key sitting in a config file cannot be either.
            webPanel = new WebPanel(this, getDataFolder(), webPort, metricsPublic,
                config.getString("web-dashboard.cors-origin", "*"),
                config.getString("web-dashboard.bind-address", "0.0.0.0"));
            try {
                webPanel.start();
            } catch (Exception e) {
                logger.sendError("Failed to start web dashboard: " + e.getMessage());
                webPanel = null;
            }
        }

        // ---- Economy (requires Vault) ----
        // Vault is only needed to *publish* the economy to other plugins. Balances, columns,
        // /eco, placeholders and the web module all work without it.
        boolean economyEnabled = config.getBoolean("economy.enabled", true);
        boolean vaultPresent = Bukkit.getPluginManager().getPlugin("Vault") != null;
        if (economyEnabled) {
            try {
                coinsManager = new CoinsManager(this);

                // Register columns for currencies. They are written straight through rather than
                // buffered: balance operations also run their own arithmetic in the database, and a
                // delayed write would land on top of one of those.
                for (var currency : coinsManager.getCurrencies()) {
                    String col = coinsManager.col(currency.getId());
                    XCoreApiProvider.get().columnBuilder()
                        .addColumn(col, ColumnType.DOUBLE).defaultValue(currency.getStartingBalance()).notNull()
                        .apply();
                    playerDAO.writeBuffer().writeThrough(col);
                }

                // Vault provider (optional)
                if (vaultPresent) {
                    try {
                        var provider = new VaultEconomyProvider(coinsManager);
                        Bukkit.getServicesManager().register(
                            net.milkbowl.vault.economy.Economy.class, provider, this,
                            org.bukkit.plugin.ServicePriority.Highest);
                        logger.sendInfo("Vault economy provider registered.");
                    } catch (Throwable e) {
                        logger.sendDebug("Failed to register Vault provider: " + e.getMessage());
                    }
                } else {
                    logger.sendInfo("Vault not installed : the economy runs internally only.");
                }

                // Commands
                new EcoCommand(this, coinsManager, langManager).register();

                // PlaceholderAPI
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    new EconomyExpansion(coinsManager).register();
                }

                // Web module
                if (webPanel != null) {
                    webPanel.registerModule(new EconomyWebModule(this, coinsManager));
                }

                // Transaction log: queued by CoinsManager, written as one batch every second.
                schedulerAdapter.runAsyncTaskTimer(coinsManager::flushTransactions, 20L, 20L);

                // Scheduled payouts — one statement for every online player, not one per player.
                if (config.getBoolean("economy.scheduled-payouts.enabled", false)) {
                    long interval = config.getLong("economy.scheduled-payouts.interval-minutes", 60) * 60 * 20;
                    double amount = config.getDouble("economy.scheduled-payouts.amount", 100);
                    String currency = config.getString("economy.scheduled-payouts.currency", "coins");
                    schedulerAdapter.runAsyncTaskTimer(() -> {
                        List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
                        if (online.isEmpty()) return;
                        // What is credited: the amount at the currency's precision, rounded down.
                        double paid = coinsManager.amountOf(currency, amount);
                        coinsManager.depositAll(online, currency, amount).thenAccept(applied -> {
                            for (UUID id : applied.keySet()) {
                                Player player = Bukkit.getPlayer(id);
                                // Money appearing with no explanation reads as a bug; the message for
                                // this existed in the language file but nothing ever sent it.
                                if (player != null) notifyEconomy(player, "eco-payout-received", currency, paid);
                            }
                        });
                    }, interval, interval);
                }

                // Interest — same idea: the multiplication happens inside the database.
                if (config.getBoolean("economy.interest.enabled", false)) {
                    long interval = config.getLong("economy.interest.interval-minutes", 1440) * 60 * 20;
                    double rate = config.getDouble("economy.interest.rate", 0.01);
                    String currency = config.getString("economy.interest.currency", "coins");
                    schedulerAdapter.runAsyncTaskTimer(() -> {
                        List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
                        if (online.isEmpty()) return;
                        coinsManager.creditInterest(online, currency, rate).thenAccept(credited ->
                            credited.forEach((id, credit) -> {
                                Player player = Bukkit.getPlayer(id);
                                // What was added, as added: worked out again from the balance, it
                                // could read a cent away from it.
                                if (player == null || credit.amount() <= 0) return;
                                notifyEconomy(player, "eco-interest-received", currency, credit.amount());
                            }));
                    }, interval, interval);
                }

            } catch (Exception e) {
                logger.sendError("Failed to initialize economy: " + e.getMessage());
                coinsManager = null;
            }
        }

        // ---- Shared services ----
        // All of these exist before the addons are enabled, because addons reach for them in
        // onEnable().
        this.ranks = new fr.xyness.XCore.Integrations.RankResolver();
        this.deliveryService = new fr.xyness.XCore.Delivery.DeliveryService(this);
        this.discordNotifier = new fr.xyness.XCore.Integrations.DiscordNotifier(this);
        discordNotifier.start();
        this.leaderboards = new fr.xyness.XCore.Leaderboards.LeaderboardService(this);
        leaderboards.start();
        this.network = new fr.xyness.XCore.Network.NetworkRegistry(this);
        network.start();

        // Call register() on the expansion itself. Passing it to a method typed on
        // PlaceholderExpansion makes the JVM load that class while XCore is loading, which is
        // before PlaceholderAPI exists.
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new fr.xyness.XCore.Integrations.XCoreExpansion(this).register();
            } catch (Throwable t) {
                logger.sendWarning("Could not register the XCore placeholders : " + t.getMessage());
            }
        }

        // Buffered column writes reach the database once a second; the invalidation that follows
        // goes out a little more often so another server does not read a stale row for long.
        writeFlushTask = schedulerAdapter.runAsyncTaskTimer(() -> playerDAO.writeBuffer().flush(), 20L, 20L);
        invalidationTask = schedulerAdapter.runAsyncTaskTimer(this::publishInvalidations, 20L, 5L);

        // ---- Addons ----
        addonManager.enableAddons();
        saveDataConfig();

        // ---- Prefetch online players ----
        List<UUID> onlineUuids = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        if (!onlineUuids.isEmpty()) {
            logger.sendInfo("Prefetching data for " + onlineUuids.size() + " online player(s).");
            playerCache.getPlayers(onlineUuids).thenAccept(map ->
                logger.sendInfo("Prefetched " + map.size() + " player(s) into cache."));
            // They joined before this start, so no join read their balances.
            if (coinsManager != null) onlineUuids.forEach(coinsManager::hold);
        }

        // ---- Tick-thread alarm ----
        // Counting from a second after the first tick: until then the main thread is the server
        // starting — every plugin building its tables there — and nobody is waiting on it.
        schedulerAdapter.runGlobalTaskLater(
                () -> fr.xyness.XCore.Database.GuardedDataSource.setServerRunning(true), 20L);
        // A call site that went on taking connections after its warning gets its count every five
        // minutes, even once it has gone quiet; this is what notices it has something to say.
        tickThreadReportTask = schedulerAdapter.runAsyncTaskTimer(() -> {
            if (dataSource instanceof fr.xyness.XCore.Database.GuardedDataSource guarded) guarded.reportPending();
        }, 1200L, 1200L);

        running = true;

        long end = System.nanoTime();
        long durationInMillis = (end - startTime) / 1_000_000;
        logger.sendInfo("Plugin loaded in <aqua>" + methods.getNumberSeparate(durationInMillis) + "ms</aqua> <green>\u2713");
        logger.sendRawBar();
        return true;
    }

    /**
     * Refuses every connection until XLogin is up, on a server that has XLogin.
     *
     * <p>XLogin is the authentication of an offline-mode server: without it nobody checks who is
     * who. It closes the server itself when its own start fails — but only once it runs at all. Its
     * 2.0 jar not in place yet (deleted with the 1.x jar before the new one was put in), refused by
     * the server, or put in the wrong folder: nothing of XLogin runs, and nothing closed anything.
     * So XCore closes the door first, from its own start, whenever XLogin is expected here — its
     * settings exist ({@code plugins/XLogin/} or the old addon folder) or a jar of it is installed —
     * and opens it the moment XLogin, the 2.0 plugin or the 1.x addon, has started
     * ({@link #extensionEnabled(String)}). If it never does, the door stays shut.</p>
     *
     * <p>{@code addons.XLogin: false} in {@code addons.yml} means XLogin is not wanted: nothing is
     * refused. XLogin's own {@code security.refuse-connections-on-failure} is honoured, read from
     * wherever its files are.</p>
     */
    private void armLoginLockdown() {
        Boolean refuse = loginRefusal();
        if (refuse == null) return;
        if (refuse) {
            org.bukkit.event.Listener lockdown =
                    new fr.xyness.XCore.Listeners.LoginLockdownListener(langManager.getComponent("auth-unavailable"));
            getServer().getPluginManager().registerEvents(lockdown, this);
            loginLockdown = lockdown;
            logger.sendDebug("Connections are refused until XLogin is running.");
        }
        // Once the server is up: by then XLogin has started, or never will.
        schedulerAdapter.runGlobalTaskLater(this::reportLoginLockdown, 1L);
    }

    /**
     * Whether XLogin is expected on this server, and what it wants done while it is not running.
     *
     * @return {@code null} when XLogin is not expected here or is switched off, {@code TRUE} when
     *         connections are to be refused without it, {@code FALSE} when its settings say not to.
     */
    private Boolean loginRefusal() {
        if (!isAddonEnabled(LOGIN_EXTENSION)) return null;

        File pluginsDir = getDataFolder().getParentFile();
        File settings = new File(pluginsDir, LOGIN_EXTENSION + File.separator + "config.yml");
        if (!settings.isFile()) {
            File oldFolder = fr.xyness.XCore.Extension.DataFolderMigration.oldFolder(pluginsDir, LOGIN_EXTENSION);
            settings = oldFolder == null ? null : new File(oldFolder, "config.yml");
            if (settings != null && !settings.isFile()) settings = null;
        }
        boolean expected = settings != null
                || Bukkit.getPluginManager().getPlugin(LOGIN_EXTENSION) != null
                || (addonManager != null && addonManager.foundJarOf(LOGIN_EXTENSION));
        if (!expected) return null;

        boolean refuse = true;
        // A settings file that cannot be read says nothing: XLogin will report it; the careful
        // side meanwhile. Read anyway it came back empty, which gave the same answer by accident.
        if (settings != null && fr.xyness.XCore.Utils.YamlFiles.problem(settings) == null) {
            try {
                refuse = YamlConfiguration.loadConfiguration(settings).getBoolean("security.refuse-connections-on-failure", true);
            } catch (RuntimeException e) {
                // Same.
            }
        }
        return refuse;
    }

    /**
     * Keeps the server closed when XCore itself did not start, on a server that has XLogin.
     *
     * <p>{@link #armLoginLockdown()} only closes the door once the start has got that far. A start
     * refused before — a {@code config.yml} that cannot be read, a database that does not answer —
     * left a server whose XLogin is still the 1.x addon, or whose XLogin 2.0 jar never loaded,
     * taking anyone under any name: nothing of XLogin runs there to close it. The server removes
     * the listeners of a plugin it disables, so XCore stays enabled, holding that one listener and
     * nothing else; everything the start had opened is released first.</p>
     *
     * @return {@code true} when XCore stays enabled to keep the door shut.
     */
    private boolean closedAfterFailedStart() {
        Boolean refuse = loginRefusal();
        if (refuse == null) return false;
        if (!refuse) {
            logger.sendSevere("XCore did not start, so XLogin does not run, and security.refuse-connections-on-failure"
                    + " is false in its config.yml: this server accepts players WITHOUT any authentication.");
            return false;
        }

        net.kyori.adventure.text.Component message = null;
        try {
            if (langManager != null) message = langManager.getComponent("auth-unavailable");
        } catch (Throwable ignored) {
            // The language files were not reached: the plain text below.
        }
        if (message == null) {
            message = net.kyori.adventure.text.Component.text(
                    "Authentication is unavailable on this server right now.\nPlease try again in a moment.",
                    net.kyori.adventure.text.format.NamedTextColor.RED);
        }

        stop();
        halted = true;
        try {
            getServer().getPluginManager().registerEvents(
                    new fr.xyness.XCore.Listeners.LoginLockdownListener(message), this);
        } catch (Throwable t) {
            logger.sendSevere("The server could not be closed to connections: " + t);
            return false;
        }
        logger.sendSevere("XCore did not start and XLogin runs on it: every connection is refused until it does."
                + " Fix what the lines above name, then restart.");
        logger.sendSevere("To open this server WITHOUT any authentication instead, set addons.XLogin: false"
                + " in plugins/XCore/addons.yml.");
        return true;
    }

    /** Says, once the server is up, that XLogin never came: what that leaves, and the way out. */
    private void reportLoginLockdown() {
        if (loginUp) return;
        if (loginLockdown != null) {
            logger.sendSevere("XLogin is not running: every connection is refused until it is. Put the XLogin 2.0"
                    + " jar in plugins/ (or see above why it did not start), then restart.");
            logger.sendSevere("To open this server WITHOUT any authentication instead, set addons.XLogin: false"
                    + " in plugins/XCore/addons.yml.");
        } else {
            logger.sendSevere("XLogin is not running and security.refuse-connections-on-failure is false in its"
                    + " config.yml: this server accepts players WITHOUT any authentication.");
        }
    }

    /**
     * Told when an addon or a plugin built on XCore has started. Internal: {@code XPlugin} and the
     * addon manager call it.
     *
     * @param name The extension's name.
     */
    public void extensionEnabled(String name) {
        if (!LOGIN_EXTENSION.equalsIgnoreCase(name)) return;
        loginUp = true;
        org.bukkit.event.Listener lockdown = loginLockdown;
        if (lockdown == null) return;
        loginLockdown = null;
        HandlerList.unregisterAll(lockdown);
        logger.sendDebug("XLogin is running: connections are accepted again.");
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Main shutdown sequence.
     */
    public void stop() {
        running = false;
        // From here the main thread is saving, the plugins first: nothing it does is a stall anyone sees.
        fr.xyness.XCore.Database.GuardedDataSource.setServerRunning(false);
        logger.sendRawBar();
        logger.sendInfo("Stopping the plugin.");

        // Put /plugins back before anything else: the addon list it prints is about to be empty.
        fr.xyness.XCore.Commands.PluginsCommand.uninstall();

        // Plugins built on XCore still running: only when XCore stops on its own, since the server
        // disables them first otherwise. They go before the pools they write to.
        extensions.disablePlugins();

        // Addons first (reverse dependency order)
        if (addonManager != null) addonManager.disableAddons();

        // Stop the timers before flushing, so nothing is queued behind our back.
        if (writeFlushTask != null) schedulerAdapter.cancelTask(writeFlushTask);
        if (invalidationTask != null) schedulerAdapter.cancelTask(invalidationTask);
        if (tickThreadReportTask != null) schedulerAdapter.cancelTask(tickThreadReportTask);

        // Anything still buffered belongs in the database, not in memory.
        if (playerDAO != null) playerDAO.writeBuffer().flush();
        if (coinsManager != null) coinsManager.flushTransactions();

        // Stop the shared services
        if (network != null) network.stop();
        if (leaderboards != null) leaderboards.stop();
        if (discordNotifier != null) discordNotifier.stop();

        // Stop web dashboard
        if (webPanel != null) webPanel.stop();

        // Stop cross-server sync and health check
        if (syncManager != null) syncManager.stop();
        if (redisHealthTask != null) schedulerAdapter.cancelTask(redisHealthTask);

        // Shutdown caches
        if (playerCache != null) playerCache.shutdown();

        // Graceful shutdown: wait for in-flight operations. The database pool goes last, because
        // the flush above is sitting in it and the connection pool must outlive it.
        shutdown(executor, "XCore-Work");
        shutdown(dbExecutor, "XCore-DB");
        rejectionPolicies.forEach(fr.xyness.XCore.Utils.RejectedTaskPolicy::shutdown);

        if (dataSource != null) dataSource.close();
        if (jedisPool != null) jedisPool.close();

        HandlerList.unregisterAll(this);
        XCoreApiProvider.unregister();

        logger.sendInfo("Plugin disabled successfully.");
        logger.sendRawBar();
    }

    /**
     * Stops a pool and waits for what is already running.
     *
     * @param pool The pool to stop, may be {@code null}.
     * @param name Its name, for the warning.
     */
    private void shutdown(ExecutorService pool, String name) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.sendWarning(name + " did not terminate in 10s, forcing shutdown.");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Config update
    // -------------------------------------------------------------------------

    /**
     * Adds the settings a new version introduces, leaving everything else alone.
     *
     * <p>The currencies and the exchange rates are named by whoever configures them, so they are
     * declared as protected: a renamed entry must not come back on the next start.</p>
     */
    private boolean updateConfigWithDefaults() {
        saveDefaultConfig();
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) return true;

        // A file that is not valid YAML is neither merged into nor started on. Read anyway, it came
        // back empty: the defaults were then written over it — database settings, currencies and
        // all, with no copy kept — and the server went on with an empty local database in place of
        // the one that had been configured.
        String problem = fr.xyness.XCore.Utils.YamlFiles.problem(configFile);
        if (problem != null) {
            logger.sendError("config.yml is not valid YAML: " + fr.xyness.XCore.Utils.YamlFiles.forConsole(problem));
            logger.sendError("The file was left untouched. XCore does not start on default settings: they would"
                    + " point every plugin at an empty local database. Fix that line, then restart.");
            return false;
        }
        // An empty file is what an interrupted upload or a full disk leaves. It parses, and the
        // merge below would fill it with the defaults: the same start on an empty local database.
        if (fr.xyness.XCore.Utils.YamlFiles.isBlank(configFile)) {
            logger.sendError("config.yml is empty. XCore does not start on default settings: they would point every"
                    + " plugin at an empty local database.");
            logger.sendError("Put your file back, or delete this one to get the default file, then restart.");
            return false;
        }

        FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream defStream = getResource("config.yml")) {
            if (defStream == null) return true;
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defStream, StandardCharsets.UTF_8));

            List<String> added = fr.xyness.XCore.Utils.ConfigMerger.addMissingKeys(
                    diskConfig, defConfig, "economy.currencies", "economy.exchange.rates");

            if (!added.isEmpty()) {
                diskConfig.save(configFile);
                logger.sendDebug("Added " + added.size() + " new setting(s) to config.yml.");
            }
        } catch (IOException e) {
            logger.sendError("Error updating config with defaults: " + e.getMessage());
        }
        reloadConfig();
        return true;
    }

    /** Set once {@code config.yml} has been read from disk, so a later reload has something to keep. */
    private boolean configRead;

    /** Where {@code config.yml} stopped being YAML at the last reload, or {@code null} when it was read. */
    private volatile String configProblem;

    /**
     * @return Where {@code config.yml} stops being YAML and why, or {@code null} when the last
     *         reload read it. While it is set, the settings in use are the ones read before.
     */
    public String configProblem() {
        return configProblem;
    }

    /**
     * Says why XCore does not start on an {@code addons.yml} that cannot be read.
     *
     * @return Whether the file was read.
     */
    private boolean addonsFileRead() {
        if (addonsProblem == null) return true;
        logger.sendError("addons.yml is not valid YAML: " + fr.xyness.XCore.Utils.YamlFiles.forConsole(addonsProblem));
        logger.sendError("The file was left untouched. XCore does not start without it: it says which plugins run"
                + " and which of them share their data across servers. Fix that line, then restart.");
        return false;
    }

    /**
     * Reloads {@code config.yml}, keeping the settings already read when the file has stopped
     * being valid YAML.
     *
     * <p>{@code /xcore reload} and {@code /eco reload} both come through here. On a file with a typo
     * the server's own loader answers with the bundled defaults, and the currencies defined by the
     * administrator were replaced by the shipped one for as long as the typo stayed.</p>
     */
    @Override
    public void reloadConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        String problem = fr.xyness.XCore.Utils.YamlFiles.problem(configFile);
        configProblem = problem;
        if (problem != null && configRead) {
            if (logger != null) {
                logger.sendError("config.yml is not valid YAML: " + fr.xyness.XCore.Utils.YamlFiles.forConsole(problem));
                logger.sendError("The file was left untouched. The settings read before stay in use until it is fixed.");
            }
            return;
        }
        super.reloadConfig();
        if (problem == null) configRead = true;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** @return The XCore logger instance. */
    public Logger logger() { return logger; }

    /** @return The public API, the same object addons get from {@code XCoreApiProvider}. */
    public fr.xyness.XCore.API.XCoreApi api() { return XCoreApiProvider.get(); }

    /** @return The per-addon profiler behind {@code /xcore profile}. */
    public fr.xyness.XCore.Utils.Profiler profiler() { return profiler; }

    /** @return The Bukkit/Folia scheduler adapter. */
    public SchedulerAdapter schedulerAdapter() { return schedulerAdapter; }

    /** @return The shared utility methods. */
    public Methods methods() { return methods; }

    /** @return The shared executor service, for work that is not a database call. */
    public ExecutorService getExecutor() { return executor; }

    /** @return The pool database work runs on. */
    public ExecutorService getDbExecutor() { return dbExecutor; }

    /**
     * Whether a returning player whose UUID changed is moved onto the new one.
     *
     * <p>Held rather than read: the join handler asked the configuration for it on every
     * connection, and a listener reading config.yml is what a Settings holder exists to avoid.</p>
     *
     * @return {@code true} when the row is migrated.
     */
    public boolean isMigrateUuidChanges() { return migrateUuidChanges; }

    /** @return The table and query builder entry point. */
    public fr.xyness.XCore.Database.TableManager tableManager() { return tableManager; }

    /** @return The mailbox for things a player could not be handed straight away. */
    public fr.xyness.XCore.Delivery.DeliveryService delivery() { return deliveryService; }

    /**
     * Returns the money service: withdrawals, deposits and rounding in an XCore currency or in
     * the economy another plugin registered with Vault, behind one set of methods.
     *
     * @return The {@link fr.xyness.XCore.Economy.Money} service.
     * @since 2.1
     */
    public fr.xyness.XCore.Economy.Money money() { return money; }

    /** @return The shared Discord webhook sender. */
    public fr.xyness.XCore.Integrations.DiscordNotifier discord() { return discordNotifier; }

    /** @return The leaderboard service. */
    public fr.xyness.XCore.Leaderboards.LeaderboardService leaderboards() { return leaderboards; }

    /** @return The network registry: which servers are up, and where the players are. */
    public fr.xyness.XCore.Network.NetworkRegistry network() { return network; }

    /** @return Group and rank lookups. */
    public fr.xyness.XCore.Integrations.RankResolver ranks() { return ranks; }

    /**
     * @return The plugins that follow a returning player onto a new UUID: where a plugin registers
     *         the handler that moves its own data.
     */
    public fr.xyness.XCore.Identity.UuidChanges uuidChanges() { return uuidChanges; }

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

    /** @return The cross-server sync manager. */
    public SyncManager getSyncManager() { return syncManager; }

    /**
     * Drops the L1 player cache and immediately reloads the online players from the database.
     * <p>
     * Used after a bulk write that bypasses the per-player write path (see
     * {@code /eco resetall}). The reload matters: {@code getPlayer(uuid)} is a
     * cache-only lookup, so without it every synchronous read would report "no data"
     * for online players until something triggered an async load.
     */
    public void clearAndWarmPlayerCache() {
        playerCache.clearAll();
        List<UUID> online = Bukkit.getOnlinePlayers().stream()
                .map(org.bukkit.entity.Player::getUniqueId)
                .collect(java.util.stream.Collectors.toList());
        if (!online.isEmpty()) playerCache.getPlayers(online);
    }

    /**
     * Drops the local copy of the players named in a sync message.
     *
     * @param joinedUuids The server UUIDs, separated by commas.
     */
    private void invalidatePlayers(String joinedUuids) {
        if (joinedUuids == null || joinedUuids.isBlank()) return;
        for (String raw : joinedUuids.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            try {
                UUID uuid = UUID.fromString(trimmed);
                playerCache.invalidateByUuid(uuid);
            } catch (IllegalArgumentException ignored) {
                // Not a UUID: nothing to invalidate.
            }
        }
    }

    /**
     * Has the other servers drop their copy of these players, with the next batch of notices.
     *
     * <p>For a write that does not go through the column buffer: a row moved onto a new UUID, whose
     * old copy another server would otherwise go on serving under the old UUID and the name.</p>
     *
     * @param uuids The players, by server UUID.
     */
    public void invalidateElsewhere(UUID... uuids) {
        if (syncManager == null || !syncManager.isRunning()) return;
        for (UUID uuid : uuids) {
            if (uuid != null) pendingInvalidations.add(uuid.toString());
        }
    }

    /**
     * Tells the other servers which players have just been written.
     *
     * <p>Sent in one message every quarter of a second rather than one per write, so a player being
     * paid ten times in a row costs a single notice.</p>
     */
    private void publishInvalidations() {
        if (pendingInvalidations.isEmpty()) return;
        if (syncManager == null || !syncManager.isRunning()) {
            pendingInvalidations.clear();
            return;
        }
        java.util.List<String> batch = new java.util.ArrayList<>(pendingInvalidations);
        pendingInvalidations.removeAll(batch);
        syncManager.publish(SYNC_CHANNEL, new fr.xyness.XCore.Sync.SyncMessage(
                "PLAYER_UPDATE", String.join(",", batch)));
    }

    /** @return The addon manager. */
    public AddonManager getAddonManager() { return addonManager; }

    /**
     * Returns every XCore extension: the addons, and the plugins built on
     * {@link fr.xyness.XCore.Extension.XPlugin}.
     *
     * @return The extension registry.
     */
    public ExtensionRegistry extensions() { return extensions; }

    /**
     * Whether XCore started and has not begun stopping: what a plugin built on it checks before
     * enabling, since the server enables a plugin even when the one it depends on failed to start.
     *
     * @return {@code true} while every shared service is up.
     */
    public boolean isRunning() { return running && isEnabled(); }

    /** @return The addon listener registry. */
    public AddonListenerRegistry getListenerRegistry() { return listenerRegistry; }

    /** @return The web dashboard panel, or {@code null} if disabled. */
    public WebPanel getWebPanel() { return webPanel; }

    /** @return The economy manager, or {@code null} if economy is disabled. */
    public CoinsManager getCoinsManager() { return coinsManager; }

    /** @return The economy language namespace, or {@code null} if economy is disabled. */

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
            dataConfigDirty = true;
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
            dataConfigDirty = true;
        }
    }

    /** Saves the addons.yml file (called once after all addons are loaded). */
    public void saveDataConfig() {
        // Not over a file that could not be read: what is in memory is not what it holds.
        if (addonsProblem != null) return;
        try {
            addonsConfig.save(addonsFile);
            dataConfigDirty = false;
        }
        catch (IOException e) { logger.sendError("Failed to save addons.yml: " + e.getMessage()); }
    }

    /**
     * Saves addons.yml only if a key was added since it was last written.
     *
     * <p>For the plugins built on XCore: they enable after XCore has written the file, one by one,
     * and the first start of each adds its switches. A start with nothing new writes nothing.</p>
     */
    public void saveDataConfigIfChanged() {
        if (dataConfigDirty) saveDataConfig();
    }


    /**
     * Tells a player about money that arrived without them asking for it.
     *
     * @param player   Who to tell.
     * @param key      Language key, taking {@code {amount}} and {@code {currency}}.
     * @param currency The currency id.
     * @param amount   How much was credited.
     */
    private void notifyEconomy(Player player, String key, String currency, double amount) {
        if (!player.isOnline()) return;
        schedulerAdapter.runEntityTask(player, () -> player.sendMessage(langManager.getComponent(key,
                "amount", coinsManager.format(currency, amount),
                "currency", currency)));
    }

    /**
     * The language every component of the installation speaks.
     *
     * <p>Set once here; addons inherit it unless they declare their own. Bundled translations are
     * English and French, and an addon with no file for the chosen language falls back to English
     * rather than printing raw keys.</p>
     *
     * @return The language code, lower-cased.
     */
    public String getLanguage() {
        String code = getConfig().getString("language", "en");
        return (code == null || code.isBlank()) ? "en" : code.trim().toLowerCase();
    }

    /** @return The configured server name for cross-server tagging. */
    public String getServerName() {
        // The name identifies this server everywhere — per-server columns, sync tags, the
        // dashboard — not only when a network is configured.
        return getConfig().getString("server-name", "default");
    }

    /** @return The plugin start time in milliseconds. */
    public long getStartTimeMillis() { return startTimeMillis; }

    /** @return The shared Gson instance. */
    public Gson getGson() { return gson; }
}
