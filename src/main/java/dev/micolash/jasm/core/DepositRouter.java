package dev.micolash.jasm.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic Deck routing. Pure planning: callers execute the allocations. */
public final class DepositRouter {
    /** One Deck wafer slot as seen by the router. Unusable slots (empty or invalid) are skipped. */
    public interface Slot<K> {
        boolean usable();

        long count(K key);

        /** How many of {@code key} still fit. */
        long room(K key);

        /** Higher fills first and empties last. */
        default int priority() {
            return 0;
        }

        /** Whether the slot's filter lists {@code key}. */
        default boolean listed(K key) {
            return false;
        }

        /** Takes only what its filter lists. */
        default boolean only() {
            return false;
        }
    }

    public record Allocation(int slot, long amount) {}

    private DepositRouter() {}

    /**
     * Fills in three passes, each over the slots from the highest priority down (ties by slot order): slots whose
     * filter lists the key, then slots already holding it, then any slot not set to take only its filter. A slot
     * set to "only" never takes a key its filter doesn't list. Nothing is ever dropped: what fits nowhere stays out.
     */
    public static <K> List<Allocation> planDeposit(List<? extends Slot<K>> slots, K key, long amount) {
        List<Integer> order = order(slots, Comparator.comparingInt((Integer i) -> -slots.get(i).priority()).thenComparingInt(i -> i));
        List<Allocation> plan = new ArrayList<>();
        long remaining = amount;
        boolean[] visited = new boolean[slots.size()];
        for (int pass = 1; pass <= 3 && remaining > 0; pass++) {
            for (int i : order) {
                if (remaining <= 0) {
                    break;
                }
                Slot<K> slot = slots.get(i);
                boolean listed = slot.listed(key);
                boolean wanted = switch (pass) {
                    case 1 -> listed;
                    case 2 -> slot.count(key) > 0 && (listed || !slot.only());
                    default -> !slot.only();
                };
                if (visited[i] || !slot.usable() || !wanted) {
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

    /** Withdraws from slots holding the key, lowest priority first (ties: highest slot first). */
    public static <K> List<Allocation> planWithdraw(List<? extends Slot<K>> slots, K key, long amount) {
        List<Integer> order = order(slots, Comparator.comparingInt((Integer i) -> slots.get(i).priority()).thenComparingInt(i -> -i));
        List<Allocation> plan = new ArrayList<>();
        long remaining = amount;
        for (int i : order) {
            if (remaining <= 0) {
                break;
            }
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

    private static List<Integer> order(List<?> slots, Comparator<Integer> comparator) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            order.add(i);
        }
        order.sort(comparator);
        return order;
    }
}
