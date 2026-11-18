package fr.xyness.XCore.Listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;

import net.kyori.adventure.text.Component;

/**
 * Refuses every connection while the server has lost its authentication to the 2.0 upgrade.
 *
 * <p>XLogin is a plugin since XCore 2.0, and XCore no longer loads its 1.x addon jar. Updating
 * XCore before putting the XLogin 2.0 jar in {@code plugins/} — the order XCore's own notes give —
 * left an offline-mode server with nothing checking passwords: whoever typed a name played under
 * it. XLogin itself closes the door when it cannot start; here it is not loaded at all, so XCore
 * does it on its behalf, under the same {@code security.refuse-connections-on-failure} setting.</p>
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
