package fr.xyness.XCore.Utils;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

	/** Reference to the main plugin instance. */
    private final XCore main;

	/** Whether the server is running Folia (detected at construction time, reset to false on reflection failure). */
    private boolean isFolia;

    // Cached Folia reflection methods (null on non-Folia servers)
    private Method playerTeleportAsyncMethod;
    private Method playerGetSchedulerMethod;
    private Method entityGetSchedulerMethod;
    private Method entitySchedulerRunMethod;
    private Method entitySchedulerRunDelayedMethod;
    private Method bukkitGetGlobalRegionSchedulerMethod;
    private Method globalSchedulerRunMethod;
    private Method globalSchedulerRunAtFixedRateMethod;
    private Method bukkitGetRegionSchedulerMethod;
    private Method regionSchedulerRunLocationMethod;
    private Method regionSchedulerRunLocationDelayedMethod;
    private Method regionSchedulerRunChunkMethod;
    private Method regionSchedulerRunChunkDelayedMethod;
    private Method bukkitGetAsyncSchedulerMethod;
    private Method asyncSchedulerRunAtFixedRateMethod;
    private Method asyncSchedulerRunNowMethod;
    private Method asyncSchedulerRunDelayedMethod;

	/**
	 * Creates a new SchedulerAdapter and resolves all Folia scheduler methods via reflection
	 * if the server is running Folia.
	 *
	 * @param main The main plugin instance.
	 */
    public SchedulerAdapter(XCore main) {
    	this.main = main;
    	this.isFolia = isFoliaAvailable();

    	if (isFolia) {
            try {
                Class<?> playerClass = Player.class;
                playerTeleportAsyncMethod = playerClass.getMethod("teleportAsync", Location.class);
                playerGetSchedulerMethod = playerClass.getMethod("getScheduler");
                // All entities share the same #getScheduler signature on Folia/Paper 1.21+.
                entityGetSchedulerMethod = Entity.class.getMethod("getScheduler");

                Class<?> entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                entitySchedulerRunMethod = entitySchedulerClass.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                entitySchedulerRunDelayedMethod = entitySchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);

                bukkitGetGlobalRegionSchedulerMethod = Bukkit.class.getMethod("getGlobalRegionScheduler");
                Class<?> globalSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                globalSchedulerRunMethod = globalSchedulerClass.getMethod("run", Plugin.class, Consumer.class);
                globalSchedulerRunAtFixedRateMethod = globalSchedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

                bukkitGetRegionSchedulerMethod = Bukkit.class.getMethod("getRegionScheduler");
                Class<?> regionSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                regionSchedulerRunLocationMethod = regionSchedulerClass.getMethod("run", Plugin.class, Location.class, Consumer.class);
                regionSchedulerRunLocationDelayedMethod = regionSchedulerClass.getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
                regionSchedulerRunChunkMethod = regionSchedulerClass.getMethod("run", Plugin.class, World.class, int.class, int.class, Consumer.class);
                regionSchedulerRunChunkDelayedMethod = regionSchedulerClass.getMethod("runDelayed", Plugin.class, World.class, int.class, int.class, Consumer.class, long.class);

                bukkitGetAsyncSchedulerMethod = Bukkit.class.getMethod("getAsyncScheduler");
                Class<?> asyncSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
                asyncSchedulerRunNowMethod = asyncSchedulerClass.getMethod("runNow", Plugin.class, Consumer.class);
                asyncSchedulerRunAtFixedRateMethod = asyncSchedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                asyncSchedulerRunDelayedMethod = asyncSchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);

            } catch (Exception e) {
                main.logger().sendWarning("Failed to initialize Folia scheduler, falling back to Bukkit : " + e.getMessage());
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
        return (Consumer<Object>) Proxy.newProxyInstance(
            Consumer.class.getClassLoader(),
            new Class<?>[]{ Consumer.class },
            (proxy, method, args) -> {
                if ("accept".equals(method.getName())) {
                    task.run();
                    return null;
                }
                throw new UnsupportedOperationException("Unsupported method: " + method.getName());
            });
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
                entitySchedulerRunMethod.invoke(scheduler, main, wrapConsumer(task), null);
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia entity task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(main, task);
        }
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
	            entitySchedulerRunDelayedMethod.invoke(scheduler, main, wrapConsumer(task), null, delayTicks);
	        } catch (Exception e) {
	            main.logger().sendError("Failed to schedule delayed entity task: " + e.getMessage());
	        }
	    } else {
	        Bukkit.getScheduler().runTaskLater(main, task, delayTicks);
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
	            entitySchedulerRunMethod.invoke(scheduler, main, wrapConsumer(task), null);
	        } catch (Exception e) {
	            main.logger().sendError("Failed to schedule Folia entity task : " + e.getMessage());
	        }
	    } else {
	        Bukkit.getScheduler().runTask(main, task);
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
	    if (entity == null) { runAsyncTaskLater(task, delayTicks); return; }
	    if (isFolia) {
	        try {
	            Object scheduler = entityGetSchedulerMethod.invoke(entity);
	            entitySchedulerRunDelayedMethod.invoke(scheduler, main, wrapConsumer(task), null, delayTicks);
	        } catch (Exception e) {
	            main.logger().sendError("Failed to schedule delayed entity task : " + e.getMessage());
	        }
	    } else {
	        Bukkit.getScheduler().runTaskLater(main, task, delayTicks);
	    }
	}

	/**
	 * Teleports a player asynchronously.
	 * On Folia, uses the native async teleport API. On Bukkit, teleports synchronously
	 * via the global scheduler.
	 *
	 * @param player   The player to teleport.
	 * @param location The target location.
	 * @return A future that completes with {@code true} on success.
	 */
	public CompletableFuture<Boolean> teleportAsync(Player player, Location location) {
	    CompletableFuture<Boolean> future = new CompletableFuture<>();

	    if (isFolia) {
	        try {
	            Object teleportResult = playerTeleportAsyncMethod.invoke(player, location);
	            if (teleportResult instanceof CompletableFuture<?> cf) {
	                cf.thenAccept(result -> future.complete((Boolean) result))
	                  .exceptionally(ex -> {
	                      main.logger().sendError("Folia teleportAsync failed: " + ex.getMessage());
	                      future.complete(false);
	                      return null;
	                  });
	            } else {
	                future.complete(false);
	            }
	        } catch (Exception e) {
	            main.logger().sendError("Failed to use Folia teleportAsync: " + e.getMessage());
	            future.complete(false);
	        }
	    } else {
	    	runGlobalTask(() -> player.teleport(location));
	        future.complete(true);
	    }

	    return future;
	}

	/**
	 * Runs a task on the global region thread (Folia) or the main thread (Bukkit).
	 *
	 * @param task The task to run.
	 */
    public void runGlobalTask(Runnable task) {
        if (isFolia) {
            try {
                Object scheduler = bukkitGetGlobalRegionSchedulerMethod.invoke(null);
                globalSchedulerRunMethod.invoke(scheduler, main, wrapConsumer(task));
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia global task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(main, task);
        }
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
                Object scheduler = bukkitGetGlobalRegionSchedulerMethod.invoke(null);
                return globalSchedulerRunAtFixedRateMethod.invoke(scheduler, main, wrapConsumer(task), Math.max(1, startTicks), Math.max(1, periodTicks));
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia global task timer : " + e.getMessage());
                return null;
            }
        } else {
            return Bukkit.getScheduler().runTaskTimer(main, task, startTicks, periodTicks);
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
                Object scheduler = bukkitGetRegionSchedulerMethod.invoke(null);
                regionSchedulerRunLocationMethod.invoke(scheduler, main, location, wrapConsumer(task));
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia located task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(main, task);
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
                Object scheduler = bukkitGetRegionSchedulerMethod.invoke(null);
                regionSchedulerRunLocationDelayedMethod.invoke(scheduler, main, location, wrapConsumer(task), delayTicks);
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia located task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(main, task);
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
                Object scheduler = bukkitGetRegionSchedulerMethod.invoke(null);
                regionSchedulerRunChunkMethod.invoke(scheduler, main, world, chunkX, chunkZ, wrapConsumer(task));
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia chunk task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(main, task);
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
                Object scheduler = bukkitGetRegionSchedulerMethod.invoke(null);
                regionSchedulerRunChunkDelayedMethod.invoke(scheduler, main, world, chunkX, chunkZ, wrapConsumer(task), delayTicks);
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia chunk task : " + e.getMessage());
            }
        } else {
            Bukkit.getScheduler().runTask(main, task);
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
                Object scheduler = bukkitGetAsyncSchedulerMethod.invoke(null);
                long initialDelayMs = Math.max(1L, startTicks * 50L);
                long periodMs = Math.max(1L, periodTicks * 50L);
                return asyncSchedulerRunAtFixedRateMethod.invoke(scheduler, main, wrapConsumer(task), initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia async task timer: " + e);
                return null;
            }
        } else {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(main, task, startTicks, periodTicks);
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
	        	Object scheduler = bukkitGetAsyncSchedulerMethod.invoke(null);
	            long delayMs = Math.max(1L, delayTicks * 50L);
	            return asyncSchedulerRunDelayedMethod.invoke(scheduler, main, wrapConsumer(task), delayMs, TimeUnit.MILLISECONDS);
	        } catch (Exception e) {
	            main.logger().sendError("Failed to schedule delayed async task: " + e.getMessage());
	            return null;
	        }
	    } else {
	        return Bukkit.getScheduler().runTaskLater(main, task, delayTicks);
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

        if (taskHandle.getClass().getSimpleName().contains("ScheduledTask")) {
            try {
                Method cancelMethod = taskHandle.getClass().getMethod("cancel");
                cancelMethod.setAccessible(true);
                cancelMethod.invoke(taskHandle);
            } catch (Exception e) {
                main.logger().sendError("Failed to cancel ScheduledTask : " + e.getMessage());
            }
        }
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
                Object scheduler = bukkitGetAsyncSchedulerMethod.invoke(null);
                return asyncSchedulerRunNowMethod.invoke(scheduler, main, wrapConsumer(task));
            } catch (Exception e) {
                main.logger().sendError("Failed to schedule Folia async task : " + e);
                return null;
            }
        } else {
            return Bukkit.getScheduler().runTaskAsynchronously(main, task);
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
        if (isFolia) {
            try {
                Method getChunkAtAsyncMethod = world.getClass().getMethod("getChunkAtAsync", int.class, int.class);
                Object result = getChunkAtAsyncMethod.invoke(world, x, z);
                if (result instanceof CompletableFuture<?> cf) {
                    return (CompletableFuture<org.bukkit.Chunk>) cf;
                }
            } catch (Exception e) {
                main.logger().sendError("Failed to call Folia getChunkAtAsync: " + e.getMessage());
            }
            return CompletableFuture.failedFuture(new RuntimeException("Folia getChunkAtAsync failed"));
        }
        return CompletableFuture.completedFuture(world.getChunkAt(x, z));
    }

}
