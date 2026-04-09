package fr.xyness.XCore.API;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.zaxxer.hikari.HikariDataSource;

import fr.xyness.XCore.Models.PlayerData;
import fr.xyness.XCore.Models.PlayerTempData;
import fr.xyness.XCore.Utils.LangManager;
import fr.xyness.XCore.Utils.Logger;
import fr.xyness.XCore.Utils.SchedulerAdapter;
import redis.clients.jedis.JedisPool;

/**
 * Public API interface for XCore.
 * <p>
 * External plugins should obtain an instance via {@link XCoreApiProvider#get()}
 * and use these methods to query player data, schedule tasks, and extend the database schema.
 * </p>
 */
public interface XCoreApi {

	// -------------------------------------------------------------------------
	// Player data access (ported from PlayersApi)
	// -------------------------------------------------------------------------

	/**
	 * Retrieves a player's data synchronously from the L1 cache.
	 * Does not trigger a database load on cache miss.
	 *
	 * @param playerId The server-side UUID.
	 * @return An {@link Optional} containing the player data if cached, or empty.
	 */
    @NotNull Optional<PlayerData> getPlayer(@NotNull UUID playerId);

	/**
	 * Retrieves a player's data asynchronously (L1 cache &rarr; Redis &rarr; database).
	 *
	 * @param playerId The server-side UUID.
	 * @return A future resolving to an {@link Optional} with the player data.
	 */
    @NotNull CompletableFuture<Optional<PlayerData>> getPlayerAsync(@NotNull UUID playerId);

	/**
	 * Retrieves multiple players' data asynchronously in batch.
	 *
	 * @param playerIds The list of server-side UUIDs.
	 * @return A future resolving to a map of UUID &rarr; Optional&lt;PlayerData&gt;.
	 */
	@NotNull CompletableFuture<Map<UUID, Optional<PlayerData>>> getPlayersAsync(@NotNull List<UUID> playerIds);

	/**
	 * Retrieves a player's data synchronously from the L1 cache by name.
	 * Does not trigger a database load on cache miss.
	 *
	 * @param playerName The player name.
	 * @return An {@link Optional} containing the player data if cached, or empty.
	 */
    @NotNull Optional<PlayerData> getPlayer(@NotNull String playerName);

	/**
	 * Retrieves a player's data asynchronously by name (L1 cache &rarr; Redis &rarr; database).
	 *
	 * @param playerName The player name.
	 * @return A future resolving to an {@link Optional} with the player data.
	 */
    @NotNull CompletableFuture<Optional<PlayerData>> getPlayerAsync(@NotNull String playerName);

	/**
	 * Retrieves or creates the temporary session data for an online player.
	 *
	 * @param playerId The server-side UUID.
	 * @return The {@link PlayerTempData} instance (never null).
	 */
    @NotNull PlayerTempData getPlayerTempData(@NotNull UUID playerId);

	// -------------------------------------------------------------------------
	// Infrastructure access (ported from PlayersApi)
	// -------------------------------------------------------------------------

	/**
	 * Returns the plugin's custom logger for external plugins to use.
	 *
	 * @return The {@link Logger} instance.
	 */
    @NotNull Logger getLogger();

	/**
	 * Returns the Bukkit/Folia-compatible scheduler adapter.
	 *
	 * @return The {@link SchedulerAdapter} instance.
	 */
    @NotNull SchedulerAdapter getSchedulerAdapter();

	/**
	 * Creates a fluent {@link ColumnBuilder} for adding columns to the {@code players} table.
	 *
	 * @return A new {@link ColumnBuilder} instance.
	 */
    @NotNull ColumnBuilder columnBuilder();

	/**
	 * Updates a dynamic column value for a player and persists it to the database.
	 * Also updates the L1 and L2 caches.
	 *
	 * @param playerId The server-side UUID of the player.
	 * @param column   The column name to update.
	 * @param value    The new value.
	 * @return A future that completes when the update is persisted.
	 */
    @NotNull CompletableFuture<Void> updatePlayerDataAsync(@NotNull UUID playerId, @NotNull String column, Object value);

	/**
	 * Returns the shared HikariCP data source.
	 * External plugins can use this to create their own tables in the same database.
	 *
	 * @return The {@link HikariDataSource} instance.
	 */
    @NotNull HikariDataSource getDataSource();

	/**
	 * Returns the shared executor service for async operations.
	 *
	 * @return The {@link ExecutorService} instance.
	 */
    @NotNull ExecutorService getExecutor();

	/**
	 * Returns whether Redis is enabled and connected.
	 *
	 * @return {@code true} if Redis is available.
	 */
    boolean isRedisEnabled();

	/**
	 * Returns the shared Jedis connection pool, or {@code null} if Redis is not enabled.
	 *
	 * @return The {@link JedisPool} instance, or {@code null}.
	 */
    @Nullable JedisPool getJedisPool();

	/**
	 * Returns the configured database type.
	 *
	 * @return The {@link DatabaseType}.
	 */
    @NotNull DatabaseType getDatabaseType();

	// -------------------------------------------------------------------------
	// V2 additions
	// -------------------------------------------------------------------------

	/**
	 * Returns the language manager for message lookups.
	 *
	 * @return The {@link LangManager} instance.
	 */
	@NotNull LangManager langManager();

	// -------------------------------------------------------------------------
	// Bedrock / Floodgate detection
	// -------------------------------------------------------------------------

	/**
	 * Checks if a player is a Bedrock player connected via Geyser/Floodgate.
	 *
	 * @param player The player to check.
	 * @return {@code true} if the player is a Bedrock player.
	 */
	boolean isBedrockPlayer(@NotNull Player player);

	/**
	 * Checks if a UUID belongs to a Bedrock player based on the Geyser UUID prefix.
	 *
	 * @param uuid The UUID to check.
	 * @return {@code true} if the UUID matches the Bedrock prefix pattern.
	 */
	boolean isBedrockPlayer(@NotNull UUID uuid);

}
