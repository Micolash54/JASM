package dev.micolash.jasm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.micolash.jasm.core.StampPolicy.RecordView;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plays through saves and crashes where a wafer's record and the items that carry its stamp are saved at different
 * times, in both orders, with and without copies. After every use, only the copy just used may still work.
 */
class DesyncSimulationTest {
    private static final int CAP = 1024;

    /** One wafer: its record in memory, its record on disk, the stamp counter, and every physical copy of the item. */
    private static final class World {
        StampAuthority stamps = new StampAuthority(1, 0);
        Stamp current;
        Stamp floor;
        boolean newestSeen = true;
        Stamp savedCurrent;
        Stamp savedFloor;
        long savedEpoch;
        long savedCounter;
        long clock = 100;
        final List<Item> items = new ArrayList<>();
        int fastForwards;

        World() {
            current = stamps.mint();
            saveRecord();
        }

        Item newItem() {
            Item item = new Item(current);
            items.add(item);
            return item;
        }

        Item copyOf(Item item) {
            Item copy = new Item(item.stamp);
            items.add(copy);
            return copy;
        }

        RecordView view() {
            return new RecordView(current, floor, CAP, newestSeen);
        }

        Verdict judge(Item item) {
            return StampPolicy.judge(item.stamp, CAP, view());
        }

        /** Opening a Deck with the item: the same steps the validator takes in ACTIVATE mode. */
        Verdict use(Item item) {
            Verdict verdict = judge(item);
            switch (verdict) {
                case VALID_AHEAD -> {
                    current = item.stamp;
                    stamps.observe(item.stamp);
                    fastForwards++;
                    restamp(item);
                }
                case VALID -> restamp(item);
                case DUPLICATE, RECOVERED_ORIGINAL -> item.gone = true;
                default -> {
                }
            }
            if (verdict.grantsAccess()) {
                for (Item other : items) {
                    if (other != item && !other.gone) {
                        assertFalse(judge(other).grantsAccess(), "after one copy is used, no other copy may work");
                    }
                }
            }
            return verdict;
        }

        private void restamp(Item item) {
            Stamp stamp = stamps.mint();
            current = stamp;
            newestSeen = true;
            item.stamp = stamp;
        }

        void recover() {
            Stamp stamp = stamps.mint();
            current = stamp;
            floor = stamp;
            newestSeen = true;
            items.add(new Item(stamp));
        }

        void saveRecord() {
            savedCurrent = current;
            savedFloor = floor;
            savedEpoch = stamps.epoch();
            savedCounter = stamps.counter();
        }

        /** The server dies: memory is lost, the record and counter come back as last saved, and a new session starts. */
        void crashAndRestart(List<Item> survivingItems) {
            current = savedCurrent;
            floor = savedFloor;
            newestSeen = false;
            stamps = new StampAuthority(savedEpoch, savedCounter);
            stamps.beginEpoch(clock += 100);
            items.retainAll(survivingItems);
        }
    }

    private static final class Item {
        Stamp stamp;
        boolean gone;

        Item(Stamp stamp) {
            this.stamp = stamp;
        }
    }

    @Test
    void itemSavedRecordLostFastForwards() {
        World world = new World();
        Item wafer = world.newItem();
        world.use(wafer);
        Item saved = world.copyOf(wafer); // the player's file was written; the record was not
        world.crashAndRestart(List.of(saved));

        assertEquals(Verdict.VALID_AHEAD, world.use(saved), "the saved item is ahead of the record");
        assertEquals(1, world.fastForwards, "the record catches up instead of refusing");
        assertEquals(Verdict.VALID, world.use(saved), "and keeps working afterwards");
    }

    @Test
    void itemSavedRecordLostWithAnOlderCopy() {
        World world = new World();
        Item wafer = world.newItem();
        Item oldCopy = world.copyOf(wafer); // copied before the last use, and saved somewhere
        world.use(wafer);
        world.crashAndRestart(List.of(wafer, oldCopy));

        assertEquals(Verdict.VALID_AHEAD, world.use(wafer));
        assertEquals(Verdict.DUPLICATE, world.use(oldCopy), "the older copy is wiped once the newer one has been used");
    }

    @Test
    void itemSavedRecordLostOlderCopyUsedFirst() {
        World world = new World();
        Item wafer = world.newItem();
        Item oldCopy = world.copyOf(wafer);
        world.use(wafer);
        world.crashAndRestart(List.of(wafer, oldCopy));

        assertEquals(Verdict.VALID, world.use(oldCopy), "the old copy matches the saved record and wins the race");
        assertEquals(Verdict.DUPLICATE, world.use(wafer), "the other one is then a copy");
    }

    @Test
    void recordSavedItemLostLocksTheOlderItem() {
        World world = new World();
        Item wafer = world.newItem();
        Item olderSaved = world.copyOf(wafer); // what the player's file holds
        world.use(wafer);
        world.saveRecord(); // the record got written with the newer stamp; the newer item was lost with the crash
        world.crashAndRestart(List.of(olderSaved));

        assertEquals(Verdict.STALE, world.use(olderSaved), "an older item is locked, not wiped, while the newest hasn't shown up");
        assertEquals(Verdict.STALE, world.use(olderSaved), "it stays locked, keeping its identity for recovery");
        world.recover();
        assertEquals(Verdict.RECOVERED_ORIGINAL, world.use(olderSaved), "after recovery the old one dissolves");
    }

    @Test
    void recordSavedItemLostButTheNewestTurnsUp() {
        World world = new World();
        Item wafer = world.newItem();
        Item olderSaved = world.copyOf(wafer);
        world.use(wafer);
        world.saveRecord();
        world.crashAndRestart(List.of(olderSaved, wafer));

        assertEquals(Verdict.STALE, world.judge(olderSaved), "locked at first");
        assertEquals(Verdict.VALID, world.use(wafer), "the newest copy shows up and works");
        assertEquals(Verdict.DUPLICATE, world.use(olderSaved), "now the older one is known to be a copy");
    }

    @Test
    void repeatedCrashesNeverLetTwoCopiesWork() {
        World world = new World();
        Item a = world.newItem();
        Item b = world.copyOf(a);
        for (int round = 0; round < 5; round++) {
            world.use(round % 2 == 0 ? a : b);
            if (round % 2 == 0) {
                world.saveRecord();
            }
            world.crashAndRestart(List.of(a, b));
        }
        int working = 0;
        for (Item item : world.items) {
            if (!item.gone && world.judge(item).grantsAccess()) {
                working++;
            }
        }
        assertTrue(working <= 2, "before anything is used, at most the saved copy and one ahead of it can open");
        Item first = world.items.stream().filter(i -> !i.gone && world.judge(i).grantsAccess()).findFirst().orElseThrow();
        world.use(first);
    }
}
