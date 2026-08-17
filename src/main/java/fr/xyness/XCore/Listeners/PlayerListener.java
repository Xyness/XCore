package fr.xyness.XCore.Listeners;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
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

        // Before creating anything: is this somebody we already know under another UUID?
        findPreviousIdentity(playerId, playerName, mojangUuid).thenAccept(previous -> {
            if (previous.isPresent()) {
                migrate(previous.get(), playerId, playerName, texture, mojangUuid);
                return;
            }
            insertNew(playerId, playerName, texture, mojangUuid);
        }).exceptionally(ex -> {
            core.logger().sendError("Failed to resolve the identity of " + playerName + " : " + ex.getMessage());
            insertNew(playerId, playerName, texture, mojangUuid);
            return null;
        });
    }

    /**
     * Looks for a row belonging to this player under a different server UUID.
     *
     * <h2>Why this exists</h2>
     * {@code server_uuid} is not stable. In offline mode it is derived from the name, in online mode
     * it is Mojang's — so flipping that setting (or putting the server behind a proxy that changes
     * it) makes every returning player look brand new. A second row was then created for them, and
     * everything living in the {@code players} table went with the old one: balances, per-addon
     * columns, activity dates. The player logged in to an empty account and nothing said why.
     *
     * <p>Two identifiers survive the switch. The Mojang UUID is the reliable one and is tried first.
     * A name-only match is the fallback for accounts with no Mojang UUID at all, which is the normal
     * case on a cracked server — and there a name maps to exactly one offline UUID, so it identifies
     * the account as surely as the UUID would.</p>
     *
     * @param playerId   The UUID this player arrived with.
     * @param playerName Their name.
     * @param mojangUuid Their Mojang UUID, or {@code "none"}.
     * @return A future holding the previous row, or empty when this really is a new player.
     */
    private CompletableFuture<Optional<PlayerData>> findPreviousIdentity(UUID playerId, String playerName, String mojangUuid) {
        if (!core.getConfig().getBoolean("migrate-uuid-changes", true)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        CompletableFuture<Optional<PlayerData>> byMojang = (mojangUuid == null || "none".equalsIgnoreCase(mojangUuid))
                ? CompletableFuture.completedFuture(Optional.empty())
                : core.playerDAO().findByMojangUuidAsync(mojangUuid);

        return byMojang.thenCompose(found -> {
            if (found.isPresent() && !found.get().getUuid().equals(playerId)) {
                return CompletableFuture.completedFuture(found);
            }
            if (found.isPresent()) return CompletableFuture.completedFuture(Optional.empty());

            return core.playerDAO().findByNameAsync(playerName).thenApply(byName -> {
                if (byName.isEmpty()) return Optional.empty();
                PlayerData existing = byName.get();
                if (existing.getUuid().equals(playerId)) return Optional.empty();
                // Only when the stored row has no Mojang identity of its own, or the same one: a row
                // carrying somebody else's Mojang UUID under this name belongs to a player who was
                // renamed, and taking it over would hand their data to whoever claimed the name.
                String storedMojang = existing.getMojangUUID();
                boolean storedUnknown = storedMojang == null || storedMojang.isBlank() || "none".equalsIgnoreCase(storedMojang);
                boolean sameMojang = mojangUuid != null && mojangUuid.equalsIgnoreCase(storedMojang);
                return (storedUnknown || sameMojang) ? Optional.of(existing) : Optional.empty();
            });
        });
    }

    /**
     * Moves an existing row onto the UUID the player arrived with, keeping everything it holds.
     *
     * @param previous   The row found under the old UUID.
     * @param playerId   The new server UUID.
     * @param playerName The current name.
     * @param texture    The current skin texture.
     * @param mojangUuid The Mojang UUID.
     */
    private void migrate(PlayerData previous, UUID playerId, String playerName, String texture, String mojangUuid) {
        UUID oldId = previous.getUuid();
        PlayerData migrated = new PlayerData(playerId, playerName, texture, mojangUuid);
        migrated.setData(previous.getData());

        core.playerDAO().updateAsync(migrated, oldId.toString()).thenRun(() -> {
            core.playerCache().invalidateLocal(oldId, previous.getName());
            core.playerCache().addOrUpdateToCache(migrated);
            core.logger().sendInfo("Player <aqua>" + playerName + "</aqua> came back under a new UUID ("
                    + oldId + " → " + playerId + "). Their data was moved rather than duplicated.");
            Bukkit.getPluginManager().callEvent(new PlayerDataLoadEvent(migrated, false));
        }).exceptionally(ex -> {
            core.logger().sendError("Failed to migrate " + playerName + " from " + oldId + " : " + ex.getMessage());
            return null;
        });
    }

    /**
     * Creates the row of a genuinely new player.
     *
     * @param playerId   The server UUID.
     * @param playerName The name.
     * @param texture    The skin texture.
     * @param mojangUuid The Mojang UUID.
     */
    private void insertNew(UUID playerId, String playerName, String texture, String mojangUuid) {
        PlayerData playerData = new PlayerData(playerId, playerName, texture, mojangUuid);

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

    /** Where the start of the session is kept, on the player's temporary data. */
    private static final String SESSION_START = XCore.SESSION_START_KEY;

    /**
     * Records the login, starts counting play time, announces the arrival to the network and hands
     * over anything the player was owed.
     *
     * @param event The player join event.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        core.playerCache().getTempPlayerData(uuid).put(SESSION_START, System.currentTimeMillis());
        if (core.network() != null) core.network().announceJoin(player);

        // A second's delay so a brand new player has their row by then. Through the cache path,
        // otherwise the copy in memory keeps the previous date all session.
        core.schedulerAdapter().runAsyncTaskLater(() -> {
            String now = LocalDateTime.now().format(XCore.FORMATTER);
            core.api().updatePlayerDataAsync(uuid, "last_login", now);
            if (core.delivery() != null) core.delivery().claimOnJoin(player);
        }, 20L);
    }

    /**
     * Records the logout, adds the session to the total play time and cleans up.
     *
     * @param event The player quit event.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        String now = LocalDateTime.now().format(XCore.FORMATTER);
        core.api().updatePlayerDataAsync(uuid, "last_logout", now);

        addPlaytime(uuid);

        if (core.network() != null) core.network().announceQuit(player);
        core.playerCache().removePlayerTempDataFromCache(uuid);

        // Anything this player still had buffered goes out now rather than waiting for the timer:
        // they may reconnect on another server within the second.
        core.playerDAO().writeBuffer().flushPlayer(uuid.toString());
    }

    /**
     * Adds the session that just ended to the player's total.
     *
     * <p>The addition is done by the database rather than in Java, so two servers closing a session
     * for the same account cannot each write a total computed from the same starting point.</p>
     *
     * @param uuid The player who just left.
     */
    private void addPlaytime(UUID uuid) {
        Object start = core.playerCache().getTempPlayerData(uuid).get(SESSION_START);
        if (!(start instanceof Long since)) return;

        long seconds = (System.currentTimeMillis() - since) / 1000L;
        if (seconds <= 0) return;

        core.tableManager().query("players")
                .update()
                .setRelative("playtime", seconds)
                .where("server_uuid", uuid.toString())
                .executeUpdateAsync()
                .exceptionally(error -> {
                    core.logger().sendDebug("Could not add play time for " + uuid + " : " + error.getMessage());
                    return 0;
                });

        // Keep the cached copy in step with what the database now holds.
        core.playerCache().getPlayerSync(uuid).ifPresent(data -> {
            Long current = data.getTargetData("playtime", Long.class);
            data.setTargetData("playtime", (current == null ? 0L : current) + seconds);
            core.playerCache().addOrUpdateToCache(data);
        });
    }
}
