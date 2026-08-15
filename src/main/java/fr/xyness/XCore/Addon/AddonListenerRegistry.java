package fr.xyness.XCore.Addon;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Utils.Profiler;

/**
 * Tracks Bukkit event listeners registered by each addon.
 * <p>
 * All addon listeners are registered under the {@link XCore} plugin instance
 * (since addons are not standalone Bukkit plugins). This registry keeps track
 * of which listeners belong to which addon, enabling clean unregistration
 * when an individual addon is disabled without affecting other addons or XCore itself.
 * </p>
 *
 * <pre>{@code
 * // In an addon's onEnable():
 * registerListener(new MyListener(this));
 *
 * // The AddonManager calls unregisterAll() automatically on disable.
 * }</pre>
 *
 * @see XAddon#registerListener(Listener)
 * @see AddonManager#disableAddons()
 */
public class AddonListenerRegistry {

    private final XCore core;
    private final Map<String, List<Listener>> addonListeners = new ConcurrentHashMap<>();

    /**
     * Creates a new AddonListenerRegistry.
     *
     * @param core The XCore plugin instance used to register listeners with Bukkit.
     */
    public AddonListenerRegistry(XCore core) {
        this.core = core;
    }

    /**
     * Registers a Bukkit event listener under the given addon name.
     * The listener is registered with Bukkit's plugin manager using XCore as the owning plugin,
     * and tracked internally so it can be unregistered later.
     *
     * @param addonName The name of the addon that owns this listener.
     * @param listener  The Bukkit event listener to register.
     */
    public void registerListener(String addonName, Listener listener) {
        if (!registerTimed(addonName, listener)) {
            // Anything unexpected in the reflective path and the listener still gets registered the
            // plain way: a missing measurement is a nuisance, a missing listener is a broken addon.
            Bukkit.getPluginManager().registerEvents(listener, core);
        }
        addonListeners.computeIfAbsent(addonName, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Registers every {@code @EventHandler} of a listener behind an executor that can time it.
     *
     * <p>Addons run their listeners under XCore's plugin identity, so a timings report attributes
     * everything they do to XCore and nothing to the addon that actually spent the time. Going
     * through our own executor is what makes {@code /xcore profile} able to name the culprit.</p>
     *
     * <p>The handler itself is invoked through a {@link MethodHandle} — the same mechanism Bukkit
     * uses — so the only cost added when profiling is off is one volatile read per event.</p>
     *
     * @param addonName The owning addon.
     * @param listener  The listener to register.
     * @return {@code true} when every handler was registered here.
     */
    private boolean registerTimed(String addonName, Listener listener) {
        try {
            Set<Method> methods = new LinkedHashSet<>();
            Collections.addAll(methods, listener.getClass().getMethods());
            Collections.addAll(methods, listener.getClass().getDeclaredMethods());

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            String owner = listener.getClass().getSimpleName();
            boolean any = false;

            for (Method method : methods) {
                EventHandler annotation = method.getAnnotation(EventHandler.class);
                if (annotation == null) continue;
                if (method.isBridge() || method.isSynthetic()) continue;
                if (method.getParameterCount() != 1) continue;
                Class<?> parameter = method.getParameterTypes()[0];
                if (!Event.class.isAssignableFrom(parameter)) continue;

                Class<? extends Event> eventClass = parameter.asSubclass(Event.class);
                method.setAccessible(true);
                MethodHandle handle = lookup.unreflect(method);
                String key = addonName + "/" + owner + "#" + eventClass.getSimpleName();
                Profiler profiler = core.profiler();

                EventExecutor executor = (registered, event) -> {
                    if (!eventClass.isInstance(event)) return;
                    long start = Profiler.isEnabled() ? System.nanoTime() : 0L;
                    try {
                        handle.invoke(registered, event);
                    } catch (Throwable throwable) {
                        throw new EventException(throwable);
                    } finally {
                        if (start != 0L) profiler.record(key, System.nanoTime() - start);
                    }
                };

                Bukkit.getPluginManager().registerEvent(eventClass, listener, annotation.priority(),
                        executor, core, annotation.ignoreCancelled());
                any = true;
            }
            return any;
        } catch (Throwable t) {
            core.logger().sendDebug("Falling back to plain listener registration for "
                    + listener.getClass().getSimpleName() + " : " + t.getMessage());
            return false;
        }
    }

    /**
     * Unregisters all Bukkit event listeners belonging to the given addon.
     * This is called automatically by {@link AddonManager#disableAddons()} after
     * the addon's {@link XAddon#onDisable()} method completes.
     *
     * @param addonName The name of the addon whose listeners should be unregistered.
     */
    public void unregisterAll(String addonName) {
        List<Listener> listeners = addonListeners.remove(addonName);
        if (listeners != null) {
            for (Listener l : listeners) {
                HandlerList.unregisterAll(l);
            }
        }
    }

    /**
     * Returns the number of listeners currently registered for the given addon.
     *
     * @param addonName The addon name.
     * @return The count of tracked listeners.
     */
    public int getListenerCount(String addonName) {
        List<Listener> listeners = addonListeners.get(addonName);
        return listeners != null ? listeners.size() : 0;
    }
}
