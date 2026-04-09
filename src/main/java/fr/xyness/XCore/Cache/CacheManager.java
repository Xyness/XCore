package fr.xyness.XCore.Cache;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import com.google.gson.Gson;

import redis.clients.jedis.JedisPool;

/**
 * Central manager for all {@link CacheRegion} instances.
 * <p>
 * Each addon creates its cache regions through this manager, which handles
 * shared resources (Gson, JedisPool, executor) and provides a central
 * shutdown method.
 * </p>
 */
public class CacheManager {

    private final JedisPool jedisPool;
    private final Executor executor;
    private final Gson gson;
    private final Map<String, CacheRegion<?, ?>> regions = new ConcurrentHashMap<>();

    /**
     * Creates a new CacheManager.
     *
     * @param jedisPool The Redis connection pool, or {@code null} if Redis is not available.
     * @param executor  The async executor for cache loading operations.
     */
    public CacheManager(JedisPool jedisPool, Executor executor) {
        this.jedisPool = jedisPool;
        this.executor = executor;
        this.gson = new Gson();
    }

    /**
     * Creates and registers a new cache region.
     *
     * @param config The cache region configuration.
     * @param <K>    The key type.
     * @param <V>    The value type.
     * @return The created {@link CacheRegion}.
     * @throws IllegalStateException if a region with the same name already exists.
     */
    public <K, V> CacheRegion<K, V> createRegion(CacheConfig<K, V> config) {
        if (regions.containsKey(config.getRegionName())) {
            throw new IllegalStateException("Cache region '" + config.getRegionName() + "' already exists.");
        }
        CacheRegion<K, V> region = new CacheRegion<>(config, jedisPool, executor, gson);
        regions.put(config.getRegionName(), region);
        return region;
    }

    /**
     * Retrieves a registered cache region by name.
     *
     * @param name The region name.
     * @return An {@link Optional} containing the region, or empty if not found.
     */
    public Optional<CacheRegion<?, ?>> getRegion(String name) {
        return Optional.ofNullable(regions.get(name));
    }

    /**
     * Retrieves a registered cache region by name with type safety.
     *
     * @param name      The region name.
     * @param keyType   The expected key class.
     * @param valueType The expected value class.
     * @param <K>       The key type.
     * @param <V>       The value type.
     * @return An {@link Optional} containing the typed region, or empty if not found or type mismatch.
     */
    @SuppressWarnings("unchecked")
    public <K, V> Optional<CacheRegion<K, V>> getRegion(String name, Class<K> keyType, Class<V> valueType) {
        CacheRegion<?, ?> region = regions.get(name);
        if (region == null) return Optional.empty();
        CacheConfig<?, ?> cfg = region.getConfig();
        if (cfg.getKeyType() == keyType && cfg.getValueType() == valueType) {
            return Optional.of((CacheRegion<K, V>) region);
        }
        return Optional.empty();
    }

    /**
     * Invalidates all entries across all regions and removes all regions.
     * Called during plugin shutdown.
     */
    public void shutdown() {
        for (CacheRegion<?, ?> region : regions.values()) {
            region.invalidateAll();
        }
        regions.clear();
    }

    /** @return The shared Gson instance used for Redis serialization. */
    public Gson getGson() {
        return gson;
    }

    /** @return The JedisPool, or {@code null} if Redis is not available. */
    public JedisPool getJedisPool() {
        return jedisPool;
    }

    /** @return The shared async executor. */
    public Executor getExecutor() {
        return executor;
    }
}
