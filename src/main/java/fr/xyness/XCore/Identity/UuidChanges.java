package fr.xyness.XCore.Identity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import fr.xyness.XCore.Events.PlayerUuidChangeEvent;
import fr.xyness.XCore.Utils.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The plugins that follow a player whose UUID changed, and the call that tells them.
 *
 * <h2>Why</h2>
 * XCore has long recognised a returning player under a new UUID — a server switched between offline
 * and online UUIDs, or put behind a proxy that changes them — and moved its own row instead of
 * creating a second one. It told nobody. Each plugin keyed on UUIDs either guessed on its own, with
 * rules of its own and a timing of its own (XBans waited at pre-login for XCore's row to disappear,
 * then looked again after join), or never followed the player at all: bans stopped applying,
 * passwords had to be chosen again, listings belonged to nobody.
 *
 * <h2>What happens now</h2>
 * During the player's pre-login, once XCore has committed the move of its own row, every handler
 * registered here is called with the old and the new UUID, side by side. The login waits until each
 * one has finished, failed, or used up its {@value #HANDLER_TIMEOUT_SECONDS} seconds, and only then
 * goes on to the plugins' own pre-login listeners: a ban recorded under the old UUID is found under
 * the new one by the check that follows. A failure or a timeout is reported under the plugin's name;
 * the login is not refused for it. Then {@link PlayerUuidChangeEvent} is fired for anyone else, with
 * nothing waiting on it.
 *
 * <pre>{@code
 * // In a plugin built on XCore (dropped automatically when it is disabled):
 * onUuidChange((oldUuid, newUuid, name) -> myStore.moveAsync(oldUuid, newUuid));
 *
 * // Any other plugin:
 * XCoreApiProvider.get().uuidChanges().register(this, (oldUuid, newUuid, name) -> myStore.moveAsync(oldUuid, newUuid));
 * }</pre>
 */
public final class UuidChanges {

    /** How long one handler may take before the login goes on without it. */
    public static final long HANDLER_TIMEOUT_SECONDS = 5;

    /** XCore's work pool: where the handlers are called, and the event fired. */
    private final Executor executor;

    /** XCore's console: where each handler's outcome is said. */
    private final Logger logger;

    /** In registration order. Copy-on-write: registered a handful of times, read at every UUID change. */
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    /**
     * One handler and who it belongs to.
     *
     * @param owner   The name failures are reported under.
     * @param plugin  The plugin it belongs to, {@code null} for an addon: a disabled plugin's handler
     *                is skipped and dropped, since its classes may already be gone.
     * @param handler The handler.
     */
    private record Registration(String owner, Plugin plugin, UuidChangeHandler handler) {}

    /**
     * @param executor XCore's work pool, never the database pool: a handler hands its SQL to that
     *                 one, and must not find itself queued behind its own work.
     * @param logger   XCore's console.
     */
    public UuidChanges(Executor executor, Logger logger) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers a handler for a plugin. Reported under the plugin's name, skipped once the plugin is
     * disabled; a plugin built on XCore uses its own {@code onUuidChange(handler)} instead, which
     * also takes it back when the plugin stops.
     *
     * @param plugin  The plugin the handler belongs to.
     * @param handler What moves the plugin's data; see {@link UuidChangeHandler}.
     */
    public void register(@NotNull Plugin plugin, @NotNull UuidChangeHandler handler) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(handler, "handler");
        registrations.add(new Registration(plugin.getName(), plugin, handler));
    }

    /**
     * Registers a handler under a name: what an addon, which is no Bukkit plugin, goes through.
     *
     * @param owner   The name failures are reported under.
     * @param handler What moves the data; see {@link UuidChangeHandler}.
     */
    public void register(@NotNull String owner, @NotNull UuidChangeHandler handler) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(handler, "handler");
        registrations.add(new Registration(owner, null, handler));
    }

    /**
     * Removes one handler.
     *
     * @param handler The handler, as it was registered.
     * @return {@code true} when it was registered.
     */
    public boolean unregister(@NotNull UuidChangeHandler handler) {
        return registrations.removeIf(registration -> registration.handler() == handler);
    }

    /**
     * Removes every handler of a plugin.
     *
     * @param plugin The plugin.
     * @return How many were removed.
     */
    public int unregisterAll(@NotNull Plugin plugin) {
        List<Registration> gone = new ArrayList<>();
        for (Registration registration : registrations) {
            if (registration.plugin() == plugin) gone.add(registration);
        }
        registrations.removeAll(gone);
        return gone.size();
    }

    /**
     * Removes every handler registered under a name.
     *
     * @param owner The name.
     * @return How many were removed.
     */
    public int unregisterAll(@NotNull String owner) {
        List<Registration> gone = new ArrayList<>();
        for (Registration registration : registrations) {
            if (registration.plugin() == null && registration.owner().equals(owner)) gone.add(registration);
        }
        registrations.removeAll(gone);
        return gone.size();
    }

    /** @return Who has a handler registered, once each, in registration order. */
    public List<String> owners() {
        Set<String> owners = new LinkedHashSet<>();
        for (Registration registration : registrations) owners.add(registration.owner());
        return List.copyOf(owners);
    }

    // -------------------------------------------------------------------------
    // Dispatch (XCore's, once its own row has moved)
    // -------------------------------------------------------------------------

    /**
     * Calls every handler for one change, side by side.
     *
     * <p>XCore calls this once the move of its own row is committed; a plugin never needs to. The
     * future completes once each handler has finished, failed or timed out, and never exceptionally:
     * what went wrong is in the console, under the name of the plugin it happened to. The event goes
     * out after that, on the work pool, with nothing waiting for its listeners.</p>
     *
     * @param oldUuid      The UUID the data was stored under.
     * @param newUuid      The UUID the player is connecting with.
     * @param name         The name the player is connecting with.
     * @param previousName The name stored with the old UUID.
     * @return A future completing once every handler is done with.
     */
    public CompletableFuture<Void> dispatch(UUID oldUuid, UUID newUuid, String name, String previousName) {
        List<CompletableFuture<Void>> running = new ArrayList<>();
        for (Registration registration : registrations) {
            if (registration.plugin() != null && !registration.plugin().isEnabled()) {
                // Disabled without taking its handler back: its classes may be unloaded already.
                registrations.remove(registration);
                continue;
            }
            running.add(run(registration, oldUuid, newUuid, name));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(running.toArray(new CompletableFuture[0]));
        all.whenComplete((ignored, error) -> {
            try {
                executor.execute(() -> fire(oldUuid, newUuid, name, previousName));
            } catch (RuntimeException rejected) {
                logger.sendDebug("UUID change event of " + name + " not fired: " + rejected);
            }
        });
        return all;
    }

    /**
     * One handler, bounded in time, its outcome reported and never thrown.
     *
     * <p>The returned future is what the login waits for: it completes when the handler does, or
     * when its time is up — whichever comes first, decided once. The handler itself is not stopped
     * (nothing can stop a running SQL statement safely from here), and how it ends is still said.</p>
     */
    private CompletableFuture<Void> run(Registration registration, UUID oldUuid, UUID newUuid, String name) {
        long start = System.nanoTime();
        CompletableFuture<Object> work;
        try {
            work = CompletableFuture
                    .supplyAsync(() -> registration.handler().onUuidChange(oldUuid, newUuid, name), executor)
                    .thenCompose(UuidChanges::orDone);
        } catch (RuntimeException rejected) {
            work = CompletableFuture.failedFuture(rejected);
        }

        AtomicInteger state = new AtomicInteger(PENDING);
        CompletableFuture<Void> bounded = new CompletableFuture<>();
        work.whenComplete((value, error) -> {
            if (state.compareAndSet(PENDING, IN_TIME)) {
                if (error != null) {
                    report(registration, oldUuid, newUuid, name, error);
                } else {
                    logger.sendDebug(escape(registration.owner() + " moved the data of " + name + " to the new UUID in "
                            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms."));
                }
                bounded.complete(null);
                return;
            }
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            if (error == null) {
                logger.sendInfo(escape(registration.owner() + " finished moving the data of " + name
                        + " to the new UUID after " + seconds + "s, once the login had gone on."));
            } else {
                report(registration, oldUuid, newUuid, name, error);
            }
        });
        try {
            CompletableFuture.delayedExecutor(HANDLER_TIMEOUT_SECONDS, TimeUnit.SECONDS, executor).execute(() -> {
                if (!state.compareAndSet(PENDING, LATE)) return;
                logger.sendWarning(escape(registration.owner() + " did not finish moving the data of "
                        + name + " (" + oldUuid + " → " + newUuid + ") within " + HANDLER_TIMEOUT_SECONDS
                        + "s: the login went on without waiting. It is still running; how it ends will be said here."));
                bounded.complete(null);
            });
        } catch (RuntimeException rejected) {
            // No timer to be had: the login's own limit still applies.
        }
        return bounded;
    }

    /** {@link #run}: the handler has not answered yet. */
    private static final int PENDING = 0;
    /** {@link #run}: it answered within its time. */
    private static final int IN_TIME = 1;
    /** {@link #run}: its time ran out first. */
    private static final int LATE = 2;

    private void report(Registration registration, UUID oldUuid, UUID newUuid, String name, Throwable error) {
        Throwable cause = unwrap(error);
        logger.sendWarning(escape(registration.owner() + " could not move the data of " + name + " ("
                + oldUuid + " → " + newUuid + "): " + cause + ". What it did not move is still under the old UUID."));
    }

    private void fire(UUID oldUuid, UUID newUuid, String name, String previousName) {
        try {
            Bukkit.getPluginManager().callEvent(new PlayerUuidChangeEvent(oldUuid, newUuid, name, previousName));
        } catch (Throwable t) {
            // The server already reports a listener that throws; this is the event itself failing.
            logger.sendDebug("UUID change event of " + name + " failed: " + t);
        }
    }

    /** A handler that returned {@code null} is done. */
    private static CompletableFuture<Object> orDone(CompletableFuture<?> future) {
        if (future == null) return CompletableFuture.completedFuture(null);
        return future.thenApply(value -> (Object) value);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Names and exception messages printed as text, never read as formatting. */
    private static String escape(String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }
}
