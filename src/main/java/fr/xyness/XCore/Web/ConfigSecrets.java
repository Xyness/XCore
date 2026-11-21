package fr.xyness.XCore.Web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Keeps the secrets of a configuration file out of the dashboard's editor.
 *
 * <p>The editor shows a plugin's {@code config.yml} as text, and a few of its values are keys
 * rather than settings: XLogin's SMTP password, XBans' GeoIP key, the Discord webhooks of all four
 * plugins. Being allowed to read the file is not being allowed to log into the mail account or to
 * post in the staff channel. Those values are sent as {@value #PLACEHOLDER}, and a file saved back
 * with the placeholder where it was keeps the value stored on disk.</p>
 *
 * <p>A value is a secret by the last word of its key — {@code password}, {@code secret},
 * {@code token}, {@code api-key}, {@code webhook}… — or by what it is, a webhook address, wherever
 * it sits. A flag next to such a name is not one ({@code require-strong-password: false}), and an
 * empty value is shown as it is: it says that nothing is set.</p>
 *
 * <p>The text is rewritten line by line, so the comments and the layout stay as the administrator
 * wrote them; the result is then parsed and compared with the original. When a secret the parser
 * sees was not hidden, or the rewrite changed anything else, the file is not served at all.</p>
 */
final class ConfigSecrets {

    /** What a hidden value reads as in the editor. */
    static final String PLACEHOLDER = "<hidden>";

    /** The placeholder as it is written into the file. */
    private static final String WRITTEN = "\"" + PLACEHOLDER + "\"";

    /** A webhook address: whoever holds one posts in the channel behind it. */
    private static final Pattern WEBHOOK = Pattern.compile(
            "https?://(?:[a-z0-9-]+\\.)*(?:discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/|hooks\\.slack\\.com/)",
            Pattern.CASE_INSENSITIVE);

    /** The last word of a key that holds a secret. */
    private static final Set<String> SECRET_WORDS = Set.of(
            "password", "passwd", "pwd", "pass", "passphrase", "secret", "token", "apikey", "secretkey",
            "privatekey", "accesskey", "credential", "credentials", "webhook", "webhooks");

    /** Words that make a {@code key} a secret one when they come right before it: {@code api-key}. */
    private static final Set<String> KEY_KINDS = Set.of(
            "api", "secret", "private", "access", "license", "licence", "encryption", "signing", "client", "auth");

    /** Words that make a plain {@code url} a webhook's when one of the keys above it carries them. */
    private static final Set<String> HOOK_WORDS = Set.of("webhook", "webhooks", "discord", "slack");

    /** Stands for a flow collection ({@code [a, b]}, {@code {a: b}}) while the file is read line by line. */
    private static final Object FLOW = new Object();

    private ConfigSecrets() {}

    /**
     * The file as the editor gets it, and how many values were hidden.
     *
     * @param yaml   The text to show.
     * @param hidden How many values it shows as {@value #PLACEHOLDER}.
     */
    record Masked(String yaml, int hidden) {}

    /** A file whose secrets could not be hidden with certainty: it is not shown at all. */
    static final class Unsafe extends Exception {
        Unsafe(String message) {
            super(message);
        }
    }

    /** A file sent with a placeholder that cannot be resolved: it is not saved. */
    static final class Refused extends Exception {
        private final int line;
        private final String key;

        Refused(int line, String key) {
            super("Line " + line + ": " + key + " still reads " + PLACEHOLDER + ", and no stored value can be kept"
                    + " there: its key moved, or the placeholder was edited. Type the whole value.");
            this.line = line;
            this.key = key;
        }

        /** @return The line of the placeholder, counted from 1. */
        int line() {
            return line;
        }

        /** @return The key it stands under, dotted. */
        String key() {
            return key;
        }
    }

    // **************************************************************************
    // *                              Hiding                                    *
    // **************************************************************************

    /**
     * Hides the secrets of a file.
     *
     * @param text The file as it is on disk.
     * @return The text to show, and how many values it hides.
     * @throws Unsafe When the result could not be checked to hide every secret and change nothing else.
     */
    static Masked mask(String text) throws Unsafe {
        Lines lines = new Lines(text);
        List<Scalar> scalars = scan(lines);
        Object tree = parse(text);
        boolean parsed = tree != UNPARSED;

        List<Scalar> hidden = new ArrayList<>();
        for (Scalar scalar : scalars) {
            if (parsed ? secretInTree(tree, scalar) : secretAsWritten(lines, scalar)) hidden.add(scalar);
        }

        List<List<Object>> secrets = new ArrayList<>();
        if (parsed) collect(tree, new ArrayList<>(), secrets);
        if (hidden.isEmpty() && secrets.isEmpty()) return new Masked(text, 0);

        // From the bottom up, so the lines a value spans are counted on the file as it was.
        hidden.sort((a, b) -> Integer.compare(b.first(), a.first()));
        for (Scalar scalar : hidden) {
            String line = lines.text.get(scalar.first());
            String after = scalar.multiline() ? "" : line.substring(scalar.to());
            lines.text.set(scalar.first(), line.substring(0, scalar.from()) + WRITTEN + after);
            for (int i = scalar.end() - 1; i > scalar.first(); i--) {
                lines.text.remove(i);
                lines.breaks.remove(i);
            }
        }
        String masked = lines.join();
        if (!parsed) return new Masked(masked, hidden.size());

        // Every secret the parser sees is hidden, by its own path or by one above it...
        for (List<Object> secret : secrets) {
            boolean covered = false;
            for (Scalar scalar : hidden) {
                if (startsWith(secret, scalar.path())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) throw new Unsafe("the value of " + dotted(secret) + " looks secret and could not be hidden");
        }
        // ...and nothing but them changed.
        Object expected = parse(text);
        for (Scalar scalar : hidden) {
            if (!replaceAt(expected, scalar.path())) throw new Unsafe("could not follow " + dotted(scalar.path()));
        }
        Object shown = parse(masked);
        if (shown == UNPARSED || !java.util.Objects.equals(expected, shown)) {
            throw new Unsafe("hiding its secrets would have changed something else");
        }
        return new Masked(masked, hidden.size());
    }

    // **************************************************************************
    // *                             Restoring                                  *
    // **************************************************************************

    /**
     * Puts the stored values back where a file sent from the editor still carries the placeholder.
     *
     * @param submitted The file as the editor sent it.
     * @param current   The file as it is on disk now.
     * @return The text to write.
     * @throws Refused When a placeholder stands where no value is stored.
     */
    static String restore(String submitted, String current) throws Refused {
        if (!submitted.contains(PLACEHOLDER)) return submitted;

        Lines stored = new Lines(current);
        Map<List<Object>, Scalar> byPath = new HashMap<>();
        // The last one wins on a key written twice, as it does for the parser.
        for (Scalar scalar : scan(stored)) byPath.put(scalar.path(), scalar);

        Lines lines = new Lines(submitted);
        List<Scalar> placeholders = new ArrayList<>();
        for (Scalar scalar : scan(lines)) {
            if (PLACEHOLDER.equals(valueAsWritten(lines, scalar))) placeholders.add(scalar);
        }
        placeholders.sort((a, b) -> Integer.compare(b.first(), a.first()));

        for (Scalar placeholder : placeholders) {
            Scalar original = byPath.get(placeholder.path());
            String line = lines.text.get(placeholder.first());
            if (original == null || placeholder.multiline()) throw new Refused(placeholder.first() + 1, dotted(placeholder.path()));
            String source = stored.text.get(original.first());
            // A value over several lines is indented from its key: it only fits under a key that
            // has not moved.
            if (original.multiline() && indentOf(source) != indentOf(line)) {
                throw new Refused(placeholder.first() + 1, dotted(placeholder.path()));
            }
            String value = source.substring(original.from(), original.multiline() ? source.length() : original.to());
            // A comment after the placeholder stays, unless the value runs on below it.
            String tail = original.multiline() ? "" : line.substring(placeholder.to());
            String lineBreak = lines.breaks.get(placeholder.first());
            lines.text.set(placeholder.first(), line.substring(0, placeholder.from()) + value + tail);
            if (original.multiline()) {
                lines.breaks.set(placeholder.first(), stored.breaks.get(original.first()));
                int at = placeholder.first() + 1;
                for (int i = original.first() + 1; i < original.end(); i++, at++) {
                    lines.text.add(at, stored.text.get(i));
                    lines.breaks.add(at, i == original.end() - 1 ? lineBreak : stored.breaks.get(i));
                }
            }
        }
        String restored = lines.join();

        // A placeholder left anywhere the lines above did not reach — inside a flow list, under a
        // key written in a way this does not follow — would be written to disk as the value.
        Object tree = parse(restored);
        if (tree != UNPARSED) {
            List<Object> left = findPlaceholder(tree, new ArrayList<>());
            if (left != null) {
                int line = 1;
                for (Scalar scalar : scan(new Lines(restored))) {
                    if (scalar.path().equals(left)) {
                        line = scalar.first() + 1;
                        break;
                    }
                }
                throw new Refused(line, dotted(left));
            }
        }
        return restored;
    }

    // **************************************************************************
    // *                         What is a secret                               *
    // **************************************************************************

    /** Whether a parsed value is a secret, given where it sits. */
    private static boolean secret(List<Object> path, Object value) {
        if (value == null || value instanceof Map || value instanceof List) return false;
        String text = String.valueOf(value);
        if (text.isBlank()) return false;
        if (value instanceof String string && WEBHOOK.matcher(string.trim()).lookingAt()) return true;
        // A switch next to a secret's name is a setting about it, not it.
        if (value instanceof Boolean) return false;
        return secretName(path);
    }

    /** Whether the key a value sits under names a secret. */
    private static boolean secretName(List<Object> path) {
        int leafAt = -1;
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof String) {
                leafAt = i;
                break;
            }
        }
        if (leafAt < 0) return false;
        List<String> words = words((String) path.get(leafAt));
        if (words.isEmpty()) return false;
        String last = words.get(words.size() - 1);
        if (SECRET_WORDS.contains(last)) return true;
        if (words.size() >= 2) {
            String before = words.get(words.size() - 2);
            if (last.equals("key") && KEY_KINDS.contains(before)) return true;
            if (before.equals("webhook") && (last.equals("url") || last.equals("uri") || last.equals("address"))) return true;
        }
        // discord.routes.sanctions.url: a plain "url" is a webhook's under a key that says so.
        if (words.size() == 1 && (last.equals("url") || last.equals("uri"))) {
            for (int i = 0; i < leafAt; i++) {
                if (path.get(i) instanceof String parent) {
                    for (String word : words(parent)) if (HOOK_WORDS.contains(word)) return true;
                }
            }
        }
        return false;
    }

    /** The words of a key: {@code smtp-password}, {@code smtp_password} and {@code smtpPassword} alike. */
    private static List<String> words(String key) {
        String spaced = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String word : spaced.split("[^a-z0-9]+")) if (!word.isEmpty()) out.add(word);
        return out;
    }

    /** Whether a value found in the text is a secret, by what the parser read there. */
    private static boolean secretInTree(Object tree, Scalar scalar) {
        Object node = at(tree, scalar.path());
        if (node == MISSING) return false;
        if (node instanceof Map || node instanceof List) {
            List<List<Object>> inside = new ArrayList<>();
            collect(node, new ArrayList<>(scalar.path()), inside);
            return !inside.isEmpty();
        }
        return secret(scalar.path(), node);
    }

    /** Whether a value is a secret as written, for a file the parser refuses. */
    private static boolean secretAsWritten(Lines lines, Scalar scalar) {
        Object value = scalar.value();
        if (value == FLOW) {
            StringBuilder raw = new StringBuilder();
            for (int i = scalar.first(); i < scalar.end(); i++) raw.append(lines.text.get(i)).append('\n');
            return secretName(scalar.path()) || WEBHOOK.matcher(raw).find();
        }
        return secret(scalar.path(), value);
    }

    /** Every secret of a parsed file, by path. */
    private static void collect(Object node, List<Object> path, List<List<Object>> into) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collect(entry.getValue(), with(path, String.valueOf(entry.getKey())), into);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collect(list.get(i), with(path, i), into);
        } else if (secret(path, node)) {
            into.add(path);
        }
    }

    /** The path of a placeholder left in a parsed file, or {@code null}. */
    private static List<Object> findPlaceholder(Object node, List<Object> path) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                List<Object> found = findPlaceholder(entry.getValue(), with(path, String.valueOf(entry.getKey())));
                if (found != null) return found;
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                List<Object> found = findPlaceholder(list.get(i), with(path, i));
                if (found != null) return found;
            }
        } else if (node instanceof String string && string.contains(PLACEHOLDER)) {
            return path;
        }
        return null;
    }

    // **************************************************************************
    // *                          The parsed file                               *
    // **************************************************************************

    /** Stands for a file the parser refuses. */
    private static final Object UNPARSED = new Object();

    /** Stands for a path the parsed file does not have. */
    private static final Object MISSING = new Object();

    /** The file as the parser reads it, or {@link #UNPARSED}. */
    private static Object parse(String text) {
        try {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (Exception e) {
            return UNPARSED;
        }
    }

    /** What a parsed file holds at a path, or {@link #MISSING}. */
    private static Object at(Object tree, List<Object> path) {
        Object node = tree;
        for (Object step : path) {
            if (step instanceof String key && node instanceof Map<?, ?> map) {
                Object next = MISSING;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (String.valueOf(entry.getKey()).equals(key)) next = entry.getValue();
                }
                if (next == MISSING) return MISSING;
                node = next;
            } else if (step instanceof Integer index && node instanceof List<?> list) {
                if (index < 0 || index >= list.size()) return MISSING;
                node = list.get(index);
            } else {
                return MISSING;
            }
        }
        return node;
    }

    /** Puts the placeholder at a path of a parsed file, as the rewritten text has it. */
    @SuppressWarnings("unchecked")
    private static boolean replaceAt(Object tree, List<Object> path) {
        if (path.isEmpty()) return false;
        Object parent = at(tree, path.subList(0, path.size() - 1));
        Object last = path.get(path.size() - 1);
        if (last instanceof String key && parent instanceof Map<?, ?> map) {
            Object found = null;
            for (Object candidate : map.keySet()) {
                if (String.valueOf(candidate).equals(key)) found = candidate;
            }
            if (found == null) return false;
            ((Map<Object, Object>) map).put(found, PLACEHOLDER);
            return true;
        }
        if (last instanceof Integer index && parent instanceof List<?> list && index >= 0 && index < list.size()) {
            ((List<Object>) list).set(index, PLACEHOLDER);
            return true;
        }
        return false;
    }

    private static List<Object> with(List<Object> path, Object step) {
        List<Object> next = new ArrayList<>(path.size() + 1);
        next.addAll(path);
        next.add(step);
        return next;
    }

    private static boolean startsWith(List<Object> path, List<Object> prefix) {
        return path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
    }

    /** A path as an administrator reads it: {@code discord.routes.sanctions.url}, {@code hooks[1]}. */
    private static String dotted(List<Object> path) {
        StringBuilder out = new StringBuilder();
        for (Object step : path) {
            if (step instanceof Integer index) out.append('[').append(index).append(']');
            else {
                if (out.length() > 0) out.append('.');
                out.append(step);
            }
        }
        return out.length() == 0 ? "?" : out.toString();
    }

    // **************************************************************************
    // *                          The file as text                              *
    // **************************************************************************

    /** A file cut into lines, each with the break that ended it, so it goes back together exactly. */
    private static final class Lines {
        final List<String> text = new ArrayList<>();
        final List<String> breaks = new ArrayList<>();

        Lines(String source) {
            int start = 0;
            while (true) {
                int newline = source.indexOf('\n', start);
                if (newline < 0) {
                    text.add(source.substring(start));
                    breaks.add("");
                    return;
                }
                int end = newline > start && source.charAt(newline - 1) == '\r' ? newline - 1 : newline;
                text.add(source.substring(start, end));
                breaks.add(source.substring(end, newline + 1));
                start = newline + 1;
            }
        }

        String join() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.size(); i++) out.append(text.get(i)).append(breaks.get(i));
            return out.toString();
        }
    }

    /**
     * A value as written.
     *
     * @param path  The keys and list positions leading to it.
     * @param first The line it starts on.
     * @param end   The line after its last one.
     * @param from  Where it starts on its first line.
     * @param to    Where it ends on its first line, before a comment; the end of the line when it
     *              runs over several.
     * @param value What it reads as, as far as telling a secret goes: a string, a boolean,
     *              {@code null}, or {@link #FLOW}.
     */
    private record Scalar(List<Object> path, int first, int end, int from, int to, Object value) {
        boolean multiline() {
            return end - first > 1;
        }
    }

    /** A key or a list entry the lines below belong to. */
    private static final class Frame {
        final int indent;
        final Object label;
        final boolean entry;
        int entries;

        Frame(int indent, Object label, boolean entry) {
            this.indent = indent;
            this.label = label;
            this.entry = entry;
        }
    }

    /**
     * Finds every value written on a line of its own — after a key, or as a list entry — with the
     * path the parser would give it. Sections, lists and comments are followed, not reported.
     */
    private static List<Scalar> scan(Lines lines) {
        List<Scalar> found = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(-1, null, false));
        int count = lines.text.size();
        int i = 0;
        while (i < count) {
            String line = lines.text.get(i);
            int col = indentOf(line);
            if (col >= line.length() || line.charAt(col) == '#') {
                i++;
                continue;
            }
            if (col == 0 && (line.startsWith("---") || line.startsWith("..."))
                    && (line.length() == 3 || line.charAt(3) == ' ')) {
                // A new document: nothing above it applies.
                stack.clear();
                stack.push(new Frame(-1, null, false));
                i++;
                continue;
            }

            // List entries, as many as the line opens ("- - x" nests one in the other).
            while (col < line.length() && line.charAt(col) == '-' && (col + 1 == line.length() || line.charAt(col + 1) == ' ')) {
                unwind(stack, col, true);
                Frame parent = stack.peek();
                stack.push(new Frame(col, parent.entries++, true));
                col = skipSpaces(line, col + 1);
            }
            if (col >= line.length() || line.charAt(col) == '#') {
                i++;
                continue;
            }

            Key key = keyAt(line, col);
            List<Object> path;
            int base;
            int from;
            if (key != null) {
                unwind(stack, col, false);
                path = pathOf(stack, key.name());
                stack.push(new Frame(col, key.name(), false));
                base = col;
                from = skipSpaces(line, key.end());
                if (from >= line.length() || line.charAt(from) == '#') {
                    // A section, a list, or nothing: what follows belongs to it.
                    i++;
                    continue;
                }
            } else {
                Frame top = stack.peek();
                if (!top.entry || top.indent >= col) {
                    // A line this does not follow: left as it is.
                    i++;
                    continue;
                }
                path = pathOf(stack, null);
                base = top.indent;
                from = col;
            }

            int[] extent = extent(lines, i, from, base);
            Scalar scalar = new Scalar(path, i, extent[0], from, extent[1], null);
            found.add(new Scalar(path, i, extent[0], from, extent[1], interpret(lines, scalar)));
            i = extent[0];
        }
        return found;
    }

    /** Leaves on the stack what a line starting at this column belongs to. */
    private static void unwind(Deque<Frame> stack, int col, boolean entry) {
        while (stack.size() > 1) {
            Frame top = stack.peek();
            // A list may sit at the same indentation as its key; its entries are siblings.
            if (top.indent > col || (top.indent == col && (top.entry || !entry))) stack.pop();
            else return;
        }
    }

    /** The path of what the stack leads to, and of a key under it when there is one. */
    private static List<Object> pathOf(Deque<Frame> stack, String key) {
        List<Object> path = new ArrayList<>();
        Iterator<Frame> bottomUp = stack.descendingIterator();
        while (bottomUp.hasNext()) {
            Frame frame = bottomUp.next();
            if (frame.label != null) path.add(frame.label);
        }
        if (key != null) path.add(key);
        return List.copyOf(path);
    }

    /**
     * A key and where its colon ends.
     *
     * @param name The key, unquoted.
     * @param end  The column after the colon.
     */
    private record Key(String name, int end) {}

    /** The key a line starts with at this column, or {@code null} when it is a bare value. */
    private static Key keyAt(String line, int col) {
        char c = line.charAt(col);
        if (c == '"' || c == '\'') {
            int close = closingQuote(line, col);
            if (close < 0) return null;
            int at = skipSpaces(line, close + 1);
            if (at < line.length() && line.charAt(at) == ':' && (at + 1 == line.length() || line.charAt(at + 1) == ' ')) {
                return new Key(unquote(line.substring(col, close + 1)), at + 1);
            }
            return null;
        }
        if ("[]{}&*!|>%@`?,".indexOf(c) >= 0) return null;
        for (int at = col; at < line.length(); at++) {
            char ch = line.charAt(at);
            if (ch == '#' && at > col && line.charAt(at - 1) == ' ') return null;
            if (ch == ':' && (at + 1 == line.length() || line.charAt(at + 1) == ' ' || line.charAt(at + 1) == '\t')) {
                String name = line.substring(col, at).trim();
                return name.isEmpty() ? null : new Key(name, at + 1);
            }
        }
        return null;
    }

    /**
     * Where a value ends.
     *
     * @param lines The file.
     * @param line  The line it starts on.
     * @param from  Where it starts on it.
     * @param base  The indentation of its key, or of its list entry: the lines that carry it on
     *              are indented further.
     * @return The line after its last one, and where it ends on its first line.
     */
    private static int[] extent(Lines lines, int line, int from, int base) {
        String text = lines.text.get(line);
        int count = lines.text.size();
        char c = text.charAt(from);
        if (c == '|' || c == '>') {
            int end = line + 1;
            int last = line + 1;
            while (end < count) {
                String next = lines.text.get(end);
                boolean blank = next.isBlank();
                if (!blank && indentOf(next) <= base) break;
                end++;
                if (!blank) last = end;
            }
            return new int[]{last, text.length()};
        }
        if (c == '"' || c == '\'') {
            int close = closingQuote(text, from);
            if (close >= 0) return new int[]{line + 1, close + 1};
            for (int end = line + 1; end < count; end++) {
                if (closesQuote(lines.text.get(end), c)) return new int[]{end + 1, text.length()};
            }
            return new int[]{count, text.length()};
        }
        if (c == '[' || c == '{') {
            int depth = 0;
            boolean inDouble = false;
            boolean inSingle = false;
            for (int ln = line; ln < count; ln++) {
                String part = lines.text.get(ln);
                for (int k = ln == line ? from : 0; k < part.length(); k++) {
                    char ch = part.charAt(k);
                    if (inDouble) {
                        if (ch == '\\') k++;
                        else if (ch == '"') inDouble = false;
                        continue;
                    }
                    if (inSingle) {
                        if (ch == '\'') {
                            if (k + 1 < part.length() && part.charAt(k + 1) == '\'') k++;
                            else inSingle = false;
                        }
                        continue;
                    }
                    if (ch == '"') inDouble = true;
                    else if (ch == '\'') inSingle = true;
                    else if (ch == '#' && (k == 0 || part.charAt(k - 1) == ' ')) break;
                    else if (ch == '[' || ch == '{') depth++;
                    else if ((ch == ']' || ch == '}') && --depth == 0) {
                        return new int[]{ln + 1, ln == line ? k + 1 : text.length()};
                    }
                }
            }
            return new int[]{count, text.length()};
        }
        // A plain value: up to a comment, and on over the deeper lines that carry it on.
        int to = text.length();
        for (int k = from; k < text.length(); k++) {
            if (text.charAt(k) == '#' && k > from && text.charAt(k - 1) == ' ') {
                to = k;
                break;
            }
        }
        while (to > from && text.charAt(to - 1) == ' ') to--;
        int end = line + 1;
        while (end < count) {
            String next = lines.text.get(end);
            int indent = indentOf(next);
            if (next.isBlank() || indent <= base || next.charAt(indent) == '#') break;
            end++;
        }
        return new int[]{end, end > line + 1 ? text.length() : to};
    }

    /** What a value reads as, as far as telling a secret goes. */
    private static Object interpret(Lines lines, Scalar scalar) {
        String first = lines.text.get(scalar.first());
        char c = first.charAt(scalar.from());
        if (c == '[' || c == '{') return FLOW;
        if (c == '|' || c == '>') {
            StringBuilder body = new StringBuilder();
            for (int i = scalar.first() + 1; i < scalar.end(); i++) body.append(lines.text.get(i).trim());
            return body.toString();
        }
        // Over several lines: something is written there, which is all a secret's name needs.
        if (scalar.multiline()) return first.substring(scalar.from()).trim() + "...";
        String raw = first.substring(scalar.from(), scalar.to());
        if (c == '"' || c == '\'') return unquote(raw);
        String plain = raw.trim();
        String lower = plain.toLowerCase(Locale.ROOT);
        if (plain.isEmpty() || lower.equals("null") || lower.equals("~")) return null;
        if (BOOLEANS.contains(lower)) return Boolean.valueOf(lower.equals("true") || lower.equals("yes") || lower.equals("on"));
        return plain;
    }

    /** The words the server's parser reads as true or false. */
    private static final Set<String> BOOLEANS = Set.of("true", "false", "yes", "no", "on", "off");

    /** What a value written on one line says, unquoted; {@code null} for anything else. */
    private static String valueAsWritten(Lines lines, Scalar scalar) {
        if (scalar.multiline()) return null;
        Object value = scalar.value();
        return value instanceof String string ? string : null;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static int skipSpaces(String line, int from) {
        int i = from;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    /** Where the quote opened at {@code open} closes on the same line, or -1. */
    private static int closingQuote(String line, int open) {
        char quote = line.charAt(open);
        for (int k = open + 1; k < line.length(); k++) {
            char ch = line.charAt(k);
            if (quote == '"') {
                if (ch == '\\') k++;
                else if (ch == '"') return k;
            } else if (ch == '\'') {
                if (k + 1 < line.length() && line.charAt(k + 1) == '\'') k++;
                else return k;
            }
        }
        return -1;
    }

    /** Whether a line carrying on a quoted value closes it. */
    private static boolean closesQuote(String line, char quote) {
        for (int k = 0; k < line.length(); k++) {
            char ch = line.charAt(k);
            if (quote == '"') {
                if (ch == '\\') k++;
                else if (ch == '"') return true;
            } else if (ch == '\'') {
                if (k + 1 < line.length() && line.charAt(k + 1) == '\'') k++;
                else return true;
            }
        }
        return false;
    }

    /** A quoted scalar's text, the escapes that matter here undone. */
    private static String unquote(String raw) {
        if (raw.length() < 2) return raw;
        char quote = raw.charAt(0);
        String body = raw.substring(1, raw.length() - 1);
        if (quote == '\'') return body.replace("''", "'");
        StringBuilder out = new StringBuilder(body.length());
        for (int k = 0; k < body.length(); k++) {
            char ch = body.charAt(k);
            if (ch == '\\' && k + 1 < body.length()) {
                char next = body.charAt(++k);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> next;
                });
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
