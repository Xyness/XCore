package fr.xyness.XCore.Web;

/**
 * Represents a page entry in the web dashboard sidebar.
 *
 * @param name Display name (e.g., "Bans").
 * @param path URL path fragment (e.g., "bans").
 * @param icon Icon identifier (e.g., "ban").
 */
public record WebPage(String name, String path, String icon) {}
