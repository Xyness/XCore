package fr.xyness.XCore.Delivery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
     * <p>Money always goes through. An item is only marked as taken once it is in the inventory, so
     * a full inventory postpones it instead of losing it.</p>
     *
     * @param player The player, who must be online.
     * @return A future completing with the number of deliveries handed over.
     */
    public CompletableFuture<Integer> claim(Player player) {
        UUID owner = player.getUniqueId();
        return pending(owner, perJoinLimit).thenCompose(list -> {
            if (list.isEmpty()) return CompletableFuture.completedFuture(0);

            // Money first, off the server thread. A delivery is only closed once the deposit has
            // gone through, otherwise a failed payment counts as delivered and is lost.
            List<CompletableFuture<Long>> money = new ArrayList<>();
            List<Delivery> items = new ArrayList<>();
            for (Delivery delivery : list) {
                if (delivery.kind() != Delivery.Kind.MONEY) {
                    items.add(delivery);
                    continue;
                }
                if (main.getCoinsManager() == null) continue;   // stays pending until it is back
                money.add(main.getCoinsManager()
                        .deposit(owner, delivery.currency(), delivery.amount())
                        .thenApply(balance -> balance == null ? null : delivery.id())
                        .exceptionally(error -> null));
            }

            CompletableFuture<Void> deposits = CompletableFuture.allOf(money.toArray(new CompletableFuture[0]));

            return deposits.thenCompose(ignored -> {
                List<Long> handed = new ArrayList<>();
                for (CompletableFuture<Long> paid : money) {
                    Long id = paid.getNow(null);
                    if (id != null) handed.add(id);
                }

                CompletableFuture<Integer> done = new CompletableFuture<>();
                main.schedulerAdapter().runEntityTask(player, () -> {
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
                    }
                    if (handed.isEmpty()) {
                        done.complete(0);
                        return;
                    }
                    markClaimed(handed).thenAccept(done::complete);
                });
                return done;
            });
        });
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
