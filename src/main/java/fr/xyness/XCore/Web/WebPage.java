package fr.xyness.XCore.Web;

/**
 * A page entry in the web dashboard sidebar.
 *
 * <p>{@code name} is looked up as a language key by the browser and falls back to itself, so a
 * module can pass either a translation key ({@code "xbans-bans"}) or plain text ({@code "Bans"}).</p>
 *
 * @param name Display name, or a language key.
 * @param path URL path fragment (e.g. {@code "bans"}).
 * @param icon Icon identifier (e.g. {@code "ban"}).
 * @param spec Declarative description of the page, or {@code null} to let the dashboard fall back
 *             to its generic renderer.
 */
public record WebPage(String name, String path, String icon, WebPageSpec spec) {

    /** A page with no declarative description; the dashboard renders it generically. */
    public WebPage(String name, String path, String icon) {
        this(name, path, icon, null);
    }
}
