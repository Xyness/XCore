package fr.xyness.XCore.Leaderboards;

import java.util.List;
import java.util.UUID;

/**
 * One ranking, kept in memory and refreshed on a timer.
 *
 * <p>Reading it never touches the database: {@link #top()} hands back the last snapshot. That is the
 * whole point — a ranking is usually read from a scoreboard or a placeholder that runs several times
 * a second, and the values move slowly.</p>
 */
public class Leaderboard {

    /** One line of a ranking. */
    public record Entry(int rank, UUID uuid, String name, double value) {}

    private final String id;
    private final String table;
    private final String valueColumn;
    private final String uuidColumn;
    private final String nameColumn;
    private final String filter;
    private final boolean descending;
    private final int size;
    private final long refreshSeconds;

    private volatile List<Entry> snapshot = List.of();
    private volatile long refreshedAt;

    Leaderboard(Builder builder) {
        this.id = builder.id;
        this.table = builder.table;
        this.valueColumn = builder.valueColumn;
        this.uuidColumn = builder.uuidColumn;
        this.nameColumn = builder.nameColumn;
        this.filter = builder.filter;
        this.descending = builder.descending;
        this.size = builder.size;
        this.refreshSeconds = builder.refreshSeconds;
    }

    /** @return The name this ranking is registered under. */
    public String getId() {
        return id;
    }

    /** @return The last snapshot, best first. Empty until the first refresh has run. */
    public List<Entry> top() {
        return snapshot;
    }

    /**
     * @param count How many lines to return.
     * @return The first {@code count} lines of the snapshot.
     */
    public List<Entry> top(int count) {
        List<Entry> current = snapshot;
        return current.size() <= count ? current : current.subList(0, count);
    }

    /**
     * @param rank The rank, starting at 1.
     * @return That line, or {@code null} when the ranking is shorter.
     */
    public Entry at(int rank) {
        List<Entry> current = snapshot;
        return rank < 1 || rank > current.size() ? null : current.get(rank - 1);
    }

    /**
     * Looks a player up in the snapshot.
     *
     * @param uuid The player.
     * @return Their rank, or 0 when they are not in it.
     */
    public int rankOf(UUID uuid) {
        for (Entry entry : snapshot) {
            if (entry.uuid() != null && entry.uuid().equals(uuid)) return entry.rank();
        }
        return 0;
    }

    /** @return When the snapshot was taken, in milliseconds. */
    public long getRefreshedAt() {
        return refreshedAt;
    }

    void replace(List<Entry> entries) {
        this.snapshot = List.copyOf(entries);
        this.refreshedAt = System.currentTimeMillis();
    }

    String getTable() { return table; }
    String getValueColumn() { return valueColumn; }
    String getUuidColumn() { return uuidColumn; }
    String getNameColumn() { return nameColumn; }
    String getFilter() { return filter; }
    boolean isDescending() { return descending; }
    int getSize() { return size; }
    long getRefreshSeconds() { return refreshSeconds; }

    /**
     * Describes a ranking. Get one from {@code LeaderboardService#define(String)}.
     */
    public static class Builder {

        private final LeaderboardService service;
        private final String id;
        private String table = "players";
        private String valueColumn;
        private String uuidColumn = "server_uuid";
        private String nameColumn = "player_name";
        private String filter;
        private boolean descending = true;
        private int size = 10;
        private long refreshSeconds = 300;

        Builder(LeaderboardService service, String id) {
            this.service = service;
            this.id = id;
        }

        /**
         * @param table Where the values live. Defaults to {@code players}.
         * @return This builder.
         */
        public Builder table(String table) {
            this.table = table;
            return this;
        }

        /**
         * @param column The column being ranked.
         * @return This builder.
         */
        public Builder value(String column) {
            this.valueColumn = column;
            return this;
        }

        /**
         * @param column The column holding the player's UUID.
         * @return This builder.
         */
        public Builder uuid(String column) {
            this.uuidColumn = column;
            return this;
        }

        /**
         * @param column The column holding the player's name, or {@code null} to resolve names from
         *               the UUID through the player cache.
         * @return This builder.
         */
        public Builder name(String column) {
            this.nameColumn = column;
            return this;
        }

        /**
         * @param sql An extra condition, without the {@code WHERE}. Only literal SQL, never
         *            user input.
         * @return This builder.
         */
        public Builder where(String sql) {
            this.filter = sql;
            return this;
        }

        /**
         * @param descending {@code true} for highest first, which is the default.
         * @return This builder.
         */
        public Builder descending(boolean descending) {
            this.descending = descending;
            return this;
        }

        /**
         * @param size How many lines to keep. Defaults to 10.
         * @return This builder.
         */
        public Builder size(int size) {
            this.size = Math.max(1, Math.min(size, 200));
            return this;
        }

        /**
         * @param seconds How often to re-read the table. Defaults to five minutes.
         * @return This builder.
         */
        public Builder refreshEvery(long seconds) {
            this.refreshSeconds = Math.max(10, seconds);
            return this;
        }

        /**
         * Registers the ranking and schedules its first refresh.
         *
         * @return The live ranking.
         */
        public Leaderboard register() {
            if (valueColumn == null) throw new IllegalStateException("A leaderboard needs a value column.");
            return service.register(new Leaderboard(this));
        }
    }
}
