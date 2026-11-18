package fr.xyness.XCore.Delivery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.API.DatabaseType;
import fr.xyness.XCore.Database.SqlUtils;
import fr.xyness.XCore.Utils.Items;

/**
 * A mailbox: things a player is owed but could not be handed straight away, because they were
 * offline or their inventory was full.
 *
 * <p>Deliveries are handed over on join, oldest first, and whatever does not fit stays for next
 * time.</p>
 */
public class DeliveryService {

    private static final String TABLE = "xcore_deliveries";

    private final XCore main;

    /** Whether to hand deliveries over automatically when a player joins. */
    private volatile boolean deliverOnJoin;

    /** How many items to hand over per join, so a huge backlog does not freeze a login. */
    private volatile int perJoinLimit;

    /** Players whose deliveries are being handed over: one claim at a time per player. */
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();

    /**
     * @param main The plugin instance.
     */
    public DeliveryService(XCore main) {
        this.main = main;
        reload();
        createTable();
    }

    /** Re-reads the settings this service cares about. */
    public final void reload() {
        this.deliverOnJoin = main.getConfig().getBoolean("delivery.on-join", true);
        this.perJoinLimit = Math.max(1, main.getConfig().getInt("delivery.per-join-limit", 20));
    }

    /** @return Whether deliveries are handed over on join. */
    public boolean isDeliverOnJoin() {
        return deliverOnJoin;
    }

    private void createTable() {
        String autoInc = switch (main.getDatabaseType()) {
            case MYSQL -> "BIGINT AUTO_INCREMENT PRIMARY KEY";
            case POSTGRESQL -> "BIGSERIAL PRIMARY KEY";
            case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
        };
        String engine = main.getDatabaseType() == DatabaseType.MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        try (Connection conn = main.getDataSource().getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "id " + autoInc + ", "
                        + "player_uuid CHAR(36) NOT NULL, "
                        + "kind VARCHAR(8) NOT NULL, "
                        + "item TEXT, "
                        + "currency VARCHAR(32), "
                        + "amount DOUBLE PRECISION NOT NULL DEFAULT 0, "
                        + "reason VARCHAR(160), "
                        + "source VARCHAR(64), "
                        + "created_at VARCHAR(19) NOT NULL, "
                        + "claimed INT NOT NULL DEFAULT 0)" + engine);
            }
            SqlUtils.createIndexIfNotExists(conn, main.getDatabaseType(),
                    "idx_" + TABLE + "_owner", TABLE, "player_uuid, claimed");
        } catch (SQLException e) {
            main.logger().sendError("Failed to create the delivery table : " + e.getMessage());
        }
    }

    /**
     * Queues an item for a player.
     *
     * @param owner  Who it is for.
     * @param item   The item.
     * @param reason A short line the player will see.
     * @param source The addon sending it.
     * @return A future completing with {@code true} when the delivery was stored.
     */
    public CompletableFuture<Boolean> sendItem(UUID owner, ItemStack item, String reason, String source) {
        String encoded = Items.toBase64(item);
        if (encoded == null) return CompletableFuture.completedFuture(false);
        return insert(owner, Delivery.Kind.ITEM, encoded, null, 0, reason, source);
    }

    /**
     * Queues money for a player.
     *
     * @param owner    Who it is for.
     * @param currency The currency id.
     * @param amount   How much.
     * @param reason   A short line the player will see.
     * @param source   The addon sending it.
     * @return A future completing with {@code true} when the delivery was stored.
     */
    public CompletableFuture<Boolean> sendMoney(UUID owner, String currency, double amount, String reason, String source) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        return insert(owner, Delivery.Kind.MONEY, null, currency, amount, reason, source);
    }

    private CompletableFuture<Boolean> insert(UUID owner, Delivery.Kind kind, String item,
                                              String currency, double amount, String reason, String source) {
        String stamp = LocalDateTime.now().format(XCore.FORMATTER);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = main.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO " + TABLE
                         + " (player_uuid, kind, item, currency, amount, reason, source, created_at, claimed)"
                         + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
                ps.setString(1, owner.toString());
                ps.setString(2, kind.name());
                ps.setString(3, item);
                ps.setString(4, currency);
                ps.setDouble(5, amount);
                ps.setString(6, reason == null ? "" : reason);
                ps.setString(7, source == null ? "" : source);
                ps.setString(8, stamp);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                main.logger().sendError("Failed to queue a delivery for " + owner + " : " + e.getMessage());
                return false;
            }
        }, main.getDbExecutor());
    }

    /**
     * Reads what a player is waiting for.
     *
     * @param owner The player.
     * @param limit The most to return.
     * @return A future completing with the pending deliveries, oldest first.
     */
    public CompletableFuture<List<Delivery>> pending(UUID owner, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<Delivery> out = new ArrayList<>();
            try (Connection conn = main.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + TABLE
                         + " WHERE player_uuid = ? AND claimed = 0 ORDER BY id ASC LIMIT ?")) {
                ps.setString(1, owner.toString());
                ps.setInt(2, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs, owner));
                }
            } catch (SQLException e) {
                main.logger().sendError("Failed to read deliveries for " + owner + " : " + e.getMessage());
            }
            return out;
        }, main.getDbExecutor());
    }

    /**
     * Hands what a player is still owed over to their new UUID.
     *
     * <p>Part of XCore moving a returning player onto the UUID they came back with: the deliveries
     * waiting under the old one were owed to nobody any more — an item bought while they were away,
     * the proceeds of a sale — and sat there for good. Only what is unclaimed moves; what was handed
     * over is a record of what was done, at the time, under the UUID it was done to.</p>
     *
     * @param from The UUID the deliveries are waiting under.
     * @param to   The UUID the player is connecting with.
     * @return A future completing with how many deliveries moved; failing when the database refused.
     */
    public CompletableFuture<Integer> moveUnclaimed(UUID from, UUID to) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = main.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE " + TABLE
                         + " SET player_uuid = ? WHERE player_uuid = ? AND claimed = 0")) {
                ps.setString(1, to.toString());
                ps.setString(2, from.toString());
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, main.getDbExecutor());
    }

    /**
     * Counts what a player is waiting for.
     *
     * @param owner The player.
     * @return A future completing with the number of pending deliveries.
     */
    public CompletableFuture<Integer> countPending(UUID owner) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = main.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + TABLE
                         + " WHERE player_uuid = ? AND claimed = 0")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                return 0;
            }
        }, main.getDbExecutor());
    }

    /**
     * Hands over everything a player can take right now.
     *
     * <p>Money always goes through: its row is closed first ({@code claimed = 0} to {@code 1}, so
     * only one claim can win it), paid next, and reopened when the deposit fails. An item is only
     * marked as taken once it is in the inventory, so a full inventory postpones it instead of
     * losing it. Only one claim runs at a time for a given player.</p>
     *
     * @param player The player, who must be online.
     * @return A future completing with the number of deliveries handed over.
     */
    public CompletableFuture<Integer> claim(Player player) {
        UUID owner = player.getUniqueId();
        // Two claims side by side (a quick reconnect, or a join and a command) would both read the
        // same pending rows and hand them over twice.
        if (!claiming.add(owner)) return CompletableFuture.completedFuture(0);
        CompletableFuture<Integer> result;
        try {
            result = pending(owner, perJoinLimit).thenCompose(list -> handOver(player, list));
        } catch (RuntimeException e) {
            claiming.remove(owner);
            throw e;
        }
        return result.handle((count, error) -> {
            claiming.remove(owner);
            if (error != null) {
                main.logger().sendError("Failed to hand deliveries over to " + owner + " : " + error.getMessage());
                return 0;
            }
            return count;
        });
    }

    private CompletableFuture<Integer> handOver(Player player, List<Delivery> list) {
        if (list.isEmpty()) return CompletableFuture.completedFuture(0);
        UUID owner = player.getUniqueId();

        // Money first, off the server thread.
        List<CompletableFuture<Boolean>> money = new ArrayList<>();
        List<Delivery> items = new ArrayList<>();
        for (Delivery delivery : list) {
            if (delivery.kind() != Delivery.Kind.MONEY) {
                items.add(delivery);
                continue;
            }
            if (main.getCoinsManager() == null) continue;   // stays pending until it is back
            money.add(payOut(owner, delivery));
        }

        CompletableFuture<Void> deposits = CompletableFuture.allOf(money.toArray(new CompletableFuture[0]));

        return deposits.thenCompose(ignored -> {
            int paid = 0;
            for (CompletableFuture<Boolean> payment : money) {
                if (Boolean.TRUE.equals(payment.getNow(false))) paid++;
            }
            if (items.isEmpty()) return CompletableFuture.completedFuture(paid);

            int paidCount = paid;
            CompletableFuture<Integer> done = new CompletableFuture<>();
            // The retired callback matters: on Folia a task for a player who has just logged out is
            // dropped without a word, and the claim would never finish.
            main.schedulerAdapter().runEntityTask(player, () -> {
                List<Long> handed = new ArrayList<>();
                int given = 0;
                for (Delivery delivery : items) {
                    if (!player.isOnline()) break;
                    ItemStack item = delivery.item();
                    if (item == null) {
                        // Nothing readable in the row; close it rather than retry forever.
                        handed.add(delivery.id());
                        continue;
                    }
                    if (Items.roomFor(player.getInventory(), item) < item.getAmount()) continue;
                    Items.give(player, item);
                    handed.add(delivery.id());
                    given++;
                }
                if (handed.isEmpty()) {
                    done.complete(paidCount);
                    return;
                }
                int givenCount = given;
                markClaimed(handed).thenAccept(closed -> {
                    if (closed < handed.size()) {
                        main.logger().sendError("Only " + closed + " of " + handed.size()
                                + " delivered items of " + owner + " could be closed.");
                    }
                    done.complete(paidCount + givenCount);
                });
            }, () -> done.complete(paidCount));
            return done;
        });
    }

    /**
     * Pays one money delivery: closes the row, then deposits, then reopens the row if the deposit
     * did not go through.
     *
     * @return A future completing with {@code true} when this call paid the delivery.
     */
    private CompletableFuture<Boolean> payOut(UUID owner, Delivery delivery) {
        return setClaimed(delivery.id(), 0, 1).thenCompose(closed -> {
            if (!closed) return CompletableFuture.completedFuture(false);   // already taken elsewhere
            return main.getCoinsManager()
                    .deposit(owner, delivery.currency(), delivery.amount())
                    .exceptionally(error -> null)
                    .thenCompose(balance -> {
                        if (balance != null) return CompletableFuture.completedFuture(true);
                        // The deposit failed: back to pending, so it is paid next time instead of lost.
                        return setClaimed(delivery.id(), 1, 0).thenApply(reopened -> {
                            if (!reopened) {
                                main.logger().sendError("Delivery " + delivery.id() + " of " + owner
                                        + " could not be paid nor reopened (" + delivery.amount() + " "
                                        + delivery.currency() + ").");
                            }
                            return false;
                        });
                    });
        });
    }

    /**
     * Moves one delivery from one state to another, only if it is still in the first.
     *
     * @return A future completing with {@code true} when the row changed.
     */
    private CompletableFuture<Boolean> setClaimed(long id, int from, int to) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = main.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE " + TABLE
                         + " SET claimed = ? WHERE id = ? AND claimed = ?")) {
                ps.setInt(1, to);
                ps.setLong(2, id);
                ps.setInt(3, from);
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                main.logger().sendError("Failed to update delivery " + id + " : " + e.getMessage());
                return false;
            }
        }, main.getDbExecutor());
    }

    /**
     * Hands over deliveries when a player joins, if the setting allows it.
     *
     * @param player The player who just joined.
     */
    public void claimOnJoin(Player player) {
        if (!deliverOnJoin) return;
        claim(player).thenAccept(count -> {
            if (count <= 0 || !player.isOnline()) return;
            main.schedulerAdapter().runEntityTask(player, () ->
                    player.sendMessage(main.langManager().getComponent("delivery-received",
                            "count", String.valueOf(count))));
        });
    }

    private CompletableFuture<Integer> markClaimed(List<Long> ids) {
        return CompletableFuture.supplyAsync(() -> {
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            try (Connection conn = main.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE " + TABLE
                         + " SET claimed = 1 WHERE id IN (" + placeholders + ") AND claimed = 0")) {
                for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
                return ps.executeUpdate();
            } catch (SQLException e) {
                main.logger().sendError("Failed to close deliveries : " + e.getMessage());
                return 0;
            }
        }, main.getDbExecutor());
    }

    /**
     * Deletes deliveries that were taken long ago.
     *
     * @param days How long to keep them.
     * @return A future completing with the number of rows removed.
     */
    public CompletableFuture<Integer> purgeClaimed(int days) {
        String cutoff = LocalDateTime.now().minusDays(Math.max(1, days)).format(XCore.FORMATTER);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = main.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM " + TABLE + " WHERE claimed = 1 AND created_at < ?")) {
                ps.setString(1, cutoff);
                return ps.executeUpdate();
            } catch (SQLException e) {
                return 0;
            }
        }, main.getDbExecutor());
    }

    private Delivery read(ResultSet rs, UUID owner) throws SQLException {
        Delivery.Kind kind = "MONEY".equalsIgnoreCase(rs.getString("kind"))
                ? Delivery.Kind.MONEY : Delivery.Kind.ITEM;
        return new Delivery(
                rs.getLong("id"),
                owner,
                kind,
                kind == Delivery.Kind.ITEM ? Items.fromBase64(rs.getString("item")) : null,
                rs.getString("currency"),
                rs.getDouble("amount"),
                rs.getString("reason"),
                rs.getString("source"),
                rs.getString("created_at"));
    }
}
