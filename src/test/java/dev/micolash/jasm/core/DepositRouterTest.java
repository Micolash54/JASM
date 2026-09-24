package dev.micolash.jasm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.micolash.jasm.core.DepositRouter.Allocation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DepositRouterTest {
    /** Test double: a usable slot with fixed contents and free space, or an unusable one. */
    private record FakeSlot(boolean usable, Map<String, Long> contents, long free) implements DepositRouter.Slot<String> {
        static FakeSlot of(long free, Map<String, Long> contents) {
            return new FakeSlot(true, contents, free);
        }

        static FakeSlot unusable() {
            return new FakeSlot(false, Map.of(), 0);
        }

        @Override
        public long count(String key) {
            return contents.getOrDefault(key, 0L);
        }
    }

    @Test
    void prefersSlotsAlreadyHoldingTheKey() {
        List<FakeSlot> slots = List.of(FakeSlot.of(100, Map.of()), FakeSlot.of(100, Map.of("stone", 5L)));
        assertEquals(List.of(new Allocation(1, 64)), DepositRouter.planDeposit(slots, "stone", 64));
    }

    @Test
    void splitsAcrossHoldersThenFreeSlotsInAscendingOrder() {
        List<FakeSlot> slots = List.of(
                FakeSlot.of(10, Map.of()),
                FakeSlot.of(20, Map.of("stone", 1L)),
                FakeSlot.of(5, Map.of()),
                FakeSlot.of(3, Map.of("stone", 9L)));
        List<Allocation> plan = DepositRouter.planDeposit(slots, "stone", 30);
        assertEquals(List.of(new Allocation(1, 20), new Allocation(3, 3), new Allocation(0, 7)), plan);
    }

    @Test
    void remainderStaysUnallocatedWhenEverythingIsFull() {
        List<FakeSlot> slots = List.of(FakeSlot.of(4, Map.of()), FakeSlot.unusable());
        List<Allocation> plan = DepositRouter.planDeposit(slots, "stone", 10);
        assertEquals(List.of(new Allocation(0, 4)), plan);
    }

    @Test
    void skipsUnusableSlots() {
        List<FakeSlot> slots = List.of(FakeSlot.unusable(), FakeSlot.of(50, Map.of()));
        assertEquals(List.of(new Allocation(1, 10)), DepositRouter.planDeposit(slots, "stone", 10));
    }

    @Test
    void withdrawsFromHighestSlotFirst() {
        List<FakeSlot> slots = List.of(
                FakeSlot.of(0, Map.of("stone", 50L)),
                FakeSlot.of(0, Map.of("dirt", 50L)),
                FakeSlot.of(0, Map.of("stone", 10L)));
        List<Allocation> plan = DepositRouter.planWithdraw(slots, "stone", 30);
        assertEquals(List.of(new Allocation(2, 10), new Allocation(0, 20)), plan);
    }

    @Test
    void withdrawIgnoresUnusableSlots() {
        List<FakeSlot> slots = List.of(FakeSlot.of(0, Map.of("stone", 5L)), new FakeSlot(false, Map.of("stone", 99L), 0));
        assertEquals(List.of(new Allocation(0, 5)), DepositRouter.planWithdraw(slots, "stone", 64));
    }
}
