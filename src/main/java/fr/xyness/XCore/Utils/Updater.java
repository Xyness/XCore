package fr.xyness.XCore.Utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.yaml.snakeyaml.Yaml;

/**
 * Checks for addon updates by fetching a remote {@code version.yml} file.
 *
 * <p>The address comes from the addon itself ({@code update-url} in {@code addon.yml}), so an
 * addon published anywhere — another organisation, a private host, a marketplace mirror — is
 * checked where it actually lives.</p>
 */
public class Updater {


    // ***************
    // *  Variables  *
    // ***************


    private final Logger logger;
    private final String version;
    private final String url;
    private boolean is_update_available;
    private String new_version_available;
    private List<String> update_notes = new ArrayList<>();
    private String date;


    // ******************
    // *  Constructors  *
    // ******************


    /**
     * Creates an Updater pointing at an explicit address.
     *
     * @param url     Full URL of the remote {@code version.yml}.
     * @param version The current version of the addon.
     * @param logger  The logger to use for error messages.
     */
    public Updater(String url, String version, Logger logger) {
        this.logger = logger;
        this.version = version;
        this.url = url;
    }


    /** @return The address this updater reads. */
    public String getUrl() { return url; }


    // *************
    // *  Methods  *
    // *************


    /**
     * Checks if an update is available (synchronous).
     *
     * @return True if an update is available, false otherwise.
     */
    @SuppressWarnings("unchecked")
    public boolean checkForUpdates() {
        try {
            URI uri = URI.create(url);
            URL u = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) u.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; XCore/2.0)");
            connection.setRequestMethod("GET");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(reader);
                String response = data.get("version").toString();
                // Only a *newer* remote version is an update. Comparing for inequality announced
                // one whenever the numbers differed — including when the installed build was
                // ahead of what is published, which is the normal state while developing.
                boolean final_answer = isNewerThanInstalled(response);
                if (final_answer) {
                    this.new_version_available = response;
                    Object notes = data.get("update-notes");
                    if (notes instanceof List<?>) {
                        this.update_notes = (List<String>) (List<?>) notes;
                    }
                    Object d = data.get("date");
                    if (d != null) this.date = d.toString();
                }
                this.is_update_available = final_answer;
                return final_answer;
            } catch (Exception e) {
                logger.sendError("Error when trying to parse version: " + e.getMessage());
                return false;
            }
        } catch (Exception e) {
            logger.sendError("Error when checking for updates: " + e.getMessage());
            return false;
        }
    }

    /**
     * Whether {@code remote} is a later version than the one installed.
     *
     * <p>Compares the numeric segments in order, so 1.0.17 is correctly newer than 1.0.9. A
     * segment that is not a number (a {@code -beta} suffix, say) counts as 0, and a version with
     * more segments wins a tie: 1.2.1 beats 1.2.</p>
     *
     * @param remote The version published at the update URL.
     * @return {@code true} when the server should be told to update.
     */
    private boolean isNewerThanInstalled(String remote) {
        if (remote == null || remote.isBlank()) return false;
        if (version == null || version.isBlank()) return true;

        String[] mine = version.trim().split("[^0-9]+");
        String[] theirs = remote.trim().split("[^0-9]+");
        for (int i = 0; i < Math.max(mine.length, theirs.length); i++) {
            int a = segment(mine, i);
            int b = segment(theirs, i);
            if (b != a) return b > a;
        }
        return false;
    }

    /** Reads one numeric segment, treating anything missing or unparsable as 0. */
    private static int segment(String[] parts, int index) {
        if (index >= parts.length || parts[index].isEmpty()) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Checks if an update is available (asynchronous).
     *
     * @return A CompletableFuture resolving to true if an update is available.
     */
    public CompletableFuture<Boolean> checkForUpdatesAsync() {
        return CompletableFuture.supplyAsync(this::checkForUpdates);
    }

    /**
     * Gets whether an update is available (last check result).
     *
     * @return True if an update is available.
     */
    public boolean isUpdateAvailable() {
        return is_update_available;
    }

    /**
     * Gets the new version string.
     *
     * @return The new version available, or null if up to date.
     */
    public String getNewVersionAvailable() {
        return new_version_available;
    }

    /**
     * Gets the update notes.
     *
     * @return The list of update notes.
     */
    public List<String> getUpdateNotes() {
        return update_notes;
    }

    /**
     * Gets the update date.
     *
     * @return The update date string.
     */
    public String getDate() {
        return date;
    }
}
