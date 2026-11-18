package fr.xyness.XCore.Listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;

import net.kyori.adventure.text.Component;

/**
 * Refuses every connection until XLogin is up, on a server that has XLogin.
 *
 * <p>XCore registers it at its own start whenever XLogin is expected — its settings are there, or a
 * jar of it is — and removes it the moment XLogin, the 2.0 plugin or the 1.x addon, has started. An
 * XLogin that never comes up leaves it in place: its 2.0 jar not installed yet, refused by the
 * server, put in the wrong folder, or failing its own start. XLogin closes the door itself when its
 * start fails; this covers the cases where nothing of XLogin runs to do it, under the same
 * {@code security.refuse-connections-on-failure} setting.</p>
 */
public class LoginLockdownListener implements Listener {

    private final Component message;

    /**
     * @param message What a refused player reads.
     */
    public LoginLockdownListener(Component message) {
        this.message = message;
    }

    /** First, so that nothing else spends time on a connection that will not come in. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLoginFirst(AsyncPlayerPreLoginEvent event) {
        event.disallow(Result.KICK_OTHER, message);
    }

    /** And last, in case something in between allowed it again. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLoginLast(AsyncPlayerPreLoginEvent event) {
        event.disallow(Result.KICK_OTHER, message);
    }
}
