package fr.xyness.XCore.Addon;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import fr.xyness.XCore.XCore;

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
        Bukkit.getPluginManager().registerEvents(listener, core);
        addonListeners.computeIfAbsent(addonName, k -> new CopyOnWriteArrayList<>()).add(listener);
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
