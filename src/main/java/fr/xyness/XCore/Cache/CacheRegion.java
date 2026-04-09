package fr.xyness.XCore.Cache;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * A generic multi-level cache region with L1 (Caffeine), L2 (Redis), and L3 (loader) tiers.
 * <p>
 * On a {@link #get(Object)} call the lookup order is:
 * <ol>
 *   <li><b>L1</b> — In-process Caffeine async cache (fastest).</li>
 *   <li><b>L2</b> — Redis GET, if a {@link JedisPool} is available.</li>
 *   <li><b>L3</b> — Addon-provided async loader (typically a database query).</li>
 * </ol>
 * Values returned by L2 or L3 are automatically promoted to higher tiers.
 * </p>
 *
 * @param <K> The key type.
 * @param <V> The value type.
 */
public class CacheRegion<K, V> {

    private final CacheConfig<K, V> config;
    private final AsyncLoadingCache<K, Optional<V>> l1Cache;
    private final JedisPool jedisPool;
    private final Gson gson;
    private final Type valueType;

    CacheRegion(CacheConfig<K, V> config, JedisPool jedisPool, Executor executor, Gson gson) {
        this.config = config;
        this.jedisPool = jedisPool;
        this.gson = gson;
        this.valueType = TypeToken.get(config.getValueType()).getType();

        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(config.getMaxSize())
                .expireAfterAccess(config.getTtlMinutes(), TimeUnit.MINUTES)
                .executor(executor)
                .buildAsync((key, exec) -> loadFromL2ThenL3(key));
    }

    /**
     * Retrieves a value by key, checking L1 then L2 then L3.
     *
     * @param key The key to look up.
     * @return A future containing an {@link Optional} with the value, or empty if not found at any tier.
     */
    public CompletableFuture<Optional<V>> get(K key) {
        return l1Cache.get(key);
    }

    /**
     * Retrieves a value from L1 only, without triggering a load.
     *
     * @param key The key to look up.
     * @return An {@link Optional} with the value if present in L1, or empty.
     */
    public Optional<V> getIfPresent(K key) {
        CompletableFuture<Optional<V>> future = l1Cache.getIfPresent(key);
        if (future == null) return Optional.empty();
        Optional<V> result = future.getNow(null);
        return result != null ? result : Optional.empty();
    }

    /**
     * Puts a value into L1 and L2 (if Redis is available).
     *
     * @param key   The key.
     * @param value The value to store.
     */
    public void put(K key, V value) {
        l1Cache.synchronous().put(key, Optional.of(value));
        putToRedis(key, value);
    }

    /**
     * Updates L1 only, without touching Redis. Used for cross-server sync
     * where the remote server already has the value in Redis.
     *
     * @param key   The key.
     * @param value The value to store locally.
     */
    public void putLocal(K key, V value) {
        // Only update if already cached — don't pollute L1 with data from other servers
        if (l1Cache.synchronous().getIfPresent(key) != null) {
            l1Cache.synchronous().put(key, Optional.of(value));
        }
    }

    /**
     * Invalidates a key from L1 and L2.
     *
     * @param key The key to invalidate.
     */
    public void invalidate(K key) {
        l1Cache.synchronous().invalidate(key);
        removeFromRedis(key);
    }

    /**
     * Invalidates a key from L1 only, without touching Redis.
     * Used for cross-server sync removals.
     *
     * @param key The key to invalidate locally.
     */
    public void invalidateLocal(K key) {
        l1Cache.synchronous().invalidate(key);
    }

    /**
     * Invalidates all entries from L1. Does not clear Redis.
     */
    public void invalidateAll() {
        l1Cache.synchronous().invalidateAll();
    }

    /** @return The region name from the config. */
    public String getName() {
        return config.getRegionName();
    }

    /** @return The estimated number of entries in L1. */
    public long estimatedSize() {
        return l1Cache.synchronous().estimatedSize();
    }

    /** @return The underlying cache config. */
    public CacheConfig<K, V> getConfig() {
        return config;
    }

    // -- Internal --

    private CompletableFuture<Optional<V>> loadFromL2ThenL3(K key) {
        // Try L2 (Redis)
        V fromRedis = getFromRedis(key);
        if (fromRedis != null) {
            return CompletableFuture.completedFuture(Optional.of(fromRedis));
        }

        // Fall through to L3 (loader)
        return config.getLoader().apply(key).thenApply(opt -> {
            opt.ifPresent(value -> putToRedis(key, value));
            return opt;
        });
    }

    private String redisKey(K key) {
        return config.getRedisPrefix() + key.toString();
    }

    private V getFromRedis(K key) {
        if (jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(redisKey(key));
            if (json == null) return null;
            return gson.fromJson(json, valueType);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("XCore").warning("Failed to read from Redis cache: " + e.getMessage());
            return null;
        }
    }

    private void putToRedis(K key, V value) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String json = gson.toJson(value);
            jedis.setex(redisKey(key), config.getRedisTTLSeconds(), json);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("XCore").warning("Failed to write to Redis cache: " + e.getMessage());
        }
    }

    private void removeFromRedis(K key) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(redisKey(key));
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("XCore").warning("Failed to remove from Redis cache: " + e.getMessage());
        }
    }
}
