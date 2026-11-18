package fr.xyness.XCore.Web;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Reads a dashboard session owner's permissions again the moment they join.
 *
 * <p>A session goes by the permissions its owner held when last read, and they can only be read
 * while the owner is connected. Without this, a rank granted or taken away while they were away
 * would wait for the panel's minute-long round; with it, joining is enough.</p>
 */
public final class WebSessionListener implements Listener {

    private final WebPanel panel;

    WebSessionListener(WebPanel panel) {
        this.panel = panel;
    }

    /**
     * Runs on the joining player's thread, after every other listener: by then the permission
     * plugin has given them their ranks.
     *
     * @param event The join.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        panel.reread(event.getPlayer());
    }
}
