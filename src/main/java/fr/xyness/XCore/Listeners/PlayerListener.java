package fr.xyness.XCore.Listeners;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
import net.kyori.adventure.text.minimessage.MiniMessage;

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
     * How long a login waits for XCore to recognise the player, all of it: their row, the Mojang UUID
     * of a name not seen before, the search for a row they left under another UUID, and the move of
     * that row. One deadline for the whole, kept by the thread that waits, which runs none of the
     * work. Past it the login goes on, and nothing the recognition finds afterwards is moved.
     */
    private static final long IDENTITY_WAIT_SECONDS = 10;

    /**
     * A move that began before the deadline is one statement already on its way to the database: the
     * login gives it this much longer, rather than going on while the row is between two UUIDs.
     */
    private static final long MOVE_GRACE_MILLIS = 2000;

    /**
     * Past each handler's own limit ({@link UuidChanges#HANDLER_TIMEOUT_SECONDS}), the margin the
     * login gives them, for the pools to hand the answers back.
     */
    private static final long FOLLOW_MARGIN_MILLIS = 500;

    /** How long a move waits for the skin of the account it now is; past it the row keeps its own. */
    private static final long SKIN_WAIT_MILLIS = 2000;

    /** How long the row of a new player waits for their skin before it is written without one. */
    private static final long NEW_SKIN_WAIT_MILLIS = 5000;

    /** Rows read carrying one Mojang UUID: past one, the answer is "several" anyway. */
    private static final int MOJANG_MATCH_LIMIT = 8;

    /** Rows read under one name in any spelling, among which the exact spelling is looked for. */
    private static final int NAME_MATCH_LIMIT = 64;

    /** What the players table holds for "no Mojang account". */
    private static final String NONE = "none";

    /** Reference to the main plugin instance. */
    private final XCore core;

    /**
     * What the early pass found, kept for the late one. Keyed by the event itself, which every
     * listener of one login shares: the entry goes with it, and a UUID changed by a plugin in between
     * is noticed instead of being answered for the wrong player.
     */
    private final Map<AsyncPlayerPreLoginEvent, Pending> early = Collections.synchronizedMap(new WeakHashMap<>());

    /** A recognition under way, for the UUID it was started for. */
    private record Pending(UUID uuid, Recognition recognition, CompletableFuture<Arrival> arrival) {}

    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    /**
     * Whether a recognition may still move anything.
     *
     * <p>A move goes out only while the login waits for it. The waiting thread closes the recognition
     * when its time is up, and the move takes it over just before its statement is sent: whichever
     * comes first wins, once. A recognition nobody waits for — the late pass's own — starts closed,
     * since the plugins' login checks have already run by then.</p>
     */
    private static final class Recognition {
        private static final int OPEN = 0;
        private static final int MOVING = 1;
        private static final int CLOSED = 2;

        private final AtomicInteger state;

        /** @param awaited Whether a login waits for it; a recognition nobody waits for never moves. */
        Recognition(boolean awaited) {
            this.state = new AtomicInteger(awaited ? OPEN : CLOSED);
        }

        /** @return {@code true} when the move may be sent: the login waits for it from now on. */
        boolean beginMove() {
            return state.compareAndSet(OPEN, MOVING);
        }

        /** @return {@code true} when nothing will be moved any more; {@code false} when a move has begun. */
        boolean close() {
            return state.compareAndSet(OPEN, CLOSED) || state.get() == CLOSED;
        }
    }

    /**
     * Who a connecting player turned out to be.
     *
     * @param kind       Known under this UUID, moved from another one, new, or left as found.
     * @param data       Their row, for a known or a moved player.
     * @param mojangUuid For a new player, their Mojang UUID when it was already looked up.
     * @param followed   For a moved player, the plugins moving their own data; done otherwise.
     */
    private record Arrival(Kind kind, PlayerData data, String mojangUuid, CompletableFuture<Void> followed) {

        /**
         * {@code LEFT}: a row that may be theirs was found and not moved — the login had gone on, or
         * whether it is theirs could not be checked. No row is created in its place either: one under
         * the new UUID would make them a known player, and the old row would never be looked for again.
         * Their next login looks again.
         */
        enum Kind { KNOWN, MOVED, NEW, LEFT }

        static Arrival known(PlayerData data) { return new Arrival(Kind.KNOWN, data, null, DONE); }

        static Arrival moved(PlayerData data, CompletableFuture<Void> followed) { return new Arrival(Kind.MOVED, data, null, followed); }

        static Arrival fresh(String mojangUuid) { return new Arrival(Kind.NEW, null, mojangUuid, DONE); }

        static Arrival left() { return new Arrival(Kind.LEFT, null, null, DONE); }
    }

    /**
     * What the search for a row left under another UUID came to.
     *
     * @param row       The row to move — or the player's own, when {@code own}; {@code null} for nobody.
     * @param own       A row is already under the connecting UUID: a known player, nothing to move.
     * @param undecided A row may be theirs and could not be checked: nothing is moved, nothing created.
     */
    private record Search(PlayerData row, boolean own, boolean undecided) {
        static final Search NOBODY = new Search(null, false, false);
        static final Search UNDECIDED = new Search(null, false, true);

        static Search previous(PlayerData row) { return new Search(row, false, false); }

        static Search ownRow(PlayerData row) { return new Search(row, true, false); }
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
     * <h2>A bound that holds</h2>
     * The login thread only waits: the queries run on the database pool, the Mojang requests on
     * threads of their own, the steps in between on the work pool. Its wait is one deadline for the
     * whole recognition, {@value #IDENTITY_WAIT_SECONDS} seconds, then at most
     * {@value #MOVE_GRACE_MILLIS} ms for a move whose statement was already sent, then the handlers'
     * own limit. It used to run the steps itself and look at the clock between two of them: one
     * Mojang request — a permit to wait for, two timeouts, retries — went past the deadline by as
     * much as it liked. And a recognition that ended after the login had gone on still moved the
     * row, and had the plugins move theirs, behind the checks that were meant to see the result.
     * Past the deadline it now moves nothing: see {@link Recognition}.
     *
     * @param event The async pre-login event.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLoginRecognise(AsyncPlayerPreLoginEvent event) {
        if (!core.isMigrateUuidChanges()) return;
        if (event.getLoginResult() != Result.ALLOWED) return;

        UUID playerId = event.getUniqueId();
        String playerName = event.getName();
        Recognition recognition = new Recognition(true);
        CompletableFuture<Arrival> arrival = resolve(playerId, playerName, recognition);
        early.put(event, new Pending(playerId, recognition, arrival));

        Arrival found;
        try {
            found = arrival.get(IDENTITY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            if (recognition.close()) {
                core.logger().sendWarning("Recognising " + escape(playerName) + " took more than " + IDENTITY_WAIT_SECONDS
                        + "s (database or Mojang slow): the login goes on, and nothing is moved for it any more."
                        + " Should they have come back under a new UUID, their data follows them at their next login.");
                return;
            }
            // The move had begun: its statement is on its way, and gets a moment more.
            try {
                found = arrival.get(MOVE_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillMoving) {
                core.logger().sendWarning("The row of " + escape(playerName) + " was still being moved when the login had"
                        + " waited long enough: it goes on, the move finishes in the background, and the plugins follow it then.");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                return;
            }
        } catch (InterruptedException e) {
            recognition.close();
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException e) {
            // Said where it failed; the late pass reports it again with the outcome.
            return;
        }
        if (found.kind() != Arrival.Kind.MOVED) return;

        try {
            found.followed().get(TimeUnit.SECONDS.toMillis(UuidChanges.HANDLER_TIMEOUT_SECONDS) + FOLLOW_MARGIN_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            core.logger().sendWarning("The data of " + escape(playerName) + " was still being moved when the login had waited"
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
        // changed the UUID after it: this pass then recognises the player itself, and since every
        // check has already run by now, it may find a row of theirs but moves none.
        CompletableFuture<Arrival> arrival = pending != null && pending.uuid().equals(playerId)
                ? pending.arrival()
                : resolve(playerId, playerName, new Recognition(false));

        arrival.thenAcceptAsync(found -> {
            switch (found.kind()) {
                case KNOWN, MOVED -> Bukkit.getPluginManager().callEvent(new PlayerDataLoadEvent(found.data(), false));
                case NEW -> createNewPlayer(playerId, playerName, found.mojangUuid());
                case LEFT -> { }   // said where it was decided
            }
        }, core.getExecutor()).exceptionally(ex -> {
            core.logger().sendError("Could not recognise " + escape(playerName) + " (" + escape(rootMessage(ex))
                    + "): no row is created for them now, and their next login tries again.");
            return null;
        });
    }

    /**
     * Finds who a connecting player is, moving their row when they came back under another UUID.
     *
     * @param playerId    The UUID they connect with.
     * @param playerName  Their name.
     * @param recognition Whether a move may still go out (see {@link Recognition}).
     * @return A future holding what was found; failing when the database could not answer or the
     *         move itself failed — nothing is created then, and the next login tries again.
     */
    private CompletableFuture<Arrival> resolve(UUID playerId, String playerName, Recognition recognition) {
        Executor work = core.getExecutor();
        return core.playerCache().loadPlayer(playerId, playerName).thenComposeAsync(loaded -> {
            if (loaded.isPresent()) return CompletableFuture.completedFuture(Arrival.known(loaded.get()));
            if (!core.isMigrateUuidChanges()) return CompletableFuture.completedFuture(Arrival.fresh(null));

            // Unknown — null — when Mojang could not be asked, which is not "no account" ("none").
            return mojangUuidOf(playerId, playerName)
                    .exceptionally(error -> {
                        core.logger().sendDebug("No Mojang UUID for " + escape(playerName) + " (" + escape(rootMessage(error))
                                + "): recognised without it.");
                        return null;
                    })
                    .thenCompose(mojangUuid -> findPreviousIdentity(playerId, playerName, mojangUuid)
                            .thenComposeAsync(search -> settle(search, playerId, playerName, mojangUuid, recognition), work));
        }, work);
    }

    /** Turns what the search found into an arrival, moving the row found when there is one. */
    private CompletableFuture<Arrival> settle(Search search, UUID playerId, String playerName, String mojangUuid,
                                              Recognition recognition) {
        if (search.own()) {
            // Under this very UUID after all: the cache had not seen it yet.
            core.playerCache().addOrUpdateToCache(search.row());
            return CompletableFuture.completedFuture(Arrival.known(search.row()));
        }
        if (search.undecided()) return CompletableFuture.completedFuture(Arrival.left());
        if (search.row() == null) return CompletableFuture.completedFuture(Arrival.fresh(mojangUuid));
        return move(search.row(), playerId, playerName, mojangUuid, recognition);
    }

    /**
     * The Mojang UUID a connection goes by.
     *
     * <p>A version-4 UUID is Mojang's own: only an online-mode login hands one out, or a proxy that
     * forwards it, or XLogin's REAL mode once Mojang has vouched for the player. It is the player's
     * Mojang UUID, verified, and no request is needed. Any other UUID says nothing of who connects:
     * the Mojang UUID of the account that owns the name is asked for — Mojang ignores case, so it is
     * the same for {@code Steve} and {@code steve} — and it says who owns the name, nothing more.</p>
     *
     * @return A future completing with the Mojang UUID or {@code "none"}; failing when Mojang could
     *         not be asked.
     */
    private CompletableFuture<String> mojangUuidOf(UUID playerId, String playerName) {
        if (isMojangIssued(playerId)) return CompletableFuture.completedFuture(playerId.toString());
        return core.playerCache().fetchMojangUUIDAsync(playerName);
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
     * <h2>What counts</h2>
     * A row found is moved onto this connection only by these rules. Every plugin follows the
     * decision, before any of them has checked the login: a row handed to the wrong player is that
     * player's balance, sanctions and password handed with it.
     * <table>
     *   <caption>When a row left under another UUID is this player's</caption>
     *   <tr><th>Found by</th><th>Connecting UUID</th><th>Stored row</th><th>Outcome</th></tr>
     *   <tr><td>any way</td><td>any</td><td>name in other capitals</td><td>never moved</td></tr>
     *   <tr><td>any way</td><td>version 4</td><td>version-4 UUID</td><td>never moved: two Mojang accounts</td></tr>
     *   <tr><td>Mojang UUID</td><td>version 4 (verified)</td><td>same name</td><td>moved</td></tr>
     *   <tr><td>Mojang UUID</td><td>version 4 (verified)</td><td>another name</td><td>moved: renamed on Mojang</td></tr>
     *   <tr><td>Mojang UUID</td><td>other (looked up)</td><td>same name</td><td>moved</td></tr>
     *   <tr><td>Mojang UUID</td><td>other (looked up)</td><td>another name</td><td>not moved</td></tr>
     *   <tr><td>name, exactly</td><td>any</td><td>no Mojang UUID, or the connection's</td><td>moved</td></tr>
     *   <tr><td>name, exactly</td><td>any</td><td>another Mojang UUID</td><td>not moved: renamed player</td></tr>
     *   <tr><td>name, exactly</td><td>other, Mojang not answering</td><td>a Mojang UUID</td><td>left; nothing created</td></tr>
     * </table>
     * Several rows carrying the Mojang UUID: none is taken on it, it is said in the console, and
     * the exact-name step still applies. Several rows passing that step: none is moved, said too,
     * and the player starts a row of their own.
     *
     * <p>Only a version-4 UUID proves who connects (see {@link #mojangUuidOf}). Anything else is a
     * name typed by the client, and the Mojang UUID behind it is that of whoever owns the name: XCore
     * stores it for every player, so {@code Steve} and {@code steve} on an offline server — and every
     * row XCore 1.x wrote, whose lookups were all made in lower case — share one. Matching on it
     * alone gave Steve's row, and every plugin's data with it, to whoever typed {@code steve}. A name
     * maps to one offline UUID only spelled exactly: {@code steve} is another account. And a Mojang
     * name changes hands — its owner renames, somebody else takes it — so two version-4 UUIDs are two
     * accounts whatever names they carry.</p>
     *
     * @param playerId   The UUID this player arrived with.
     * @param playerName Their name.
     * @param mojangUuid Their Mojang UUID (see {@link #mojangUuidOf}), {@code "none"} when the name has
     *                   no account, {@code null} when Mojang could not be asked.
     * @return A future holding what was found; failing when the database could not answer.
     */
    private CompletableFuture<Search> findPreviousIdentity(UUID playerId, String playerName, String mojangUuid) {
        CompletableFuture<List<PlayerData>> byMojang = isKnownMojang(mojangUuid)
                ? core.playerDAO().findAllByMojangUuidAsync(mojangUuid, MOJANG_MATCH_LIMIT)
                : CompletableFuture.completedFuture(List.of());

        return byMojang.thenCompose(sharing -> {
            for (PlayerData row : sharing) {
                if (row.getUuid().equals(playerId)) return CompletableFuture.completedFuture(Search.ownRow(row));
            }
            if (sharing.size() == 1) {
                PlayerData only = sharing.get(0);
                if (takenByMojang(only, playerId, playerName)) {
                    return CompletableFuture.completedFuture(Search.previous(only));
                }
                if (isCaseVariant(only.getName(), playerName)) {
                    core.logger().sendInfo("Player " + escape(playerName) + " is not " + escape(only.getName())
                            + " (" + only.getUuid() + ") coming back: the name differs in capitals only, and a name"
                            + " counts spelled exactly. Each keeps an account of their own.");
                }
            } else if (sharing.size() > 1) {
                core.logger().sendWarning("Several players carry the Mojang UUID " + mojangUuid + " (" + describe(sharing)
                        + "): " + escape(playerName) + " takes none of them on it.");
            }
            return core.playerDAO().findAllByNameAsync(playerName, NAME_MATCH_LIMIT)
                    .thenApply(named -> byExactName(named, playerId, playerName, mojangUuid));
        });
    }

    /**
     * Whether the one row carrying the connection's Mojang UUID is this player's, under another UUID.
     *
     * @param row        That row.
     * @param playerId   The UUID connecting.
     * @param playerName The name connecting.
     */
    private static boolean takenByMojang(PlayerData row, UUID playerId, String playerName) {
        String stored = row.getName();
        if (stored == null || isCaseVariant(stored, playerName)) return false;
        boolean verified = isMojangIssued(playerId);
        // Its own UUID is a Mojang one, and not this player's: the Mojang UUID stored with it is not
        // what it is.
        if (verified && isMojangIssued(row.getUuid())) return false;
        if (stored.equals(playerName)) return true;
        // Another name: the same Mojang account renamed — only when the connection proves to be it.
        return verified;
    }

    /**
     * Picks, among the rows stored under this name in any spelling, the one that is this player's.
     *
     * @param rows       The rows, every spelling.
     * @param playerId   The UUID connecting.
     * @param playerName The name connecting.
     * @param mojangUuid The connection's Mojang UUID; {@code null} when Mojang could not be asked.
     */
    private Search byExactName(List<PlayerData> rows, UUID playerId, String playerName, String mojangUuid) {
        List<PlayerData> taken = new ArrayList<>();
        PlayerData unchecked = null;
        for (PlayerData row : rows) {
            if (row.getUuid().equals(playerId)) return Search.ownRow(row);
            // Other capitals are another player.
            if (!playerName.equals(row.getName())) continue;
            // Two Mojang accounts: the name changed hands.
            if (isMojangIssued(playerId) && isMojangIssued(row.getUuid())) continue;
            String stored = row.getMojangUUID();
            if (!isKnownMojang(stored)) {
                taken.add(row);
            } else if (mojangUuid == null) {
                unchecked = row;
            } else if (sameUuid(stored, mojangUuid)) {
                taken.add(row);
            }
            // Otherwise it carries another Mojang account under this name: a player who was renamed,
            // whose data is not handed to whoever claimed the name after them.
        }
        if (unchecked != null) {
            core.logger().sendWarning("Player " + escape(playerName) + " may be " + unchecked.getUuid() + " coming back under a"
                    + " new UUID, and Mojang could not be asked to check it: nothing is moved or created for them now,"
                    + " their next login looks again.");
            return Search.UNDECIDED;
        }
        if (taken.size() == 1) return Search.previous(taken.get(0));
        if (taken.size() > 1) {
            core.logger().sendWarning("Several players were stored under the name " + escape(playerName) + " ("
                    + describe(taken) + "): none of them is moved onto " + playerId + ", who starts an account of their own.");
        }
        return Search.NOBODY;
    }

    /**
     * Moves an existing row onto the UUID the player arrived with, keeping everything it holds, then
     * has the plugins follow.
     *
     * @param previous    The row found under the old UUID.
     * @param playerId    The new server UUID.
     * @param playerName  The current name.
     * @param mojangUuid  The connection's Mojang UUID (see {@link #mojangUuidOf}), {@code null} when unknown.
     * @param recognition Whether the move may still go out.
     * @return A future holding the moved player, with the plugins following, or {@code LEFT} when the
     *         login had gone on first; failing when the database refused the move — nothing is
     *         created then, and the next login tries again.
     */
    private CompletableFuture<Arrival> move(PlayerData previous, UUID playerId, String playerName, String mojangUuid,
                                            Recognition recognition) {
        UUID oldId = previous.getUuid();
        // A version-4 connection keeps its own UUID as its Mojang UUID: the verified one.
        String storedMojang = isKnownMojang(mojangUuid) ? mojangUuid : NONE;
        Executor work = core.getExecutor();

        // The skin of the account it now is; the one it had when that cannot be fetched in a moment.
        return skinOf(storedMojang, previous.getTexture(), SKIN_WAIT_MILLIS).thenComposeAsync(texture -> {
            // Nothing goes out once the login has gone on without the answer: the plugins' checks
            // have run on the data where it was, and they must not find it elsewhere afterwards.
            if (!recognition.beginMove()) {
                core.logger().sendWarning("Player " + escape(playerName) + " was recognised as " + oldId + " after their login"
                        + " had gone on: their data stays under that UUID for now, and follows them at their next login.");
                return CompletableFuture.completedFuture(Arrival.left());
            }

            PlayerData migrated = new PlayerData(playerId, playerName, texture, storedMojang);
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
                core.logger().sendInfo("Player <aqua>" + escape(playerName) + "</aqua> came back under a new UUID ("
                        + oldId + " → " + playerId + "). Their data was moved rather than duplicated.");

                // XCore's own deliveries and the plugins' data, side by side: they do not depend on each other.
                CompletableFuture<Void> followed = CompletableFuture.allOf(
                        moveDeliveries(oldId, playerId, playerName),
                        core.uuidChanges().dispatch(oldId, playerId, playerName, previous.getName()));
                return CompletableFuture.completedFuture(Arrival.moved(migrated, followed));
            }, work);
        }, work).whenComplete((arrival, error) -> {
            if (error != null) {
                core.logger().sendError("Failed to migrate " + escape(playerName) + " from " + oldId + " : "
                        + escape(rootMessage(error)));
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
                    core.logger().sendWarning("The deliveries waiting for " + escape(playerName)
                            + " stay under their old UUID: " + escape(rootMessage(error)));
                } else if (count != null && count > 0) {
                    core.logger().sendDebug(count + " delivery(ies) of " + escape(playerName) + " moved to the new UUID.");
                }
                return null;
            });
        } catch (RuntimeException e) {
            core.logger().sendWarning("The deliveries waiting for " + escape(playerName) + " stay under their old UUID: "
                    + escape(String.valueOf(e)));
            return DONE;
        }
    }

    /**
     * Creates a new player entry: resolves Mojang UUID and skin texture,
     * inserts into the database, caches the result, and fires {@link PlayerDataLoadEvent}.
     *
     * <p>The requests to Mojang run on their own threads (see {@link #mojangUuidOf}): this is called
     * on the work pool, which is not to be held for as long as Mojang takes.</p>
     *
     * @param playerId   The player's server UUID.
     * @param playerName The player's name.
     * @param mojangUuid The Mojang UUID when the recognition already looked it up, {@code null} otherwise.
     */
    private void createNewPlayer(UUID playerId, String playerName, String mojangUuid) {
        CompletableFuture<String> mojang = mojangUuid != null
                ? CompletableFuture.completedFuture(mojangUuid)
                : mojangUuidOf(playerId, playerName).exceptionally(error -> NONE);

        mojang.thenCompose(resolved -> skinOf(resolved, NONE, NEW_SKIN_WAIT_MILLIS)
                        .thenAcceptAsync(texture -> insertNew(playerId, playerName, texture, resolved), core.getExecutor()))
                .exceptionally(ex -> {
                    core.logger().sendError("Failed to create new player " + escape(playerName) + " : " + escape(rootMessage(ex)));
                    return null;
                });
    }

    /**
     * The skin texture of a Mojang account, fetched off the calling thread and waited for a moment.
     *
     * @param mojangUuid The Mojang UUID, or {@code "none"}.
     * @param fallback   What to answer when there is no skin, or not in time.
     * @param waitMillis How long to wait for it.
     */
    private CompletableFuture<String> skinOf(String mojangUuid, String fallback, long waitMillis) {
        if (!isKnownMojang(mojangUuid)) return CompletableFuture.completedFuture(fallback);
        return core.playerCache().fetchSkinTextureAsync(mojangUuid, fallback)
                .completeOnTimeout(fallback, waitMillis, TimeUnit.MILLISECONDS);
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
        PlayerData playerData = new PlayerData(playerId, playerName, texture, isKnownMojang(mojangUuid) ? mojangUuid : NONE);

        core.playerDAO().insertAsync(playerData).thenRun(() -> {
            // Cache the new player in both UUID and name caches
            core.playerCache().addOrUpdateToCache(playerData);

            // A player can join before this row exists, and the balances read at join found none.
            CoinsManager coins = core.getCoinsManager();
            if (coins != null) coins.reread(playerId);

            core.logger().sendDebug("New player created: " + escape(playerName) + " (" + playerId + ").");

            // Fire the event
            Bukkit.getPluginManager().callEvent(new PlayerDataLoadEvent(playerData, true));
        }).exceptionally(ex -> {
            core.logger().sendError("Failed to create new player " + escape(playerName) + " : " + escape(rootMessage(ex)));
            return null;
        });
    }

    /** Whether a stored or looked-up Mojang UUID is one, rather than empty or {@code "none"}. */
    private static boolean isKnownMojang(String mojangUuid) {
        return mojangUuid != null && !mojangUuid.isBlank() && !NONE.equalsIgnoreCase(mojangUuid);
    }

    /** Whether a server UUID is one Mojang issued: version 4, never derived from a name. */
    private static boolean isMojangIssued(UUID uuid) {
        return uuid != null && uuid.version() == 4;
    }

    /** Whether two names are the same letters in other capitals: two players, never one. */
    private static boolean isCaseVariant(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b) && !a.equals(b);
    }

    /** Two UUIDs written either way, with dashes or without, in any case. */
    private static boolean sameUuid(String a, String b) {
        return a != null && b != null
                && a.replace("-", "").trim().equalsIgnoreCase(b.replace("-", "").trim());
    }

    /** The rows, as the console names them: name and UUID. */
    private static String describe(List<PlayerData> rows) {
        StringBuilder out = new StringBuilder();
        for (PlayerData row : rows) {
            if (out.length() > 0) out.append(", ");
            out.append(escape(row.getName())).append(' ').append(row.getUuid());
        }
        return out.toString();
    }

    /** A name or a message printed as text, never read as formatting. */
    private static String escape(String text) {
        return text == null ? "null" : MiniMessage.miniMessage().escapeTags(text);
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
