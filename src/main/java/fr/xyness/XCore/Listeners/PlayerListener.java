package fr.xyness.XCore.Listeners;

import java.time.LocalDateTime;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Events.PlayerDataLoadEvent;
import fr.xyness.XCore.Models.PlayerData;

/**
 * Bukkit event listener for player join and quit events.
 * <p>
 * On pre-login (async), loads or creates the player's data in the cache and database.
 * On join, updates the last_login timestamp.
 * On quit, cleans up the player's temporary session data and updates last_logout.
 * </p>
 */
public class PlayerListener implements Listener {

    /** Reference to the main plugin instance. */
    private final XCore core;

    /**
     * Creates a new PlayerListener.
     *
     * @param core The XCore plugin instance.
     */
    public PlayerListener(XCore core) {
        this.core = core;
    }

    /**
     * Handles the async pre-login event to load/refresh player data.
     * <p>
     * If the player does not exist in the database, creates a new entry with Mojang UUID
     * and skin texture resolution, then caches the result. Fires {@link PlayerDataLoadEvent}
     * after data is loaded or created.
     * </p>
     * Only triggers if the login is allowed (not kicked by another plugin).
     * Runs at {@link EventPriority#MONITOR} to execute after all other handlers.
     *
     * @param event The async pre-login event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerPreLoginEvent(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != Result.ALLOWED) {
            return;
        }

        UUID playerId = event.getUniqueId();
        String playerName = event.getName();

        core.playerCache().loadPlayer(playerId, playerName).thenAccept(opt -> {
            if (opt.isPresent()) {
                // Existing player loaded -- fire event
                Bukkit.getPluginManager().callEvent(new PlayerDataLoadEvent(opt.get(), false));
            } else {
                // New player -- resolve Mojang UUID and skin, insert into DB, cache
                createNewPlayer(playerId, playerName);
            }
        }).exceptionally(ex -> {
            core.logger().sendError("Failed to load player data for " + playerName + " : " + ex.getMessage());
            return null;
        });
    }

    /**
     * Creates a new player entry: resolves Mojang UUID and skin texture,
     * inserts into the database, caches the result, and fires {@link PlayerDataLoadEvent}.
     *
     * @param playerId   The player's server UUID.
     * @param playerName The player's name.
     */
    private void createNewPlayer(UUID playerId, String playerName) {
        // Resolve Mojang UUID (uses the circuit-breaker-protected Mojang API)
        String mojangUuid = core.playerCache().fetchMojangUUID(playerName);

        // Resolve skin texture
        String texture = core.playerCache().fetchSkinTexture(mojangUuid, "none");

        // Create player data
        PlayerData playerData = new PlayerData(playerId, playerName, texture, mojangUuid);

        // Insert into DB
        core.playerDAO().insertAsync(playerData).thenRun(() -> {
            // Cache the new player in both UUID and name caches
            core.playerCache().addOrUpdateToCache(playerData);

            core.logger().sendDebug("New player created: " + playerName + " (" + playerId + ").");

            // Fire the event
            Bukkit.getPluginManager().callEvent(new PlayerDataLoadEvent(playerData, true));
        }).exceptionally(ex -> {
            core.logger().sendError("Failed to create new player " + playerName + " : " + ex.getMessage());
            return null;
        });
    }

    /**
     * Handles player join to update last_login timestamp.
     *
     * @param event The player join event.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Delay 20 ticks (1 second) to ensure DB row exists for new players
        core.schedulerAdapter().runAsyncTaskLater(() -> {
            String now = LocalDateTime.now().format(XCore.FORMATTER);
            core.playerDAO().updateColumnAsync(event.getPlayer().getUniqueId().toString(), "last_login", now);
        }, 20L);
    }

    /**
     * Handles the player quit event to clean up temporary session data.
     * Updates last_logout and removes temporary data from cache.
     *
     * @param event The player quit event.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String now = LocalDateTime.now().format(XCore.FORMATTER);
        core.playerDAO().updateColumnAsync(event.getPlayer().getUniqueId().toString(), "last_logout", now);
        core.playerCache().removePlayerTempDataFromCache(event.getPlayer().getUniqueId());
    }
}
