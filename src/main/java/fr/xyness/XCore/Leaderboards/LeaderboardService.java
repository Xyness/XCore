package fr.xyness.XCore.Leaderboards;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import fr.xyness.XCore.XCore;

/**
 * Keeps the rankings addons declare up to date: one refresh task for all of them, and the values
 * available as placeholders without extra work.
 *
 * <pre>{@code
 * core().leaderboards().define("kills")
 *     .table("xtools_warzone")
 *     .value("kills")
 *     .refreshEvery(300)
 *     .size(10)
 *     .register();
 * }</pre>
 *
 * <p>That publishes {@code %xcore_top_kills_1_name%} and {@code %xcore_top_kills_1_value%}.</p>
 */
public class LeaderboardService {

    private static final Pattern SAFE_IDENT = Pattern.compile("[a-zA-Z0-9_]+");

    private final XCore main;
    private final Map<String, Leaderboard> boards = new ConcurrentHashMap<>();
    private Object task;

    /**
     * @param main The plugin instance.
     */
    public LeaderboardService(XCore main) {
        this.main = main;
    }

    /**
     * Starts describing a ranking.
     *
     * @param id The name it will be known by, in placeholders included.
     * @return A builder.
     */
    public Leaderboard.Builder define(String id) {
        if (id == null || !SAFE_IDENT.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid leaderboard id: " + id);
        }
        return new Leaderboard.Builder(this, id.toLowerCase());
    }

    /**
     * @param id The ranking name.
     * @return The ranking, or {@code null} when nothing is registered under that name.
     */
    public Leaderboard get(String id) {
        return id == null ? null : boards.get(id.toLowerCase());
    }

    /** @return Every registered ranking. */
    public Collection<Leaderboard> all() {
        return boards.values();
    }

    /**
     * Drops a ranking, for an addon being disabled.
     *
     * @param id The ranking name.
     */
    public void remove(String id) {
        if (id != null) boards.remove(id.toLowerCase());
    }

    /** Starts the refresh loop. */
    public void start() {
        if (task != null) return;
        // Every ten seconds, and each board decides whether its own period has elapsed.
        task = main.schedulerAdapter().runAsyncTaskTimer(this::tick, 100L, 200L);
    }

    /** Stops the refresh loop. */
    public void stop() {
        if (task != null) {
            main.schedulerAdapter().cancelTask(task);
            task = null;
        }
    }

    Leaderboard register(Leaderboard board) {
        validate(board);
        boards.put(board.getId(), board);
        main.getDbExecutor().execute(() -> refresh(board));
        return board;
    }

    private void validate(Leaderboard board) {
        String[] identifiers = {board.getTable(), board.getValueColumn(), board.getUuidColumn()};
        for (String identifier : identifiers) {
            if (identifier == null || !SAFE_IDENT.matcher(identifier).matches()) {
                throw new IllegalArgumentException("Invalid identifier in leaderboard '"
                        + board.getId() + "': " + identifier);
            }
        }
        if (board.getNameColumn() != null && !SAFE_IDENT.matcher(board.getNameColumn()).matches()) {
            throw new IllegalArgumentException("Invalid name column in leaderboard '" + board.getId() + "'.");
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Leaderboard board : boards.values()) {
            if (now - board.getRefreshedAt() < board.getRefreshSeconds() * 1000L) continue;
            refresh(board);
        }
    }

    /**
     * Re-reads one ranking now.
     *
     * @param board The ranking to refresh.
     */
    public void refresh(Leaderboard board) {
        String columns = board.getUuidColumn() + ", " + board.getValueColumn()
                + (board.getNameColumn() != null ? ", " + board.getNameColumn() : "");
        String sql = "SELECT " + columns + " FROM " + board.getTable()
                + " WHERE " + board.getValueColumn() + " IS NOT NULL"
                + (board.getFilter() != null && !board.getFilter().isBlank() ? " AND (" + board.getFilter() + ")" : "")
                + " ORDER BY " + board.getValueColumn() + (board.isDescending() ? " DESC" : " ASC")
                + " LIMIT " + board.getSize();

        List<Leaderboard.Entry> entries = new ArrayList<>();
        try (Connection conn = main.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int rank = 0;
            while (rs.next()) {
                rank++;
                UUID uuid = null;
                String raw = rs.getString(board.getUuidColumn());
                if (raw != null) {
                    try {
                        uuid = UUID.fromString(raw);
                    } catch (IllegalArgumentException ignored) {
                        // Not a UUID column after all; the name will have to do.
                    }
                }
                String name = board.getNameColumn() != null ? rs.getString(board.getNameColumn()) : null;
                if (name == null && uuid != null) {
                    name = main.playerCache().getPlayerSync(uuid)
                            .map(fr.xyness.XCore.Models.PlayerData::getName)
                            .orElse(null);
                }
                entries.add(new Leaderboard.Entry(rank, uuid,
                        name == null ? "" : name, rs.getDouble(board.getValueColumn())));
            }
        } catch (SQLException e) {
            main.logger().sendWarning("Could not refresh the '" + board.getId() + "' leaderboard : " + e.getMessage());
            return;
        }
        board.replace(entries);
    }

    /**
     * Resolves the {@code top_<board>_<rank>_<field>} placeholders.
     *
     * @param argument What follows {@code top_}.
     * @return The value, or {@code null} when the request makes no sense.
     */
    public String resolvePlaceholder(String argument) {
        if (argument == null) return null;
        String[] parts = argument.split("_");
        if (parts.length < 3) return null;

        String field = parts[parts.length - 1];
        int rank;
        try {
            rank = Integer.parseInt(parts[parts.length - 2]);
        } catch (NumberFormatException e) {
            return null;
        }
        String id = String.join("_", java.util.Arrays.copyOfRange(parts, 0, parts.length - 2));

        Leaderboard board = get(id);
        if (board == null) return null;
        Leaderboard.Entry entry = board.at(rank);
        if (entry == null) return "";

        return switch (field.toLowerCase()) {
            case "name" -> entry.name();
            case "value" -> fr.xyness.XCore.Utils.Formats.compact(entry.value());
            case "raw" -> String.valueOf(entry.value());
            case "uuid" -> entry.uuid() == null ? "" : entry.uuid().toString();
            default -> null;
        };
    }
}
