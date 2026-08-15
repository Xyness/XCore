package fr.xyness.XCore.Utils;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The formatting every addon was rewriting on its own.
 *
 * <p>Durations, thousands separators and compact numbers had one implementation per addon — five
 * spellings of the same thing, drifting apart: one printed {@code 12h 00:00} where another printed
 * {@code 12h}, one grouped with commas and another with spaces. The unit letters come from the
 * language file so a French server reads {@code 2j 4h} and an English one {@code 2d 4h}.</p>
 */
public final class Formats {

    private Formats() {}

    /** Default unit letters, used when no language manager is supplied. */
    private static final String[] DEFAULT_UNITS = { "d", "h", "m", "s" };

    /**
     * Formats a duration, dropping the parts that are zero.
     *
     * <p>{@code 2d 4h}, {@code 13m 05s}, {@code 45s} — never {@code 0d 0h 13m 05s}, and never
     * {@code 12h 00:00} where {@code 12h} says the same thing.</p>
     *
     * @param seconds The duration in seconds.
     * @param units   The four unit letters (day, hour, minute, second), or {@code null} for English.
     * @return The formatted duration.
     */
    public static String duration(long seconds, String[] units) {
        String[] u = (units == null || units.length < 4) ? DEFAULT_UNITS : units;
        if (seconds < 0) seconds = 0;
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;

        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append(u[0]);
        if (hours > 0) {
            if (out.length() > 0) out.append(' ');
            out.append(hours).append(u[1]);
        }
        if (minutes > 0 && days == 0) {
            if (out.length() > 0) out.append(' ');
            out.append(minutes).append(u[2]);
        }
        if (secs > 0 && days == 0 && hours == 0) {
            if (out.length() > 0) out.append(' ');
            out.append(secs).append(u[3]);
        }
        return out.length() == 0 ? "0" + u[3] : out.toString();
    }

    /**
     * Formats a duration with English unit letters.
     *
     * @param seconds The duration in seconds.
     * @return The formatted duration.
     */
    public static String duration(long seconds) {
        return duration(seconds, null);
    }

    /**
     * Formats the time left until a moment in the future.
     *
     * @param target The deadline.
     * @param units  The four unit letters, or {@code null} for English.
     * @return The formatted remainder, or {@code null} when the deadline has passed.
     */
    public static String remaining(LocalDateTime target, String[] units) {
        if (target == null) return null;
        LocalDateTime now = LocalDateTime.now();
        if (!target.isAfter(now)) return null;
        return duration(Duration.between(now, target).getSeconds(), units);
    }

    /**
     * Groups thousands with a non-breaking-friendly space.
     *
     * @param value The number.
     * @return e.g. {@code 1 234 567}.
     */
    public static String number(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3 + 1);
        if (value < 0) out.append('-');
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(' ');
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    /**
     * Shortens a large number for a place where it has to fit: {@code 1.2k}, {@code 3.4M}.
     *
     * @param value The number.
     * @return The compact form; small numbers are returned unchanged.
     */
    public static String compact(double value) {
        double abs = Math.abs(value);
        if (abs < 1_000) return trim(value);
        if (abs < 1_000_000) return trim(value / 1_000) + "k";
        if (abs < 1_000_000_000) return trim(value / 1_000_000) + "M";
        if (abs < 1_000_000_000_000L) return trim(value / 1_000_000_000) + "B";
        return trim(value / 1_000_000_000_000L) + "T";
    }

    /** One decimal, and none at all when it would be a zero. */
    private static String trim(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    /**
     * Formats a byte count for a human: {@code 512 B}, {@code 4.2 MB}.
     *
     * @param bytes The size in bytes.
     * @return The formatted size.
     */
    public static String bytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return trim(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return trim(bytes / (1024.0 * 1024)) + " MB";
        return trim(bytes / (1024.0 * 1024 * 1024)) + " GB";
    }
}
