package fr.xyness.XCore.API;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.zaxxer.hikari.HikariDataSource;

import redis.clients.jedis.JedisPool;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Integrations.FloodgateHook;
import fr.xyness.XCore.Models.PlayerData;
import fr.xyness.XCore.Models.PlayerTempData;
import fr.xyness.XCore.Utils.LangManager;
import fr.xyness.XCore.Utils.Logger;
import fr.xyness.XCore.Utils.SchedulerAdapter;

/**
 * Implementation of the {@link XCoreApi} interface.
 * <p>
 * Delegates all operations to the corresponding methods on the {@link XCore} plugin class
 * and its subsystems (cache, methods, logger, scheduler).
 * </p>
 */
public class XCoreApiService implements XCoreApi {

	/** Reference to the main plugin instance. */
	private final XCore main;

	/**
	 * Creates a new API service instance.
	 *
	 * @param main The main plugin instance to delegate to.
	 */
	public XCoreApiService(XCore main) {
		this.main = main;
	}

	@Override
	public @NotNull Optional<PlayerData> getPlayer(@NotNull UUID playerId) {
		return main.playerCache().getPlayerSync(playerId);
	}

	@Override
	public @NotNull CompletableFuture<Optional<PlayerData>> getPlayerAsync(@NotNull UUID playerId) {
		return main.playerCache().getPlayer(playerId);
	}

	@Override
	public @NotNull CompletableFuture<Map<UUID, Optional<PlayerData>>> getPlayersAsync(@NotNull List<UUID> playerIds) {
		return main.playerCache().getPlayers(playerIds);
	}

	@Override
	public @NotNull Optional<PlayerData> getPlayer(@NotNull String playerName) {
		return main.playerCache().getPlayerSync(playerName);
	}

	@Override
	public @NotNull CompletableFuture<Optional<PlayerData>> getPlayerAsync(@NotNull String playerName) {
		return main.playerCache().getPlayer(playerName);
	}

	@Override
	public @NotNull PlayerTempData getPlayerTempData(@NotNull UUID playerId) {
		return main.playerCache().getTempPlayerData(playerId);
	}

	@Override
	public @NotNull Logger getLogger() {
		return main.logger();
	}

	@Override
	public @NotNull SchedulerAdapter getSchedulerAdapter() {
		return main.schedulerAdapter();
	}

	@Override
	public @NotNull ColumnBuilder columnBuilder() {
		return new ColumnBuilder(main);
	}

	@Override
	public @NotNull CompletableFuture<Void> updatePlayerDataAsync(@NotNull UUID playerId, @NotNull String column, Object value) {
		// Capture old value for rollback
		var dataOpt = main.playerCache().getPlayerSync(playerId);
		Object oldValue = dataOpt.map(d -> d.getTargetData(column)).orElse(null);

		// Optimistic cache update
		dataOpt.ifPresent(data -> {
			data.setTargetData(column, value);
			main.playerCache().addOrUpdateToCache(data);
		});

		// Persist to DB, rollback cache on failure
		return main.playerDAO().updateColumnAsync(playerId.toString(), column, value)
			.exceptionally(ex -> {
				main.logger().sendWarning("DB update failed for " + column + ", rolling back cache : " + ex.getMessage());
				dataOpt.ifPresent(data -> {
					if (oldValue != null) data.setTargetData(column, oldValue);
					main.playerCache().addOrUpdateToCache(data);
				});
				throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
			});
	}

	@Override
	public @NotNull HikariDataSource getDataSource() {
		return main.getDataSource();
	}

	@Override
	public @NotNull ExecutorService getExecutor() {
		return main.getExecutor();
	}

	@Override
	public boolean isRedisEnabled() {
		return main.getJedisPool() != null;
	}

	@Override
	public @Nullable JedisPool getJedisPool() {
		return main.getJedisPool();
	}

	@Override
	public @NotNull DatabaseType getDatabaseType() {
		return main.getDatabaseType();
	}

	@Override
	public @NotNull LangManager langManager() {
		return main.langManager();
	}

	@Override
	public boolean isBedrockPlayer(@NotNull Player player) {
		return FloodgateHook.isBedrockPlayer(player);
	}

	@Override
	public boolean isBedrockPlayer(@NotNull UUID uuid) {
		return FloodgateHook.isBedrockPlayer(uuid);
	}

}
