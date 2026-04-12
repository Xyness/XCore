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
 * <p>
 * The remote file is expected at:
 * {@code https://raw.githubusercontent.com/Xyness/<addonName>/refs/heads/main/version.yml}
 * </p>
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
     * Creates an Updater for the given addon.
     *
     * @param addonName The addon name (used in the GitHub URL).
     * @param version   The current version of the addon.
     * @param logger    The logger to use for error messages.
     */
    public Updater(String addonName, String version, Logger logger) {
        this.logger = logger;
        this.version = version;
        this.url = "https://raw.githubusercontent.com/Xyness/" + addonName + "/refs/heads/main/version.yml";
    }


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
                boolean final_answer = !version.equalsIgnoreCase(response);
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
