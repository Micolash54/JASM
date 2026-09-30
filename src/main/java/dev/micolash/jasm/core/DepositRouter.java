package dev.micolash.jasm.core;

import java.util.ArrayList;
import java.util.List;

/** Deck slots fill and empty in slot order. Filters restrict intake, never withdrawal. */
public final class DepositRouter {
    public interface Slot<K> {
        boolean usable();
        long count(K key);
        long room(K key);
        default boolean accepts(K key) { return true; }
    }
    public record Allocation(int slot, long amount) {}
    private DepositRouter() {}
    public static <K> List<Allocation> planDeposit(List<? extends Slot<K>> slots, K key, long amount) {
        return plan(slots, key, amount, false);
    }
    public static <K> List<Allocation> planWithdraw(List<? extends Slot<K>> slots, K key, long amount) {
        return plan(slots, key, amount, true);
    }
    private static <K> List<Allocation> plan(List<? extends Slot<K>> slots, K key, long amount, boolean withdraw) {
        List<Allocation> result = new ArrayList<>();
        for (int i = 0; i < slots.size() && amount > 0; i++) {
            Slot<K> slot = slots.get(i);
            if (!slot.usable() || !withdraw && !slot.accepts(key)) continue;
            long take = Math.min(amount, withdraw ? slot.count(key) : slot.room(key));
            if (take > 0) {
                result.add(new Allocation(i, take));
                amount -= take;
            }
        }
        return result;
    }
}
