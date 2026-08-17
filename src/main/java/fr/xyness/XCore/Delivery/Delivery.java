package fr.xyness.XCore.Delivery;

import java.util.UUID;

import org.bukkit.inventory.ItemStack;

/**
 * One thing waiting to be handed to a player.
 *
 * @param id       The row identifier.
 * @param owner    Who it belongs to.
 * @param kind     Whether it holds an item or money.
 * @param item     The item, for {@link Kind#ITEM}.
 * @param currency The currency id, for {@link Kind#MONEY}.
 * @param amount   The amount, for {@link Kind#MONEY}.
 * @param reason   A short line shown to the player, usually a language key's result.
 * @param source   The addon that sent it.
 * @param sentAt   When it was queued.
 */
public record Delivery(long id, UUID owner, Kind kind, ItemStack item,
                       String currency, double amount, String reason, String source, String sentAt) {

    /** What a delivery carries. */
    public enum Kind {
        /** An item stack. */
        ITEM,
        /** An amount of a currency. */
        MONEY
    }

    /** @return A one-line description, for logs and GUI lore. */
    public String describe() {
        return kind == Kind.MONEY
                ? amount + " " + currency
                : (item == null ? "?" : item.getAmount() + "x " + item.getType().name());
    }
}
