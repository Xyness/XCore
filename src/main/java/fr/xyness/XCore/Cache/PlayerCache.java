package fr.xyness.XCore.Cache;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Function;

import fr.xyness.XCore.Models.PlayerTempData;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Multi-level player data cache with Mojang API integration and circuit breaker.
 * <p>
 * This is the XCore V2 port of PlayersAPI's PlayerCache. It manages two async
 * Caffeine caches (UUID-based and name-based) backed by Redis (L2) and a
 * database loader (L3). Mojang UUID and skin texture lookups use a dedicated
 * circuit breaker and rate limiter.
 * </p>
 * <p>
 * Unlike the generic {@link CacheRegion}, this class is specialized for the
 * {@code players} table and includes Mojang API integration, head texture
 * resolution, and cross-cache consistency between UUID and name lookups.
 * </p>
 *
 * @param <P> The player data type (must be serializable to/from JSON).
 */
public class PlayerCache<P> {

    private static final String NONE = "none";
    private static final String REDIS_PREFIX_UUID = "xcore:players:uuid:";
    private static final String REDIS_PREFIX_NAME = "xcore:players:name:";
    private static final String REDIS_PREFIX_MOJANG = "xcore:players:mojang:";
    private static final String REDIS_PREFIX_SKIN = "xcore:players:skin:";
    private static final String MOJANG_API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MOJANG_PROFILE_API_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final int MAX_RETRIES = 3;
    private static final long RATE_LIMIT_BASE_DELAY_MS = 1000;
    private static final int MAX_LOAD_RETRIES = 3;
    private static final long LOAD_RETRY_BASE_DELAY_MS = 500;

    private final Executor executor;
    private final JedisPool jedisPool;
    private final int redisTTL;
    private final AsyncLoadingCache<UUID, Optional<P>> uuidToPlayers;
    private final AsyncLoadingCache<String, Optional<P>> nameToPlayers;

    private final int apiTimeoutMs;
    private final String userAgent;
    private final Semaphore apiSemaphore;
    private final MojangCircuitBreaker circuitBreaker;
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { Thread t = new Thread(r, "XCore-PlayerCache-RetryScheduler"); t.setDaemon(true); return t; });

    private final LoadingCache<String, String> mojangUUIDCache;
    private final LoadingCache<String, String> skinCache;
    private final LoadingCache<UUID, PlayerTempData> playersTempCache;

    private final LongAdder mojangCacheHits = new LongAdder();
    private final LongAdder mojangCacheMisses = new LongAdder();
    private final LongAdder skinCacheHits = new LongAdder();
    private final LongAdder skinCacheMisses = new LongAdder();
    private final LongAdder apiCalls = new LongAdder();
    private final LongAdder apiFailures = new LongAdder();
    private final LongAdder dbReads = new LongAdder();
    private final LongAdder dbWrites = new LongAdder();
    private final LongAdder apiRateLimits = new LongAdder();

    // Addon-provided callbacks
    private final Function<P, String> serializer;
    private final Function<String, P> deserializer;
    private final Function<P, UUID> uuidExtractor;
    private final Function<P, String> nameExtractor;
    private final Function<P, String> textureExtractor;
    private final Function<P, String> mojangUuidExtractor;
    private final Function<String, CompletableFuture<Optional<P>>> findByUuidAsync;
    private final Function<String, CompletableFuture<Optional<P>>> findByNameAsync;
    private final Function<List<String>, CompletableFuture<Map<String, Optional<P>>>> findByUuidsAsync;
    private final LogCallback logDebug;
    private final LogCallback logWarning;
    private final LogCallback logError;

    /**
     * Functional interface for log callbacks.
     */
    @FunctionalInterface
    public interface LogCallback {
        void log(String message);
    }

    /**
     * Creates a new PlayerCache.
     *
     * @param builder The builder with all configuration and callbacks.
     */
    private PlayerCache(Builder<P> builder) {
        this.executor = builder.executor;
        this.jedisPool = builder.jedisPool;
        this.redisTTL = builder.redisTTL;
        this.apiTimeoutMs = builder.apiTimeoutMs;
        this.userAgent = builder.userAgent;
        this.apiSemaphore = new Semaphore(builder.maxApiConcurrency);
        this.circuitBreaker = new MojangCircuitBreaker(builder.cbThreshold, builder.cbOpenMinutes);
        this.serializer = builder.serializer;
        this.deserializer = builder.deserializer;
        this.uuidExtractor = builder.uuidExtractor;
        this.nameExtractor = builder.nameExtractor;
        this.textureExtractor = builder.textureExtractor;
        this.mojangUuidExtractor = builder.mojangUuidExtractor;
        this.findByUuidAsync = builder.findByUuidAsync;
        this.findByNameAsync = builder.findByNameAsync;
        this.findByUuidsAsync = builder.findByUuidsAsync;
        this.logDebug = builder.logDebug != null ? builder.logDebug : msg -> {};
        this.logWarning = builder.logWarning != null ? builder.logWarning : msg -> {};
        this.logError = builder.logError != null ? builder.logError : msg -> {};

        this.mojangUUIDCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS).maximumSize(builder.mojangCacheSize)
                .build(playerName -> {
                    String fromRedis = getStringFromRedis(REDIS_PREFIX_MOJANG + playerName.toLowerCase());
                    if (fromRedis != null) return fromRedis;
                    String uuid = getUUIDFromMojang(playerName);
                    String result = uuid == null ? NONE : uuid;
                    putStringToRedis(REDIS_PREFIX_MOJANG + playerName.toLowerCase(), result);
                    return result;
                });

        this.skinCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS).maximumSize(builder.mojangCacheSize)
                .build(mojangUUID -> {
                    if (mojangUUID == null || mojangUUID.equals(NONE)) return NONE;
                    String fromRedis = getStringFromRedis(REDIS_PREFIX_SKIN + mojangUUID);
                    if (fromRedis != null) return fromRedis;
                    String skin = getSkinURL(mojangUUID);
                    String result = skin == null ? NONE : skin;
                    putStringToRedis(REDIS_PREFIX_SKIN + mojangUUID, result);
                    return result;
                });

        this.uuidToPlayers = Caffeine.newBuilder()
                .maximumSize(builder.maxCacheSize).executor(executor).recordStats()
                .expireAfterAccess(builder.cacheTTLMinutes, TimeUnit.MINUTES)
                .removalListener((UUID key, Optional<P> value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (cause.wasEvicted() && value != null && value.isPresent()) {
                        removeFromRedis(value.get());
                        logDebug.log("L1 eviction triggered Redis cleanup for " + nameExtractor.apply(value.get()) + ".");
                    }
                })
                .buildAsync(new AsyncCacheLoader<UUID, Optional<P>>() {
                    @Override
                    public CompletableFuture<? extends Optional<P>> asyncLoad(UUID key, Executor exec) {
                        Optional<P> fromRedis = getPlayerFromRedis(REDIS_PREFIX_UUID + key);
                        if (fromRedis != null) return CompletableFuture.completedFuture(fromRedis);
                        dbReads.increment();
                        return findByUuidAsync.apply(key.toString()).thenApply(opt -> {
                            opt.ifPresent(PlayerCache.this::putToRedis);
                            return opt;
                        });
                    }

                    @Override
                    public CompletableFuture<? extends Map<? extends UUID, ? extends Optional<P>>> asyncLoadAll(
                            Set<? extends UUID> keys, Executor exec) {
                        Map<UUID, Optional<P>> result = new HashMap<>();
                        java.util.List<String> dbMisses = new java.util.ArrayList<>();

                        for (UUID key : keys) {
                            Optional<P> fromRedis = getPlayerFromRedis(REDIS_PREFIX_UUID + key);
                            if (fromRedis != null) {
                                result.put(key, fromRedis);
                            } else {
                                dbMisses.add(key.toString());
                            }
                        }

                        if (dbMisses.isEmpty()) {
                            return CompletableFuture.completedFuture(result);
                        }

                        if (findByUuidsAsync == null) {
                            // Fallback to individual lookups
                            CompletableFuture<?>[] futures = new CompletableFuture[dbMisses.size()];
                            for (int i = 0; i < dbMisses.size(); i++) {
                                String uuidStr = dbMisses.get(i);
                                UUID uuid = UUID.fromString(uuidStr);
                                dbReads.increment();
                                futures[i] = findByUuidAsync.apply(uuidStr).thenAccept(opt -> {
                                    opt.ifPresent(PlayerCache.this::putToRedis);
                                    result.put(uuid, opt);
                                });
                            }
                            return CompletableFuture.allOf(futures).thenApply(v -> result);
                        }

                        dbReads.add(dbMisses.size());
                        return findByUuidsAsync.apply(dbMisses).thenApply(dbResult -> {
                            for (String uuidStr : dbMisses) {
                                UUID uuid = UUID.fromString(uuidStr);
                                Optional<P> fromDb = dbResult.getOrDefault(uuidStr, Optional.empty());
                                fromDb.ifPresent(PlayerCache.this::putToRedis);
                                result.put(uuid, fromDb);
                            }
                            return result;
                        });
                    }
                });

        this.nameToPlayers = Caffeine.newBuilder()
                .maximumSize(builder.maxCacheSize).executor(executor).recordStats()
                .expireAfterAccess(builder.cacheTTLMinutes, TimeUnit.MINUTES)
                .buildAsync(new AsyncCacheLoader<String, Optional<P>>() {
                    @Override
                    public CompletableFuture<? extends Optional<P>> asyncLoad(String key, Executor exec) {
                        Optional<P> fromRedis = getPlayerFromRedis(REDIS_PREFIX_NAME + key.toLowerCase());
                        if (fromRedis != null) return CompletableFuture.completedFuture(fromRedis);
                        dbReads.increment();
                        return findByNameAsync.apply(key).thenApply(opt -> {
                            opt.ifPresent(PlayerCache.this::putToRedis);
                            return opt;
                        });
                    }
                });

        this.playersTempCache = Caffeine.newBuilder()
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build(uuid -> new PlayerTempData(uuid));
    }

    // -- Redis helpers --

    private String getStringFromRedis(String key) {
        if (jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) { return jedis.get(key); }
        catch (Exception e) { logError.log("Redis read error : " + e.getMessage()); return null; }
    }

    private void putStringToRedis(String key, String value) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) { jedis.setex(key, redisTTL, value); }
        catch (Exception e) { logError.log("Redis write error : " + e.getMessage()); }
    }

    private Optional<P> getPlayerFromRedis(String key) {
        if (jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(key);
            if (json == null) return null;
            return Optional.ofNullable(deserializer.apply(json));
        } catch (Exception e) { logError.log("Redis read error : " + e.getMessage()); return null; }
    }

    private void putToRedis(P data) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String json = serializer.apply(data);
            jedis.setex(REDIS_PREFIX_UUID + uuidExtractor.apply(data), redisTTL, json);
            jedis.setex(REDIS_PREFIX_NAME + nameExtractor.apply(data).toLowerCase(), redisTTL, json);
        } catch (Exception e) { logError.log("Redis write error : " + e.getMessage()); }
    }

    private void removeFromRedis(P data) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(REDIS_PREFIX_UUID + uuidExtractor.apply(data),
                      REDIS_PREFIX_NAME + nameExtractor.apply(data).toLowerCase());
        } catch (Exception e) { logError.log("Redis delete error : " + e.getMessage()); }
    }

    private void removeNameFromRedis(String name) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) { jedis.del(REDIS_PREFIX_NAME + name.toLowerCase()); }
        catch (Exception e) { logError.log("Redis delete error : " + e.getMessage()); }
    }

    // -- Cache operations --

    /**
     * Clears all L1 caches (UUID, name, Mojang UUID, skin).
     */
    public void clearAll() {
        uuidToPlayers.synchronous().invalidateAll();
        nameToPlayers.synchronous().invalidateAll();
        mojangUUIDCache.invalidateAll();
        skinCache.invalidateAll();
        logDebug.log("All caches cleared.");
    }

    /**
     * Force-reloads a player by UUID, refreshing L1 from L2/L3.
     *
     * @param uuid The player's server UUID.
     */
    public void forceReloadByUUID(UUID uuid) {
        uuidToPlayers.synchronous().refresh(uuid);
        uuidToPlayers.get(uuid).thenAccept(opt ->
            opt.ifPresent(data -> nameToPlayers.synchronous().put(nameExtractor.apply(data).toLowerCase(), opt)));
    }

    /**
     * Force-reloads a player by name, refreshing L1 from L2/L3.
     *
     * @param name The player's name.
     */
    public void forceReloadByName(String name) {
        String nameKey = name.toLowerCase();
        nameToPlayers.synchronous().refresh(nameKey);
        nameToPlayers.get(nameKey).thenAccept(opt ->
            opt.ifPresent(data -> uuidToPlayers.synchronous().put(uuidExtractor.apply(data), opt)));
    }

    /**
     * Adds or updates a player in L1 and L2.
     *
     * @param playerData The player data to cache.
     * @return {@code true} if the data was successfully stored in both UUID and name caches.
     */
    public boolean addOrUpdateToCache(P playerData) {
        Optional<P> opt = Optional.of(playerData);
        UUID uuid = uuidExtractor.apply(playerData);
        String name = nameExtractor.apply(playerData);
        String nameKey = name.toLowerCase();

        uuidToPlayers.synchronous().asMap().put(uuid, opt);
        nameToPlayers.synchronous().put(nameKey, opt);
        putToRedis(playerData);

        logDebug.log("Cache updated for " + name + ".");
        return uuidToPlayers.getIfPresent(uuid) != null && nameToPlayers.getIfPresent(nameKey) != null;
    }

    /**
     * Removes a player from L1 and L2.
     *
     * @param playerData The player data to remove.
     * @return {@code true} if the data was successfully removed from both caches.
     */
    public boolean removeFromCache(P playerData) {
        UUID uuid = uuidExtractor.apply(playerData);
        String name = nameExtractor.apply(playerData);
        String nameKey = name.toLowerCase();
        uuidToPlayers.synchronous().invalidate(uuid);
        nameToPlayers.synchronous().invalidate(nameKey);
        removeFromRedis(playerData);

        logDebug.log("Cache cleared for " + name + ".");
        return uuidToPlayers.getIfPresent(uuid) == null && nameToPlayers.getIfPresent(nameKey) == null;
    }

    /**
     * Updates L1 only, without touching Redis or triggering sync.
     * Used by sync implementations for cross-server propagation.
     *
     * @param playerData The player data to put into L1.
     */
    public void updateLocal(P playerData) {
        UUID uuid = uuidExtractor.apply(playerData);
        String name = nameExtractor.apply(playerData);
        if (uuidToPlayers.synchronous().getIfPresent(uuid) != null) {
            Optional<P> opt = Optional.of(playerData);
            uuidToPlayers.synchronous().put(uuid, opt);
            nameToPlayers.synchronous().put(name.toLowerCase(), opt);
        }
    }

    /**
     * Invalidates L1 for a player without touching Redis.
     * Used by sync implementations for cross-server removal.
     *
     * @param uuid The player's server UUID.
     * @param name The player's name.
     */
    public void invalidateLocal(UUID uuid, String name) {
        uuidToPlayers.synchronous().invalidate(uuid);
        nameToPlayers.synchronous().invalidate(name.toLowerCase());
    }

    /**
     * Serializes player data to JSON using the addon-provided serializer.
     *
     * @param data The player data.
     * @return The JSON string.
     */
    public String serializePlayerData(P data) {
        return serializer.apply(data);
    }

    /**
     * Deserializes player data from JSON using the addon-provided deserializer.
     *
     * @param json The JSON string.
     * @return The player data, or {@code null} if deserialization fails.
     */
    public P deserializePlayerData(String json) {
        try {
            return deserializer.apply(json);
        } catch (Exception e) {
            logDebug.log("Failed to deserialize PlayerData : " + e.getMessage());
            return null;
        }
    }

    // -- Mojang API --

    /**
     * Fetches the Mojang UUID from the local Mojang UUID cache (which itself
     * checks Redis L2 and the Mojang API as needed).
     *
     * @param playerName The player name.
     * @return The Mojang UUID string, or {@code "none"} if unavailable.
     */
    public String fetchMojangUUID(String playerName) {
        String val = mojangUUIDCache.get(playerName);
        if (val.equals(NONE)) { mojangCacheMisses.increment(); return NONE; }
        mojangCacheHits.increment();
        return val;
    }

    /**
     * Fetches the skin texture URL from the local skin cache.
     *
     * @param mojangUUID      The Mojang UUID.
     * @param currentFallback The fallback texture if the lookup fails.
     * @return The skin URL, or the fallback.
     */
    public String fetchSkinTexture(String mojangUUID, String currentFallback) {
        String val = skinCache.get(mojangUUID);
        if (val == null || val.equals(NONE)) { skinCacheMisses.increment(); return currentFallback; }
        skinCacheHits.increment();
        return val;
    }

    private String getUUIDFromMojang(String playerName) {
        if (!circuitBreaker.allowRequest()) {
            logDebug.log("Circuit breaker OPEN, skipping Mojang UUID lookup for " + playerName + ".");
            return null;
        }
        apiCalls.increment();
        return callMojangUUIDWithRetry(playerName, 0);
    }

    private String callMojangUUIDWithRetry(String playerName, int attempt) {
        try {
            if (!apiSemaphore.tryAcquire(5, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        boolean released = false;
        HttpURLConnection connection = null;
        try {
            connection = openMojangConnection(MOJANG_API_URL + playerName);
            int code = connection.getResponseCode();
            if (code == 200) {
                try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                    JsonObject responseJson = JsonParser.parseReader(reader).getAsJsonObject();
                    if (responseJson == null || !responseJson.has("id")) return null;
                    circuitBreaker.recordSuccess();
                    logDebug.log("UUID retrieved from Mojang API for " + playerName + ".");
                    return addDashesToUUID(responseJson.get("id").getAsString());
                }
            } else if (code == 204 || code == 404) {
                circuitBreaker.recordSuccess();
                return null;
            } else if (code == 429 && attempt < MAX_RETRIES) {
                connection.disconnect(); connection = null;
                apiRateLimits.increment();
                long delay = RATE_LIMIT_BASE_DELAY_MS * (1L << attempt);
                logWarning.log("Mojang API rate limited for " + playerName + ", retrying in " + delay + "ms (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ").");
                apiSemaphore.release(); released = true;
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                return callMojangUUIDWithRetry(playerName, attempt + 1);
            } else {
                apiFailures.increment(); circuitBreaker.recordFailure();
                logError.log("Mojang UUID API returned " + code + " for " + playerName + ".");
                return null;
            }
        } catch (IOException ioe) { apiFailures.increment(); circuitBreaker.recordFailure(); logError.log("Network error for " + playerName + " : " + ioe.getMessage()); return null;
        } catch (Exception e) { apiFailures.increment(); circuitBreaker.recordFailure(); logError.log("Unexpected error for " + playerName + " : " + e.getMessage()); return null;
        } finally { if (connection != null) connection.disconnect(); if (!released) apiSemaphore.release(); }
    }

    private String getSkinURL(String uuid) {
        if (!circuitBreaker.allowRequest()) {
            logDebug.log("Circuit breaker OPEN, skipping Mojang skin lookup for " + uuid + ".");
            return null;
        }
        apiCalls.increment();
        return callMojangSkinWithRetry(uuid, 0);
    }

    private String callMojangSkinWithRetry(String uuid, int attempt) {
        try {
            if (!apiSemaphore.tryAcquire(5, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        boolean released = false;
        HttpURLConnection connection = null;
        try {
            connection = openMojangConnection(MOJANG_PROFILE_API_URL + uuid);
            int code = connection.getResponseCode();
            if (code == 200) {
                try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                    JsonObject response = JsonParser.parseReader(reader).getAsJsonObject();
                    if (response == null || !response.has("properties")) return null;
                    JsonArray propsArray = response.getAsJsonArray("properties");
                    for (JsonElement el : propsArray) {
                        if (!el.isJsonObject()) continue;
                        JsonObject prop = el.getAsJsonObject();
                        if (!prop.has("name") || !prop.has("value")) continue;
                        if (!"textures".equals(prop.get("name").getAsString())) continue;
                        String value = prop.get("value").getAsString();
                        String decodedValue = new String(Base64.getDecoder().decode(value));
                        JsonObject textureProperty = JsonParser.parseString(decodedValue).getAsJsonObject();
                        if (textureProperty.has("textures") && textureProperty.getAsJsonObject("textures").has("SKIN")) {
                            JsonObject skinObj = textureProperty.getAsJsonObject("textures").getAsJsonObject("SKIN");
                            if (skinObj.has("url")) {
                                circuitBreaker.recordSuccess();
                                logDebug.log("Skin texture retrieved from Mojang API for " + uuid + ".");
                                return skinObj.get("url").getAsString();
                            }
                        }
                    }
                    circuitBreaker.recordSuccess();
                    return null;
                }
            } else if (code == 429 && attempt < MAX_RETRIES) {
                connection.disconnect(); connection = null;
                apiRateLimits.increment();
                long delay = RATE_LIMIT_BASE_DELAY_MS * (1L << attempt);
                logWarning.log("Mojang API rate limited for " + uuid + ", retrying in " + delay + "ms (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ").");
                apiSemaphore.release(); released = true;
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                return callMojangSkinWithRetry(uuid, attempt + 1);
            } else {
                apiFailures.increment(); circuitBreaker.recordFailure();
                logError.log("Mojang profile API returned " + code + " for " + uuid + ".");
                return null;
            }
        } catch (IOException ioe) { apiFailures.increment(); circuitBreaker.recordFailure(); logError.log("Network error for " + uuid + " : " + ioe.getMessage()); return null;
        } catch (Exception e) { apiFailures.increment(); circuitBreaker.recordFailure(); logError.log("Unexpected error for " + uuid + " : " + e.getMessage()); return null;
        } finally { if (connection != null) connection.disconnect(); if (!released) apiSemaphore.release(); }
    }

    private HttpURLConnection openMojangConnection(String urlStr) throws IOException {
        URI uri = URI.create(urlStr);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(apiTimeoutMs);
        connection.setReadTimeout(apiTimeoutMs);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestMethod("GET");
        return connection;
    }

    private static String addDashesToUUID(String uuid) {
        if (uuid == null || uuid.length() != 32) return uuid;
        return uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" +
               uuid.substring(12, 16) + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20);
    }

    /**
     * Shuts down internal schedulers. Call on plugin disable.
     */
    public void shutdown() {
        retryScheduler.shutdownNow();
    }

    // -- Metrics --

    public long getMojangCacheHits() { return mojangCacheHits.longValue(); }
    public long getMojangCacheMisses() { return mojangCacheMisses.longValue(); }
    public long getSkinCacheHits() { return skinCacheHits.longValue(); }
    public long getSkinCacheMisses() { return skinCacheMisses.longValue(); }
    public long getApiCalls() { return apiCalls.longValue(); }
    public long getApiFailures() { return apiFailures.longValue(); }
    public long getDbReads() { return dbReads.longValue(); }
    public long getDbWrites() { return dbWrites.longValue(); }
    public long getApiRateLimits() { return apiRateLimits.longValue(); }
    public MojangCircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public LoadingCache<String, String> mojangUUIDCache() { return mojangUUIDCache; }
    public LoadingCache<String, String> skinCache() { return skinCache; }
    /** @return The L1 cache hit rate (0.0 to 1.0) for UUID-based lookups. */
    public double getL1HitRate() { return uuidToPlayers.synchronous().stats().hitRate(); }
    /** @return The estimated number of entries in the L1 UUID cache. */
    public long getL1Size() { return uuidToPlayers.synchronous().estimatedSize(); }

    // -- Player getters --

    public CompletableFuture<Optional<P>> getPlayer(UUID playerId) { return uuidToPlayers.get(playerId); }
    public CompletableFuture<Map<UUID, Optional<P>>> getPlayers(List<UUID> playerIds) { return uuidToPlayers.getAll(playerIds); }
    public Optional<P> getPlayerSync(UUID playerId) { Optional<P> p = uuidToPlayers.synchronous().getIfPresent(playerId); return p == null ? Optional.empty() : p; }
    public CompletableFuture<Optional<P>> getPlayer(String playerName) { return nameToPlayers.get(playerName.toLowerCase()); }
    public Optional<P> getPlayerSync(String playerName) { Optional<P> p = nameToPlayers.synchronous().getIfPresent(playerName.toLowerCase()); return p == null ? Optional.empty() : p; }

    // -- Temp data --

    /**
     * Returns or creates the temporary session data for a player.
     *
     * @param playerId The player's server UUID.
     * @return The {@link PlayerTempData} instance (never null).
     */
    public PlayerTempData getTempPlayerData(UUID playerId) {
        return playersTempCache.get(playerId);
    }

    /**
     * Removes temporary session data for a player from cache.
     *
     * @param playerId The player's server UUID.
     * @return {@code true} if the data was removed.
     */
    public boolean removePlayerTempDataFromCache(UUID playerId) {
        playersTempCache.invalidate(playerId);
        return playersTempCache.getIfPresent(playerId) == null;
    }

    // -- Load player (pre-login) --

    /**
     * Loads a player into cache by UUID and cross-populates the name cache.
     * <p>
     * Uses a single UUID-based lookup (L1 &rarr; Redis &rarr; DB) and then puts the
     * result into the name cache as well, avoiding a redundant name-based DB query.
     * Returns a future so the caller can chain creation logic for new players.
     * </p>
     *
     * @param playerId   The player's server UUID.
     * @param playerName The player's name.
     * @return A future resolving to the loaded player data, or empty if not found.
     */
    public CompletableFuture<Optional<P>> loadPlayer(UUID playerId, String playerName) {
        logDebug.log("Pre-login cache load triggered for " + playerName + " (" + playerId + ").");
        return uuidToPlayers.get(playerId).thenApply(opt -> {
            // Cross-populate the name cache from the UUID result
            if (opt != null && opt.isPresent()) {
                String name = nameExtractor.apply(opt.get()).toLowerCase();
                nameToPlayers.synchronous().put(name, opt);
                // Also populate by the join-time name if different (name change)
                String joinKey = playerName.toLowerCase();
                if (!name.equals(joinKey)) {
                    nameToPlayers.synchronous().put(joinKey, opt);
                }
            }
            return opt == null ? Optional.empty() : opt;
        });
    }

    // -- Builder --

    /**
     * Creates a new builder for {@link PlayerCache}.
     *
     * @param <P> The player data type.
     * @return A new builder.
     */
    public static <P> Builder<P> builder() {
        return new Builder<>();
    }

    /**
     * Fluent builder for {@link PlayerCache}.
     *
     * @param <P> The player data type.
     */
    public static class Builder<P> {
        private Executor executor;
        private JedisPool jedisPool;
        private int redisTTL = 3600;
        private int maxCacheSize = 10_000;
        private int cacheTTLMinutes = 30;
        private int mojangCacheSize = 10_000;
        private int maxApiConcurrency = 5;
        private int apiTimeoutMs = 5000;
        private int cbThreshold = 5;
        private int cbOpenMinutes = 5;
        private String userAgent = "XCore/1.0";
        private Function<P, String> serializer;
        private Function<String, P> deserializer;
        private Function<P, UUID> uuidExtractor;
        private Function<P, String> nameExtractor;
        private Function<P, String> textureExtractor;
        private Function<P, String> mojangUuidExtractor;
        private Function<String, CompletableFuture<Optional<P>>> findByUuidAsync;
        private Function<String, CompletableFuture<Optional<P>>> findByNameAsync;
        private Function<List<String>, CompletableFuture<Map<String, Optional<P>>>> findByUuidsAsync;
        private LogCallback logDebug;
        private LogCallback logWarning;
        private LogCallback logError;

        public Builder<P> executor(Executor executor) { this.executor = executor; return this; }
        public Builder<P> jedisPool(JedisPool jedisPool) { this.jedisPool = jedisPool; return this; }
        public Builder<P> redisTTL(int redisTTL) { this.redisTTL = redisTTL; return this; }
        public Builder<P> maxCacheSize(int maxCacheSize) { this.maxCacheSize = maxCacheSize; return this; }
        public Builder<P> cacheTTLMinutes(int cacheTTLMinutes) { this.cacheTTLMinutes = cacheTTLMinutes; return this; }
        public Builder<P> mojangCacheSize(int mojangCacheSize) { this.mojangCacheSize = mojangCacheSize; return this; }
        public Builder<P> maxApiConcurrency(int maxApiConcurrency) { this.maxApiConcurrency = maxApiConcurrency; return this; }
        public Builder<P> apiTimeoutMs(int apiTimeoutMs) { this.apiTimeoutMs = apiTimeoutMs; return this; }
        public Builder<P> circuitBreaker(int threshold, int openMinutes) { this.cbThreshold = threshold; this.cbOpenMinutes = openMinutes; return this; }
        public Builder<P> userAgent(String userAgent) { this.userAgent = userAgent; return this; }
        public Builder<P> serializer(Function<P, String> serializer) { this.serializer = serializer; return this; }
        public Builder<P> deserializer(Function<String, P> deserializer) { this.deserializer = deserializer; return this; }
        public Builder<P> uuidExtractor(Function<P, UUID> uuidExtractor) { this.uuidExtractor = uuidExtractor; return this; }
        public Builder<P> nameExtractor(Function<P, String> nameExtractor) { this.nameExtractor = nameExtractor; return this; }
        public Builder<P> textureExtractor(Function<P, String> textureExtractor) { this.textureExtractor = textureExtractor; return this; }
        public Builder<P> mojangUuidExtractor(Function<P, String> mojangUuidExtractor) { this.mojangUuidExtractor = mojangUuidExtractor; return this; }
        public Builder<P> findByUuidAsync(Function<String, CompletableFuture<Optional<P>>> fn) { this.findByUuidAsync = fn; return this; }
        public Builder<P> findByNameAsync(Function<String, CompletableFuture<Optional<P>>> fn) { this.findByNameAsync = fn; return this; }
        public Builder<P> findByUuidsAsync(Function<List<String>, CompletableFuture<Map<String, Optional<P>>>> fn) { this.findByUuidsAsync = fn; return this; }
        public Builder<P> logDebug(LogCallback cb) { this.logDebug = cb; return this; }
        public Builder<P> logWarning(LogCallback cb) { this.logWarning = cb; return this; }
        public Builder<P> logError(LogCallback cb) { this.logError = cb; return this; }

        /**
         * Builds the {@link PlayerCache}.
         *
         * @return A new {@link PlayerCache}.
         * @throws IllegalStateException if required fields are missing.
         */
        public PlayerCache<P> build() {
            if (executor == null) throw new IllegalStateException("PlayerCache requires an executor.");
            if (serializer == null) throw new IllegalStateException("PlayerCache requires a serializer.");
            if (deserializer == null) throw new IllegalStateException("PlayerCache requires a deserializer.");
            if (uuidExtractor == null) throw new IllegalStateException("PlayerCache requires a uuidExtractor.");
            if (nameExtractor == null) throw new IllegalStateException("PlayerCache requires a nameExtractor.");
            if (findByUuidAsync == null) throw new IllegalStateException("PlayerCache requires findByUuidAsync.");
            if (findByNameAsync == null) throw new IllegalStateException("PlayerCache requires findByNameAsync.");
            return new PlayerCache<>(this);
        }
    }
}
