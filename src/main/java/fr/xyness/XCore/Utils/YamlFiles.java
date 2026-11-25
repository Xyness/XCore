package fr.xyness.XCore.Utils;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Tells whether a YAML file can be read, before anything is merged into it.
 *
 * <h2>Why it exists</h2>
 * {@code YamlConfiguration.loadConfiguration} answers a file it cannot parse with an empty
 * configuration. Every pass that keeps a file up to date then sees all of its keys as missing,
 * writes the defaults and saves — over the file. One tab in front of a list entry cost an
 * administrator the whole of their {@code config.yml}, and a translation went the same way. The
 * rule is now the same everywhere: a file that cannot be read is never written, and the console
 * says where the mistake is.
 */
public final class YamlFiles {

    /** Where SnakeYAML says a mark is; the last one of a message is the problem's. */
    private static final Pattern MARK = Pattern.compile("line (\\d+), column (\\d+)");

    private YamlFiles() {}

    /**
     * Checks that a file parses.
     *
     * @param file The file; one that does not exist has nothing wrong with it.
     * @return {@code null} when the file can be read, otherwise one line saying where it stops
     *         being YAML and why — plain text, not escaped for the console.
     */
    public static String problem(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            new YamlConfiguration().load(file);
            return null;
        } catch (InvalidConfigurationException e) {
            return describe(e);
        } catch (IOException e) {
            return "the file could not be opened (" + e.getMessage() + ")";
        }
    }

    /**
     * Turns a parser error into one line: {@code line 249, column 1: found character '\t(TAB)'…}.
     *
     * <p>The parser's own message runs over five lines and opens with what it was doing
     * ({@code while scanning for the next token}), which says nothing to whoever has to fix the
     * file. The position and the complaint are what they need.</p>
     *
     * @param error The parse error.
     * @return The description.
     */
    public static String describe(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        String[] lines = message.split("\n");

        String where = null;
        Matcher mark = MARK.matcher(message);
        while (mark.find()) where = "line " + mark.group(1) + ", column " + mark.group(2);

        String what = lines.length > 0 ? lines[0].trim() : "";
        for (String line : lines) {
            String text = line.trim();
            if (text.startsWith("found ") || text.startsWith("expected ") || text.startsWith("mapping values")
                    || text.startsWith("could not ") || text.startsWith("did not find ")) {
                what = text;
                break;
            }
        }
        if (what.isEmpty()) what = "not valid YAML";
        return where == null ? what : where + ": " + what;
    }

    /**
     * The same line, safe to hand to a logger that reads MiniMessage: a parser complaint such as
     * {@code expected <block end>} would otherwise be taken for a tag.
     *
     * @param problem The output of {@link #problem(File)} or {@link #describe(Throwable)}.
     * @return The text with its tags escaped.
     */
    public static String forConsole(String problem) {
        if (problem == null) return "";
        try {
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(problem);
        } catch (Throwable ignored) {
            return problem.replace("<", "\\<");
        }
    }
}
