package dev.micolash.jasm.core;

import java.util.ArrayList;
import java.util.List;

/** Deterministic Deck routing. Pure planning: callers execute the allocations. */
public final class DepositRouter {
    /** One Deck wafer slot as seen by the router. Unusable slots (empty or invalid) are skipped. */
    public interface Slot<K> {
        boolean usable();

        long count(K key);

        /** How many of {@code key} still fit. */
        long room(K key);
    }

    public record Allocation(int slot, long amount) {}

    private DepositRouter() {}

    /** Pass 1: slots already holding the key, ascending. Pass 2: other slots with space, ascending. */
    public static <K> List<Allocation> planDeposit(List<? extends Slot<K>> slots, K key, long amount) {
        List<Allocation> plan = new ArrayList<>();
        long remaining = amount;
        boolean[] visited = new boolean[slots.size()];
        for (int pass = 1; pass <= 2 && remaining > 0; pass++) {
            for (int i = 0; i < slots.size() && remaining > 0; i++) {
                Slot<K> slot = slots.get(i);
                if (visited[i] || !slot.usable()) {
                    continue;
                }
                if (pass == 1 && slot.count(key) <= 0) {
                    continue;
                }
                visited[i] = true;
                long take = Math.min(remaining, slot.room(key));
                if (take > 0) {
                    plan.add(new Allocation(i, take));
                    remaining -= take;
                }
            }
        }
        return plan;
    }

    /** Withdraw from slots holding the key, highest index first. */
    public static <K> List<Allocation> planWithdraw(List<? extends Slot<K>> slots, K key, long amount) {
        List<Allocation> plan = new ArrayList<>();
        long remaining = amount;
        for (int i = slots.size() - 1; i >= 0 && remaining > 0; i--) {
            Slot<K> slot = slots.get(i);
            if (!slot.usable()) {
                continue;
            }
            long take = Math.min(remaining, slot.count(key));
            if (take > 0) {
                plan.add(new Allocation(i, take));
                remaining -= take;
            }
        }
        return plan;
    }
}
