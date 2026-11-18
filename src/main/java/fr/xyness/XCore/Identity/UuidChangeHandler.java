package fr.xyness.XCore.Identity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What a plugin hands XCore so that its own data follows a player whose UUID changed.
 *
 * <p>A server that switches between offline and online UUIDs (or is put behind a proxy that changes
 * them) gives every returning player a new UUID. XCore recognises them — by Mojang UUID first, by
 * name otherwise ({@code migrate-uuid-changes}) — and moves its own row. Every handler is then called
 * with the old and the new UUID, during the player's pre-login, and the login waits for all of them
 * (five seconds each at most) before it goes on: the plugins' own login checks, which run after,
 * already see the data where it now is.</p>
 *
 * <pre>{@code
 * onUuidChange((oldUuid, newUuid, name) -> CompletableFuture.runAsync(() -> {
 *     try (Connection conn = api().getDataSource().getConnection();
 *          PreparedStatement ps = conn.prepareStatement("UPDATE my_table SET player_uuid = ? WHERE player_uuid = ?")) {
 *         ps.setString(1, newUuid.toString());
 *         ps.setString(2, oldUuid.toString());
 *         ps.executeUpdate();
 *     } catch (SQLException e) {
 *         throw new CompletionException(e);
 *     }
 * }, api().getDbExecutor()));
 * }</pre>
 *
 * @see UuidChanges
 */
@FunctionalInterface
public interface UuidChangeHandler {

    /**
     * Moves this plugin's data from the old UUID to the new one.
     *
     * <p>Called on XCore's work pool, after XCore has committed the move of its own row. Return at
     * once with a future and do the SQL on the database pool ({@code api().getDbExecutor()}); a
     * future that fails, or that is still running after five seconds, is reported in the console
     * under this plugin's name and the login goes on without it. Nothing is retried, so make the
     * move one transaction, and harmless to run twice: on a network sharing one database, the
     * server where XCore moved the row is the one that calls the handlers.</p>
     *
     * @param oldUuid The UUID the player's data was stored under.
     * @param newUuid The UUID the player is connecting with.
     * @param name    The name the player is connecting with.
     * @return A future completing once the data has moved; {@code null} counts as done.
     */
    CompletableFuture<?> onUuidChange(UUID oldUuid, UUID newUuid, String name);
}
