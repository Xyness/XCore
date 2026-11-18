package fr.xyness.XCore.Listeners;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import fr.xyness.XCore.Economy.CoinsManager;
import fr.xyness.XCore.Events.PlayerDataLoadEvent;
import fr.xyness.XCore.Identity.UuidChanges;
import fr.xyness.XCore.Models.PlayerData;

/**
 * Bukkit event listener for player pre-login, join and quit events.
 * <p>
 * Pre-login is handled in two passes. The first, at {@link EventPriority#LOW}, recognises the
 * player: their row under the UUID they arrive with, or — with {@code migrate-uuid-changes} — the
 * row they left under another UUID, which is moved, the plugins following it, before the login goes
 * on. The second, at {@link EventPriority#MONITOR}, only for a login nobody refused, creates the row
 * of a genuinely new player and fires {@link PlayerDataLoadEvent}.
 * On join, updates the last_login timestamp.
 * On quit, cleans up the player's temporary session data and updates last_logout.
 * </p>
 */
public class PlayerListener implements Listener {

    /**
     * How long a login waits for XCore to recognise the player and move their row: the lookups, the
     * Mojang UUID of a name not seen before, the move itself. Past it the login goes on, and whatever
     * has to move still does, in the background — as everything did before.
     */
    private static final long IDENTITY_WAIT_SECONDS = 10;

    /**
     * Past each handler's own limit ({@link UuidChanges#HANDLER_TIMEOUT_SECONDS}), the margin the
     * login gives them, for the pools to hand the answers back.
     */
    private static final long FOLLOW_MARGIN_MILLIS = 500;

    /** Reference to the main plugin instance. */
    private final XCore core;

    /**
     * What the early pass found, kept for the late one. Keyed by the event itself, which every
     * listener of one login shares: the entry goes with it, and a UUID changed by a plugin in between
     * is noticed instead of being answered for the wrong player.
     */
    private final Map<AsyncPlayerPreLoginEvent, Pending> early = Collections.synchronizedMap(new WeakHashMap<>());

    /** A recognition under way, for the UUID it was started for. */
    private record Pending(UUID uuid, CompletableFuture<Arrival> arrival) {}

    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    /**
     * Who a connecting player turned out to be.
     *
     * @param kind       Known under this UUID, moved from another one, or new.
     * @param data       Their row, for a known or a moved player.
     * @param mojangUuid For a new player, their Mojang UUID when it was already looked up.
     * @param followed   For a moved player, the plugins moving their own data; done otherwise.
     */
    private record Arrival(Kind kind, PlayerData data, String mojangUuid, CompletableFuture<Void> followed) {

        enum Kind { KNOWN, MOVED, NEW }

        static Arrival known(PlayerData data) { return new Arrival(Kind.KNOWN, data, null, DONE); }

        static Arrival moved(PlayerData data, CompletableFuture<Void> followed) { return new Arrival(Kind.MOVED, data, null, followed); }

        static Arrival fresh(String mojangUuid) { return new Arrival(Kind.NEW, null, mojangUuid, DONE); }
    }

    /**
     * Creates a new PlayerListener.
     *
     * @param core The XCore plugin instance.
     */
    public PlayerListener(XCore core) {
        this.core = core;
    }

    /**
     * Recognises the player before the plugins' own pre-login checks, and waits for their data to
     * follow them when their UUID changed.
     *
     * <h2>Why here</h2>
     * This used to start at MONITOR — after every plugin's pre-login listener — and to run in the
     * background: the row usually moved after the player had joined, and nobody was told. XBans,
     * checking bans at HIGHEST, found nothing under the new UUID and let a banned player in, then
     * waited out XCore on a timer to look again. Now XCore recognises the player first, moves its row,
     * has every registered handler move the plugins' data (see {@link UuidChanges}), and the login
     * waits for all of it, bounded, before the checks that follow run.
     *
     * <h2>Why LOW, and not LOWEST</h2>
     * LOWEST is where the cheap gatekeepers sit — XLogin's connection limit and name rules, the
     * lockdown that refuses everyone while authentication is down — and where XLogin, in REAL UUID
     * mode, hands a verified player their Mojang UUID. What is recognised has to be the UUID the
     * player keeps, and a connection about to be refused is not worth a lookup. Within LOW, XCore's
     * listener is registered before those of the addons and plugins built on it, so it runs first;
     * every listener at NORMAL and later sees the data where it now is.
     *
     * <p>Runs on the login's own thread, which is there to wait, and which does the waiting work
     * itself: the Mojang requests and XCore's own steps run here, the queries on the database pool,
     * the handlers on the work pool (see {@link WaitingThreadExecutor}).</p>
     *
     * @param event The async pre-login event.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLoginRecognise(AsyncPlayerPreLoginEvent event) {
        if (!core.isMigrateUuidChanges()) return;
        if (event.getLoginResult() != Result.ALLOWED) return;

        UUID playerId = event.getUniqueId();
        String playerName = event.getName();
        WaitingThreadExecutor here = new WaitingThreadExecutor(core.getExecutor());
        CompletableFuture<Arrival> arrival = resolve(playerId, playerName, here);
        early.put(event, new Pending(playerId, arrival));

        Arrival found;
        try {
            if (!here.runUntil(arrival, System.nanoTime() + TimeUnit.SECONDS.toNanos(IDENTITY_WAIT_SECONDS))) {
                core.logger().sendWarning("Recognising " + playerName + " took more than " + IDENTITY_WAIT_SECONDS
                        + "s (database or Mojang slow): the login goes on, and whatever has to move follows in the background.");
                return;
            }
            found = arrival.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            // Said where it failed; the late pass reports it again with the outcome.
            return;
        }
        if (found.kind() != Arrival.Kind.MOVED) return;

        try {
            found.followed().get(TimeUnit.SECONDS.toMillis(UuidChanges.HANDLER_TIMEOUT_SECONDS) + FOLLOW_MARGIN_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            core.logger().sendWarning("The data of " + playerName + " was still being moved when the login had waited"
                    + " long enough: it goes on, and the move finishes in the background.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Never thrown: each handler's outcome is reported by UuidChanges.
        }
    }

    /**
     * Finishes the pre-login of a player nobody refused: creates the row of a new player, and fires
     * {@link PlayerDataLoadEvent}.
     * <p>
     * Runs at {@link EventPriority#MONITOR}, after every other handler, so a refused login never
     * creates a row: a flood of refused connections would otherwise be a flood of new players.
     * Nothing here holds the login back.
     * </p>
     *
     * @param event The async pre-login event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerPreLoginEvent(AsyncPlayerPreLoginEvent event) {
        Pending pending = early.remove(event);
        if (event.getLoginResult() != Result.ALLOWED) {
            return;
        }

        UUID playerId = event.getUniqueId();
        String playerName = event.getName();

        // Settled by the early pass, normally. Not when that pass did not run — migrate-uuid-changes
        // off, or the login refused when it came by and allowed again since — nor when a plugin
        // changed the UUID after it: this pass then recognises the player itself, as it always did.
        CompletableFuture<Arrival> arrival = pending != null && pending.uuid().equals(playerId)
                ? pending.arrival()
                : resolve(playerId, playerName, core.getExecutor());

        arrival.thenAcceptAsync(found -> {
            switch (found.kind()) {
                case KNOWN, MOVED -> Bukkit.getPluginManager().callEvent(new PlayerDataLoadEvent(found.data(), false));
                case NEW -> createNewPlayer(playerId, playerName, found.mojangUuid());
            }
        }, core.getExecutor()).exceptionally(ex -> {
            core.logger().sendError("Failed to load player data for " + playerName + " : " + rootMessage(ex));
            return null;
        });
    }

    /**
     * Finds who a connecting player is, moving their row when they came back under another UUID.
     *
     * @param playerId   The UUID they connect with.
     * @param playerName Their name.
     * @param steps      Where XCore's own steps run, Mojang requests included: the login thread
     *                   while it waits, the work pool otherwise. Never the database pool, which the
     *                   queries need and which is no place to wait for Mojang.
     * @return A future holding what was found; failing only when the move itself failed.
     */
    private CompletableFuture<Arrival> resolve(UUID playerId, String playerName, java.util.concurrent.Executor steps) {
        return core.playerCache().loadPlayer(playerId, playerName).thenComposeAsync(loaded -> {
            if (loaded.isPresent()) return CompletableFuture.completedFuture(Arrival.known(loaded.get()));
            if (!core.isMigrateUuidChanges()) return CompletableFuture.completedFuture(Arrival.fresh(null));

            // May be a request to Mojang.
            String mojangUuid = core.playerCache().fetchMojangUUID(playerName);

            // Before creating anything: is this somebody we already know under another UUID?
            return findPreviousIdentity(playerId, playerName, mojangUuid)
                    .exceptionally(ex -> {
                        core.logger().sendError("Failed to resolve the identity of " + playerName + " : " + rootMessage(ex));
                        return Optional.empty();
                    })
                    .thenComposeAsync(previous -> previous.isPresent()
                            ? move(previous.get(), playerId, playerName, mojangUuid, steps)
                            : CompletableFuture.completedFuture(Arrival.fresh(mojangUuid)), steps);
        }, steps);
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
     * the account as surely as the UUID would. Spelled exactly, then: the offline UUID comes from the
     * letters as typed, and {@code steve} is not {@code Steve} coming back under another UUID but
     * another account. The lookup ignores case, and that took Steve's row — and since every plugin
     * now follows this decision, Steve's sanctions and password with it — for whoever typed his name
     * in lower case.</p>
     *
     * @param playerId   The UUID this player arrived with.
     * @param playerName Their name.
     * @param mojangUuid Their Mojang UUID, or {@code "none"}.
     * @return A future holding the previous row, or empty when this really is a new player.
     */
    private CompletableFuture<Optional<PlayerData>> findPreviousIdentity(UUID playerId, String playerName, String mojangUuid) {
        CompletableFuture<Optional<PlayerData>> byMojang = isKnownMojang(mojangUuid)
                ? core.playerDAO().findByMojangUuidAsync(mojangUuid)
                : CompletableFuture.completedFuture(Optional.empty());

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
                boolean storedUnknown = !isKnownMojang(storedMojang);
                boolean sameMojang = isKnownMojang(mojangUuid) && mojangUuid.equalsIgnoreCase(storedMojang);
                boolean sameSpelling = playerName.equals(existing.getName());
                return ((storedUnknown && sameSpelling) || sameMojang) ? Optional.of(existing) : Optional.empty();
            });
        });
    }

    /**
     * Moves an existing row onto the UUID the player arrived with, keeping everything it holds, then
     * has the plugins follow.
     *
     * @param previous   The row found under the old UUID.
     * @param playerId   The new server UUID.
     * @param playerName The current name.
     * @param mojangUuid The Mojang UUID.
     * @param steps      Where XCore's own steps run (see {@link #resolve}).
     * @return A future holding the moved player, with the plugins following; failing when the
     *         database refused the move — nothing is created then, and the next login tries again.
     */
    private CompletableFuture<Arrival> move(PlayerData previous, UUID playerId, String playerName, String mojangUuid,
                                            java.util.concurrent.Executor steps) {
        UUID oldId = previous.getUuid();
        // The skin of the account it now is; the one it had when that cannot be fetched.
        String texture = core.playerCache().fetchSkinTexture(mojangUuid, previous.getTexture());
        PlayerData migrated = new PlayerData(playerId, playerName, texture, mojangUuid);
        migrated.setData(previous.getData());

        return core.playerDAO().moveAsync(migrated, oldId.toString()).thenComposeAsync(moved -> {
            if (!moved) {
                // Nothing left under the old UUID: another login moved it first, here or on another
                // server of the network, and that one told the plugins. The row is under the new UUID.
                return core.playerDAO().findByServerUuidAsync(playerId.toString()).thenApply(current -> {
                    if (current.isEmpty()) return Arrival.fresh(mojangUuid);
                    core.playerCache().addOrUpdateToCache(current.get());
                    return Arrival.known(current.get());
                });
            }

            // The old UUID and the name pointed at the old row, here and in Redis.
            core.playerCache().removeFromCache(previous);
            core.playerCache().addOrUpdateToCache(migrated);
            core.invalidateElsewhere(oldId, playerId);
            core.logger().sendInfo("Player <aqua>" + playerName + "</aqua> came back under a new UUID ("
                    + oldId + " → " + playerId + "). Their data was moved rather than duplicated.");

            // XCore's own deliveries and the plugins' data, side by side: they do not depend on each other.
            CompletableFuture<Void> followed = CompletableFuture.allOf(
                    moveDeliveries(oldId, playerId, playerName),
                    core.uuidChanges().dispatch(oldId, playerId, playerName, previous.getName()));
            return CompletableFuture.completedFuture(Arrival.moved(migrated, followed));
        }, steps).whenComplete((arrival, error) -> {
            if (error != null) {
                core.logger().sendError("Failed to migrate " + playerName + " from " + oldId + " : " + rootMessage(error));
            }
        });
    }

    /**
     * Hands the deliveries still waiting under the old UUID over to the new one.
     *
     * @return A future that never fails: a delivery left behind is reported, not fatal.
     */
    private CompletableFuture<Void> moveDeliveries(UUID oldId, UUID playerId, String playerName) {
        if (core.delivery() == null) return DONE;
        try {
            return core.delivery().moveUnclaimed(oldId, playerId).handle((count, error) -> {
                if (error != null) {
                    core.logger().sendWarning("The deliveries waiting for " + playerName
                            + " stay under their old UUID: " + rootMessage(error));
                } else if (count != null && count > 0) {
                    core.logger().sendDebug(count + " delivery(ies) of " + playerName + " moved to the new UUID.");
                }
                return null;
            });
        } catch (RuntimeException e) {
            core.logger().sendWarning("The deliveries waiting for " + playerName + " stay under their old UUID: " + e);
            return DONE;
        }
    }

    /**
     * Creates a new player entry: resolves Mojang UUID and skin texture,
     * inserts into the database, caches the result, and fires {@link PlayerDataLoadEvent}.
     *
     * @param playerId   The player's server UUID.
     * @param playerName The player's name.
     * @param mojangUuid The Mojang UUID when the recognition already looked it up, {@code null} otherwise.
     */
    private void createNewPlayer(UUID playerId, String playerName, String mojangUuid) {
        // Resolve Mojang UUID (uses the circuit-breaker-protected Mojang API)
        String resolved = mojangUuid != null ? mojangUuid : core.playerCache().fetchMojangUUID(playerName);

        // Resolve skin texture
        String texture = core.playerCache().fetchSkinTexture(resolved, "none");

        insertNew(playerId, playerName, texture, resolved);
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

            // A player can join before this row exists, and the balances read at join found none.
            CoinsManager coins = core.getCoinsManager();
            if (coins != null) coins.reread(playerId);

            core.logger().sendDebug("New player created: " + playerName + " (" + playerId + ").");

            // Fire the event
            Bukkit.getPluginManager().callEvent(new PlayerDataLoadEvent(playerData, true));
        }).exceptionally(ex -> {
            core.logger().sendError("Failed to create new player " + playerName + " : " + ex.getMessage());
            return null;
        });
    }

    /** Whether a stored or looked-up Mojang UUID is one, rather than empty or {@code "none"}. */
    private static boolean isKnownMojang(String mojangUuid) {
        return mojangUuid != null && !mojangUuid.isBlank() && !"none".equalsIgnoreCase(mojangUuid);
    }

    /** The message of what actually failed, under the wrappers futures add. */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause);
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

        core.playerCache().holdTempPlayerData(uuid);
        core.playerCache().getTempPlayerData(uuid).put(SESSION_START, System.currentTimeMillis());
        if (core.network() != null) core.network().announceJoin(player);

        // Menus, placeholders and Vault ask for balances on this thread: from now until the player
        // leaves, they are answered from memory.
        CoinsManager coins = core.getCoinsManager();
        if (coins != null) coins.hold(uuid);

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
        CoinsManager coins = core.getCoinsManager();
        if (coins != null) coins.release(uuid);

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
