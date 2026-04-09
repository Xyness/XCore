package fr.xyness.XCore.Cache;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Configuration for a named cache region.
 * <p>
 * Each region has its own size limits, TTL settings, and an asynchronous loader
 * function provided by the addon that owns the region. Use the {@link Builder}
 * to construct instances.
 * </p>
 *
 * @param <K> The key type.
 * @param <V> The value type.
 */
public class CacheConfig<K, V> {

    private final String regionName;
    private final int maxSize;
    private final int ttlMinutes;
    private final int redisTTLSeconds;
    private final String redisPrefix;
    private final Function<K, CompletableFuture<Optional<V>>> loader;
    private final Class<K> keyType;
    private final Class<V> valueType;

    private CacheConfig(Builder<K, V> builder) {
        this.regionName = builder.regionName;
        this.maxSize = builder.maxSize;
        this.ttlMinutes = builder.ttlMinutes;
        this.redisTTLSeconds = builder.redisTTLSeconds;
        this.redisPrefix = builder.redisPrefix;
        this.loader = builder.loader;
        this.keyType = builder.keyType;
        this.valueType = builder.valueType;
    }

    /** @return The unique name identifying this cache region. */
    public String getRegionName() { return regionName; }

    /** @return The maximum number of entries in the L1 (Caffeine) cache. */
    public int getMaxSize() { return maxSize; }

    /** @return The time-to-live in minutes for L1 cache entries (access-based expiry). */
    public int getTtlMinutes() { return ttlMinutes; }

    /** @return The time-to-live in seconds for L2 (Redis) cache entries. */
    public int getRedisTTLSeconds() { return redisTTLSeconds; }

    /** @return The Redis key prefix for this region (e.g. {@code "xbans:sanctions:"}). */
    public String getRedisPrefix() { return redisPrefix; }

    /** @return The async loader function that fetches a value from the backing store (L3). */
    public Function<K, CompletableFuture<Optional<V>>> getLoader() { return loader; }

    /** @return The key class type, used for deserialization. */
    public Class<K> getKeyType() { return keyType; }

    /** @return The value class type, used for deserialization. */
    public Class<V> getValueType() { return valueType; }

    /**
     * Creates a new builder for a cache region.
     *
     * @param regionName The unique name for the region.
     * @param keyType    The key class.
     * @param valueType  The value class.
     * @param <K>        The key type.
     * @param <V>        The value type.
     * @return A new {@link Builder}.
     */
    public static <K, V> Builder<K, V> builder(String regionName, Class<K> keyType, Class<V> valueType) {
        return new Builder<>(regionName, keyType, valueType);
    }

    /**
     * Fluent builder for {@link CacheConfig}.
     *
     * @param <K> The key type.
     * @param <V> The value type.
     */
    public static class Builder<K, V> {

        private final String regionName;
        private final Class<K> keyType;
        private final Class<V> valueType;
        private int maxSize = 10_000;
        private int ttlMinutes = 30;
        private int redisTTLSeconds = 3600;
        private String redisPrefix;
        private Function<K, CompletableFuture<Optional<V>>> loader;

        private Builder(String regionName, Class<K> keyType, Class<V> valueType) {
            this.regionName = regionName;
            this.keyType = keyType;
            this.valueType = valueType;
            this.redisPrefix = "xcore:" + regionName + ":";
        }

        /** Sets the maximum number of entries in L1. Default: 10,000. */
        public Builder<K, V> maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /** Sets the L1 access-based TTL in minutes. Default: 30. */
        public Builder<K, V> ttlMinutes(int ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
            return this;
        }

        /** Sets the Redis TTL in seconds. Default: 3600 (1 hour). */
        public Builder<K, V> redisTTLSeconds(int redisTTLSeconds) {
            this.redisTTLSeconds = redisTTLSeconds;
            return this;
        }

        /** Sets the Redis key prefix. Default: {@code "xcore:{regionName}:"}. */
        public Builder<K, V> redisPrefix(String redisPrefix) {
            this.redisPrefix = redisPrefix;
            return this;
        }

        /** Sets the async loader function (L3 backing store). Required. */
        public Builder<K, V> loader(Function<K, CompletableFuture<Optional<V>>> loader) {
            this.loader = loader;
            return this;
        }

        /**
         * Builds the {@link CacheConfig}.
         *
         * @return A new immutable {@link CacheConfig}.
         * @throws IllegalStateException if the loader is not set.
         */
        public CacheConfig<K, V> build() {
            if (loader == null) {
                throw new IllegalStateException("CacheConfig requires a loader function for region '" + regionName + "'.");
            }
            return new CacheConfig<>(this);
        }
    }
}
