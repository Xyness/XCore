package fr.xyness.XCore.Utils;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import fr.xyness.XCore.XCore;

/**
 * Compatibility layer for task scheduling across Bukkit and Folia.
 * <p>
 * On Folia, tasks must be submitted to region-specific schedulers via reflection
 * because the Folia API classes are not available at compile time.
 * On standard Bukkit/Paper servers, the classic {@code BukkitScheduler} is used directly.
 * </p>
 * <p>
 * Folia's scheduler methods accept {@code Consumer<ScheduledTask>} instead of {@code Runnable}.
 * Since the Folia classes are not on the compile classpath, a JDK {@link Proxy} is used
 * to create a Consumer implementation at runtime. This is encapsulated in {@link #wrapConsumer(Runnable)}.
 * </p>
 */
@SuppressWarnings("unchecked")
public class SchedulerAdapter {

	/**
	 * The plugin every task is scheduled under.
	 *
	 * <p>Bukkit and Folia both cancel a plugin's tasks when that plugin is disabled, and refuse new
	 * ones from it afterwards. A plugin built on XCore gets an adapter of its own, so what it
	 * schedules stops with it; XCore's adapter, the one addons share, stays owned by XCore.</p>
	 */
    private final Plugin owner;

	/** Where scheduling failures are reported: the owner's own logger. */
    private final Logger logger;

	/** Whether the server is running Folia (detected at construction time, reset to false on reflection failure). */
    private boolean isFolia;

    // Cached Folia reflection methods (null on non-Folia servers)
    private Method playerGetSchedulerMethod;
    private Method entityGetSchedulerMethod;
    private Method entitySchedulerRunMethod;
    private Method entitySchedulerRunDelayedMethod;
    private Method entitySchedulerRunAtFixedRateMethod;
    private Method globalSchedulerRunMethod;
    private Method globalSchedulerRunDelayedMethod;
    private Method globalSchedulerRunAtFixedRateMethod;
    private Method regionSchedulerRunLocationMethod;
    private Method regionSchedulerRunLocationDelayedMethod;
    private Method regionSchedulerRunChunkMethod;
    private Method regionSchedulerRunChunkDelayedMethod;
    private Method asyncSchedulerRunAtFixedRateMethod;
    private Method asyncSchedulerRunNowMethod;
    private Method asyncSchedulerRunDelayedMethod;

    /**
     * The three Folia schedulers, resolved once.
     *
     * <p>They are singletons, so asking {@link Bukkit} for them reflectively on every single
     * scheduling call was half the reflection this class performs — for a value that never changes.
     * Resolved here, every {@code runX} below is left with a single {@code invoke}.</p>
     */
    private Object globalScheduler;
    private Object regionScheduler;
    private Object asyncScheduler;

    /**
     * {@code cancel()} per ScheduledTask implementation class.
     *
     * <p>Looking the method up on every cancellation meant a reflective lookup plus a
     * {@code setAccessible} call each time a GUI closed or a blink task stopped.</p>
     */
    private final java.util.Map<Class<?>, Method> cancelMethods = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Creates the adapter XCore and its addons share, owned by XCore.
	 *
	 * @param main The main plugin instance.
	 */
    public SchedulerAdapter(XCore main) {
        this(main, main.logger());
    }

	/**
	 * Creates a new SchedulerAdapter and resolves all Folia scheduler methods via reflection
	 * if the server is running Folia.
	 *
	 * @param owner  The plugin the tasks belong to: they are cancelled when it is disabled.
	 * @param logger Where scheduling failures are reported.
	 */
    public SchedulerAdapter(Plugin owner, Logger logger) {
    	this.owner = owner;
    	this.logger = logger;
    	this.isFolia = isFoliaAvailable();

    	if (isFolia) {
            try {
                Class<?> playerClass = Player.class;
                playerGetSchedulerMethod = playerClass.getMethod("getScheduler");
                // All entities share the same #getScheduler signature on Folia/Paper 1.21+.
                entityGetSchedulerMethod = Entity.class.getMethod("getScheduler");

                Class<?> entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                entitySchedulerRunMethod = entitySchedulerClass.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                entitySchedulerRunDelayedMethod = entitySchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
                entitySchedulerRunAtFixedRateMethod = entitySchedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);

                Class<?> globalSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                globalSchedulerRunMethod = globalSchedulerClass.getMethod("run", Plugin.class, Consumer.class);
                globalSchedulerRunDelayedMethod = globalSchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                globalSchedulerRunAtFixedRateMethod = globalSchedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

                Class<?> regionSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                regionSchedulerRunLocationMethod = regionSchedulerClass.getMethod("run", Plugin.class, Location.class, Consumer.class);
                regionSchedulerRunLocationDelayedMethod = regionSchedulerClass.getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
                regionSchedulerRunChunkMethod = regionSchedulerClass.getMethod("run", Plugin.class, World.class, int.class, int.class, Consumer.class);
                regionSchedulerRunChunkDelayedMethod = regionSchedulerClass.getMethod("runDelayed", Plugin.class, World.class, int.class, int.class, Consumer.class, long.class);

                Class<?> asyncSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
                asyncSchedulerRunNowMethod = asyncSchedulerClass.getMethod("runNow", Plugin.class, Consumer.class);
                asyncSchedulerRunAtFixedRateMethod = asyncSchedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                asyncSchedulerRunDelayedMethod = asyncSchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);

                // The schedulers themselves are singletons: fetched once here instead of on every call.
                globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                regionScheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
                asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);

            } catch (Exception e) {
                logger.sendWarning("Failed to initialize Folia scheduler, falling back to Bukkit : " + e.getMessage());
                isFolia = false;
            }
        }
	}

	/**
	 * Checks whether the Folia regionized server class is present on the classpath.
	 *
	 * @return {@code true} if running on Folia, {@code false} otherwise.
	 */
    private boolean isFoliaAvailable() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

	/**
	 * Wraps a {@link Runnable} into a Folia-compatible {@code Consumer<ScheduledTask>}
	 * using a JDK dynamic proxy. Only the {@code accept} method is implemented;
	 * all other Consumer methods throw {@link UnsupportedOperationException}.
	 *
	 * @param task The runnable to wrap.
	 * @return A proxied Consumer that executes the runnable on {@code accept()}.
	 */
    private Consumer<Object> wrapConsumer(Runnable task) {
        // Folia's schedulers take a Consumer<ScheduledTask>. ScheduledTask is not on the compile
        // classpath, but Consumer is — and generics are erased, so a plain Consumer<Object> is
        // accepted by the reflective call. This used to allocate a JDK dynamic Proxy per task and
        // dispatch every accept() reflectively; a single per-chunk sweep schedules thousands.
        return ignored -> task.run();
    }

	/**
	 * Runs a task on the entity's owning thread (Folia) or the main thread (Bukkit).
	 *
	 * @param player The player whose region thread should execute the task.
	 * @param task   The task to run.
	 */
	public void runEntityTask(Player player, Runnable task) {
        if (isFolia) {
            try {
                Object scheduler = playerGetSchedulerMethod.invoke(player);
                entitySchedulerRunMethod.invoke(scheduler, owner, wrapConsumer(task), null);
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia entity task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(owner, task);
        }
    }

	/**
	 * Runs a task on the player's owning thread, with a callback for when it can no longer run.
	 *
	 * <p>Folia drops an entity task silently when the entity is removed first (a player who logs
	 * out in between): whatever the task was meant to hand over is then lost. {@code retired} runs
	 * instead in that case — and also when the task could not be scheduled at all — so the caller
	 * can put things back. Exactly one of the two runs.</p>
	 *
	 * @param player  The player whose region thread should execute the task.
	 * @param task    The task to run.
	 * @param retired Run instead of {@code task} when the player is gone (may be {@code null}).
	 * @return {@code false} when the task was not scheduled ({@code retired} has then already run).
	 */
	public boolean runEntityTask(Player player, Runnable task, Runnable retired) {
        if (retired != null) {
            // Exactly one of the two, even when the server drops the task (the owner stopping
            // first): the fallback of a task not run yet is run by runPendingFallbacks.
            Pending entry = new Pending(retired);
            pending.add(entry);
            Runnable onlyTask = task;
            task = () -> {
                if (!entry.done.compareAndSet(false, true)) return;
                pending.remove(entry);
                onlyTask.run();
            };
            retired = () -> {
                if (!entry.done.compareAndSet(false, true)) return;
                pending.remove(entry);
                entry.retired.run();
            };
        }
        if (isFolia) {
            try {
                Object scheduler = playerGetSchedulerMethod.invoke(player);
                Object handle = entitySchedulerRunMethod.invoke(scheduler, owner, wrapConsumer(task), retired);
                if (handle == null) {
                    // Already retired: Folia neither runs the task nor calls the callback.
                    if (retired != null) retired.run();
                    return false;
                }
                return true;
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia entity task : " + e.getMessage());
                if (retired != null) retired.run();
                return false;
            }
        }
        if (!player.isOnline()) {
            if (retired != null) retired.run();
            return false;
        }
        try {
            Runnable run = task;
            Runnable fallback = retired;
            Bukkit.getScheduler().runTask(owner, () -> {
                if (player.isOnline()) run.run();
                else if (fallback != null) fallback.run();
            });
            return true;
        } catch (org.bukkit.plugin.IllegalPluginAccessException | IllegalStateException stopping) {
            // The owner is being disabled: nothing can be scheduled for it any more, as on Folia.
            // The callback is what puts things back (an item already paid for, to the delivery).
            if (retired != null) retired.run();
            return false;
        }
    }

    /** A fallback waiting on its task: the first of the two to claim it runs. */
    private static final class Pending {
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable retired;

        Pending(Runnable retired) {
            this.retired = retired;
        }
    }

    /** The fallbacks of entity tasks taken but not run yet. */
    private final java.util.Set<Pending> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Runs the fallback of every entity task taken and not run yet. Called when the owner stops:
     * the server drops its tasks then, and neither the task nor its fallback would ever run, so
     * an item already paid for was neither given nor sent.
     *
     * @since 2.1
     */
    public void runPendingFallbacks() {
        for (Pending entry : new java.util.ArrayList<>(pending)) {
            if (!entry.done.compareAndSet(false, true)) continue;
            try {
                entry.retired.run();
            } catch (Throwable t) {
                logger.sendError("A task's fallback failed while stopping: " + t);
            }
        }
        pending.clear();
    }

	/**
	 * Runs a task on the entity's owning thread after a delay.
	 *
	 * @param player     The player whose region thread should execute the task.
	 * @param task       The task to run.
	 * @param delayTicks The delay in server ticks before execution.
	 */
	public void runEntityTaskLater(Player player, Runnable task, long delayTicks) {
	    if (isFolia) {
	        try {
	            Object scheduler = playerGetSchedulerMethod.invoke(player);
	            entitySchedulerRunDelayedMethod.invoke(scheduler, owner, wrapConsumer(task), null, delayTicks);
	        } catch (Exception e) {
	            logger.sendError("Failed to schedule delayed entity task: " + e.getMessage());
	        }
	    } else {
	        Bukkit.getScheduler().runTaskLater(owner, task, delayTicks);
	    }
	}

	/**
	 * Runs a task on the owning region thread of any {@link Entity}.
	 * Works for armor stands, item displays, non-player entities too — the
	 * Folia entity scheduler signature is shared across all entity types.
	 *
	 * @param entity The entity whose region thread should execute the task.
	 * @param task   The task to run.
	 */
	public void runEntityTask(Entity entity, Runnable task) {
	    if (entity == null) { runGlobalTask(task); return; }
	    if (isFolia) {
	        try {
	            Object scheduler = entityGetSchedulerMethod.invoke(entity);
	            entitySchedulerRunMethod.invoke(scheduler, owner, wrapConsumer(task), null);
	        } catch (Exception e) {
	            logger.sendError("Failed to schedule Folia entity task : " + e.getMessage());
	        }
	    } else {
	        Bukkit.getScheduler().runTask(owner, task);
	    }
	}

	/**
	 * Runs a task on the owning region thread of any {@link Entity} after a delay.
	 *
	 * @param entity     The entity whose region thread should execute the task.
	 * @param task       The task to run.
	 * @param delayTicks The delay in server ticks before execution.
	 */
	public void runEntityTaskLater(Entity entity, Runnable task, long delayTicks) {
	    // No entity to follow: a tick thread all the same, since that is what the task was written for.
	    if (entity == null) { runGlobalTaskLater(task, delayTicks); return; }
	    if (isFolia) {
	        try {
	            Object scheduler = entityGetSchedulerMethod.invoke(entity);
	            entitySchedulerRunDelayedMethod.invoke(scheduler, owner, wrapConsumer(task), null, delayTicks);
	        } catch (Exception e) {
	            logger.sendError("Failed to schedule delayed entity task : " + e.getMessage());
	        }
	    } else {
	        Bukkit.getScheduler().runTaskLater(owner, task, delayTicks);
	    }
	}

	/**
	 * Runs a repeating task on an entity's owning region thread.
	 *
	 * <p>The entity variant of {@link #runGlobalTaskTimer(Runnable, long, long)}: on Folia the task
	 * follows the entity from region to region, and stops on its own when the entity is removed.
	 * Without it, a per-entity loop had to be driven from the global thread and hop to the entity's
	 * region on every tick.</p>
	 *
	 * @param entity      The entity whose region thread should execute the task.
	 * @param task        The task to run.
	 * @param startTicks  The initial delay in ticks.
	 * @param periodTicks The period in ticks between executions.
	 * @return The task handle, or {@code null} on error.
	 */
	public Object runEntityTaskTimer(Entity entity, Runnable task, long startTicks, long periodTicks) {
	    if (entity == null) return runGlobalTaskTimer(task, startTicks, periodTicks);
	    if (isFolia) {
	        try {
	            Object scheduler = entityGetSchedulerMethod.invoke(entity);
	            return entitySchedulerRunAtFixedRateMethod.invoke(scheduler, owner, wrapConsumer(task), null,
	                    Math.max(1L, startTicks), Math.max(1L, periodTicks));
	        } catch (Exception e) {
	            logger.sendError("Failed to schedule Folia entity task timer : " + e.getMessage());
	            return null;
	        }
	    }
	    return Bukkit.getScheduler().runTaskTimer(owner, task, startTicks, periodTicks);
	}

	/**
	 * Teleports a player, loading the destination chunk off the main thread.
	 *
	 * <p>{@code Player#teleportAsync} is Paper API since 1.13 and works on Folia too, so there is no
	 * need for a separate branch here. The previous non-Folia path used a plain {@code teleport()},
	 * which loads the chunk synchronously and stalls the server, and it always reported success.</p>
	 *
	 * @param player   The player to teleport.
	 * @param location The target location.
	 * @return A future completing with {@code true} when the player was moved.
	 */
	public CompletableFuture<Boolean> teleportAsync(Player player, Location location) {
	    CompletableFuture<Boolean> future = new CompletableFuture<>();
	    try {
	        player.teleportAsync(location)
	              .thenAccept(result -> future.complete(Boolean.TRUE.equals(result)))
	              .exceptionally(ex -> {
	                  logger.sendError("Asynchronous teleport failed : " + ex.getMessage());
	                  future.complete(false);
	                  return null;
	              });
	    } catch (Throwable t) {
	        // A fork without the method: fall back to the classic path rather than not teleporting.
	        logger.sendDebug("teleportAsync unavailable (" + t + "), falling back to a scheduled teleport.");
	        runLocationTask(() -> future.complete(player.teleport(location)), location);
	    }
	    return future;
	}

	/**
	 * Runs a task on the global region thread (Folia) or the main thread (Bukkit).
	 *
	 * @param task The task to run.
	 */
    /**
     * Whether the current thread is the one global tasks run on: the main thread on Paper, the
     * global region thread on Folia. Code that must run there can then run at once instead of a
     * tick later, which matters to a caller waiting on that very thread.
     *
     * @return {@code true} on that thread.
     * @since 2.1
     */
    public boolean isGlobalThread() {
        if (!isFolia) return Bukkit.isPrimaryThread();
        try {
            return (Boolean) Bukkit.class.getMethod("isGlobalTickThread").invoke(null);
        } catch (Throwable unknown) {
            return false;
        }
    }

    /**
     * {@link #runGlobalTask(Runnable)}, saying whether the task was taken: {@code false} when the
     * server refused it (the owner is being disabled, the server is stopping), and the task will
     * never run.
     *
     * @param task The task.
     * @return Whether it was scheduled.
     * @since 2.1
     */
    public boolean tryRunGlobalTask(Runnable task) {
        try {
            if (isFolia) {
                globalSchedulerRunMethod.invoke(globalScheduler, owner, wrapConsumer(task));
            } else {
                Bukkit.getScheduler().runTask(owner, task);
            }
            return true;
        } catch (Throwable refused) {
            return false;
        }
    }

    public void runGlobalTask(Runnable task) {
        if (isFolia) {
            try {
                globalSchedulerRunMethod.invoke(globalScheduler, owner, wrapConsumer(task));
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia global task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(owner, task);
        }
    }

    /**
     * Runs a task on the global region thread (Folia) or the main thread (Bukkit) after a delay.
     *
     * <p>The missing counterpart of {@link #runGlobalTask(Runnable)}: without it, callers needing a
     * delayed main-thread task had to write {@code runAsyncTaskLater(() -> runGlobalTask(...))},
     * which costs two scheduler dispatches and a thread hop for every single delay.</p>
     *
     * @param task       The task to run.
     * @param delayTicks The delay in server ticks before execution.
     * @return The task handle, or {@code null} on error.
     */
    public Object runGlobalTaskLater(Runnable task, long delayTicks) {
        if (isFolia) {
            try {
                return globalSchedulerRunDelayedMethod.invoke(globalScheduler, owner, wrapConsumer(task), Math.max(1L, delayTicks));
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia delayed global task : " + e.getMessage());
                return null;
            }
        }
        return Bukkit.getScheduler().runTaskLater(owner, task, Math.max(1L, delayTicks));
    }

	/**
	 * Runs a repeating task on the global region thread (Folia) or the main thread (Bukkit).
	 *
	 * @param task        The task to run on each tick.
	 * @param startTicks  The initial delay in ticks before the first execution.
	 * @param periodTicks The period in ticks between executions.
	 * @return The task handle (BukkitTask or Folia ScheduledTask), or {@code null} on error.
	 */
    public Object runGlobalTaskTimer(Runnable task, long startTicks, long periodTicks) {
        if (isFolia) {
            try {
                return globalSchedulerRunAtFixedRateMethod.invoke(globalScheduler, owner, wrapConsumer(task), Math.max(1, startTicks), Math.max(1, periodTicks));
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia global task timer : " + e.getMessage());
                return null;
            }
        } else {
            return Bukkit.getScheduler().runTaskTimer(owner, task, startTicks, periodTicks);
        }
    }

	/**
	 * Runs a task on the region thread that owns the given location.
	 *
	 * @param task     The task to run.
	 * @param location The location determining the owning region.
	 */
    public void runLocationTask(Runnable task, Location location) {
        if (isFolia) {
            try {
                regionSchedulerRunLocationMethod.invoke(regionScheduler, owner, location, wrapConsumer(task));
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia located task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(owner, task);
        }
    }

	/**
	 * Runs a task on the region thread that owns the given location, after a delay.
	 *
	 * @param task       The task to run.
	 * @param location   The location determining the owning region.
	 * @param delayTicks The delay in ticks before execution.
	 */
    public void runLocationTaskLater(Runnable task, Location location, long delayTicks) {
        if (isFolia) {
            try {
                regionSchedulerRunLocationDelayedMethod.invoke(regionScheduler, owner, location, wrapConsumer(task), delayTicks);
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia located task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTaskLater(owner, task, Math.max(1L, delayTicks));
        }
    }

	/**
	 * Runs a task on the region thread that owns the given chunk.
	 *
	 * @param task   The task to run.
	 * @param world  The world containing the chunk.
	 * @param chunkX The chunk X coordinate.
	 * @param chunkZ The chunk Z coordinate.
	 */
    public void runChunkTask(Runnable task, World world, int chunkX, int chunkZ) {
        if (isFolia) {
            try {
                regionSchedulerRunChunkMethod.invoke(regionScheduler, owner, world, chunkX, chunkZ, wrapConsumer(task));
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia chunk task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(owner, task);
        }
    }

	/**
	 * Runs a task on the region thread that owns the given chunk, after a delay.
	 *
	 * @param task       The task to run.
	 * @param world      The world containing the chunk.
	 * @param chunkX     The chunk X coordinate.
	 * @param chunkZ     The chunk Z coordinate.
	 * @param delayTicks The delay in ticks before execution.
	 */
    public void runChunkTaskLater(Runnable task, World world, int chunkX, int chunkZ, long delayTicks) {
        if (isFolia) {
            try {
                regionSchedulerRunChunkDelayedMethod.invoke(regionScheduler, owner, world, chunkX, chunkZ, wrapConsumer(task), delayTicks);
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia chunk task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTaskLater(owner, task, Math.max(1L, delayTicks));
        }
    }

	/**
	 * Runs a repeating asynchronous task.
	 * On Folia, tick values are converted to milliseconds (1 tick = 50ms).
	 *
	 * @param task        The task to run.
	 * @param startTicks  The initial delay in ticks.
	 * @param periodTicks The period in ticks between executions.
	 * @return The task handle, or {@code null} on error.
	 */
    public Object runAsyncTaskTimer(Runnable task, long startTicks, long periodTicks) {
        if (isFolia) {
            try {
                long initialDelayMs = Math.max(1L, startTicks * 50L);
                long periodMs = Math.max(1L, periodTicks * 50L);
                return asyncSchedulerRunAtFixedRateMethod.invoke(asyncScheduler, owner, wrapConsumer(task), initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia async task timer: " + e);
                return null;
            }
        } else {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(owner, task, startTicks, periodTicks);
        }
    }

	/**
	 * Runs an asynchronous task after a delay.
	 * On Folia, tick values are converted to milliseconds (1 tick = 50ms).
	 *
	 * @param task       The task to run.
	 * @param delayTicks The delay in ticks before execution.
	 * @return The task handle, or {@code null} on error.
	 */
	public Object runAsyncTaskLater(Runnable task, long delayTicks) {
	    if (isFolia) {
	        try {
	            long delayMs = Math.max(1L, delayTicks * 50L);
	            return asyncSchedulerRunDelayedMethod.invoke(asyncScheduler, owner, wrapConsumer(task), delayMs, TimeUnit.MILLISECONDS);
	        } catch (Exception e) {
	            logger.sendError("Failed to schedule delayed async task: " + e.getMessage());
	            return null;
	        }
	    } else {
	        // Asynchronously, as the name says. This called runTaskLater, so off Folia every delayed
	        // "async" task ran on the server thread: its queries were made there, and one that waited
	        // on a task the server thread itself had to run froze the server for good.
	        return Bukkit.getScheduler().runTaskLaterAsynchronously(owner, task, delayTicks);
	    }
	}

	/**
	 * Cancels a previously scheduled task.
	 * Supports both {@link org.bukkit.scheduler.BukkitTask} and Folia's ScheduledTask.
	 *
	 * @param taskHandle The task handle returned by a scheduling method, or {@code null} (no-op).
	 */
    public void cancelTask(Object taskHandle) {
        if (taskHandle == null) return;

        if (taskHandle instanceof org.bukkit.scheduler.BukkitTask bukkitTask) {
            bukkitTask.cancel();
            return;
        }

        // Folia's handle, by type rather than by the spelling of its name. The interface lives in
        // paper-api, so this compiles and resolves on plain Paper too — it simply never matches
        // there. Recognising it by getSimpleName().contains("ScheduledTask") meant that the day the
        // class was renamed, cancellation would quietly become a no-op: no exception, no log, and a
        // repeating task left running for the lifetime of the server. That is the one failure mode
        // a canceller must not have.
        if (taskHandle instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduled) {
            scheduled.cancel();
            return;
        }

        // Anything else that offers cancel(): a fork with its own handle type, or a future Paper
        // one. Reflection is the fallback now, not the primary path.
        try {
            Method cancelMethod = cancelMethods.computeIfAbsent(taskHandle.getClass(), type -> {
                try {
                    Method m = type.getMethod("cancel");
                    m.setAccessible(true);
                    return m;
                } catch (Exception e) {
                    return null;
                }
            });
            if (cancelMethod != null) {
                cancelMethod.invoke(taskHandle);
            } else {
                // Saying so is the whole point: a task nobody can cancel is a leak, and silence
                // about it is how it goes unnoticed.
                logger.sendWarning("Cannot cancel a task handle of type "
                        + taskHandle.getClass().getName() + " — it offers no cancel() method.");
            }
        } catch (Exception e) {
            logger.sendError("Failed to cancel task : " + e.getMessage());
        }
    }

	/**
	 * Cancels every task the owner has scheduled, through this adapter or straight through the
	 * server: what the server does itself when it disables a plugin, for one that stays enabled
	 * after a start that did not complete. Tasks attached to an entity on Folia are not reachable
	 * this way; they go when the plugin is disabled.
	 *
	 * <p>Server classes only: it runs after a failed start, possibly with XCore's jar closed.</p>
	 */
    public void cancelAllTasks() {
        if (!isFolia) {
            try {
                Bukkit.getScheduler().cancelTasks(owner);
            } catch (Throwable ignored) {}
        }
        // Paper has these two as well, beside the Bukkit scheduler; on Folia they are the only ones.
        try {
            Bukkit.getGlobalRegionScheduler().cancelTasks(owner);
        } catch (Throwable ignored) {}
        try {
            Bukkit.getAsyncScheduler().cancelTasks(owner);
        } catch (Throwable ignored) {}
    }

	/**
	 * Runs an asynchronous task immediately.
	 *
	 * @param task The task to run.
	 * @return The task handle, or {@code null} on error.
	 */
    public Object runAsyncTask(Runnable task) {
        if (isFolia) {
            try {
                return asyncSchedulerRunNowMethod.invoke(asyncScheduler, owner, wrapConsumer(task));
            } catch (Exception e) {
                logger.sendError("Failed to schedule Folia async task : " + e);
                return null;
            }
        } else {
            return Bukkit.getScheduler().runTaskAsynchronously(owner, task);
        }
    }

	/**
	 * Loads a chunk asynchronously.
	 *
	 * @param world The world containing the chunk.
	 * @param x     The chunk X coordinate.
	 * @param z     The chunk Z coordinate.
	 * @return A future that resolves to the loaded {@link org.bukkit.Chunk}.
	 *         On Folia failure, returns a failed future.
	 */
    public CompletableFuture<org.bukkit.Chunk> getChunkAtAsync(World world, int x, int z) {
        // Paper has declared World#getChunkAtAsync in the API since 1.13, Folia included — there is
        // nothing to look up reflectively. The old non-Folia branch loaded the chunk *synchronously*,
        // which is a main-thread stall on Paper and illegal from any other thread.
        try {
            return world.getChunkAtAsync(x, z);
        } catch (Throwable t) {
            logger.sendError("Failed to load chunk asynchronously: " + t.getMessage());
            return CompletableFuture.failedFuture(t);
        }
    }

}
