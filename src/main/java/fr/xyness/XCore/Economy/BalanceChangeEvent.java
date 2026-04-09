package fr.xyness.XCore.Economy;

import java.util.UUID;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a balance change is applied.
 * <p>
 * Other plugins can listen to this event and cancel it to prevent the change.
 * </p>
 */
public class BalanceChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String currencyId;
    private final double oldBalance;
    private final double newBalance;
    private final ChangeType type;
    private boolean cancelled;

    public BalanceChangeEvent(UUID playerId, String currencyId, double oldBalance, double newBalance, ChangeType type) {
        super(true); // async
        this.playerId = playerId;
        this.currencyId = currencyId;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.type = type;
        this.cancelled = false;
    }

    public UUID getPlayerId() { return playerId; }
    public String getCurrencyId() { return currencyId; }
    public double getOldBalance() { return oldBalance; }
    public double getNewBalance() { return newBalance; }
    public ChangeType getType() { return type; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }

    /**
     * The type of balance change operation.
     */
    public enum ChangeType {
        PAY, SET, ADD, REMOVE, EXCHANGE
    }
}
