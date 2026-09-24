package dev.micolash.jasm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StampTest {
    @Test
    void ordersByEpochThenCounter() {
        assertTrue(new Stamp(1, 99).isBefore(new Stamp(2, 1)));
        assertTrue(new Stamp(2, 1).isBefore(new Stamp(2, 2)));
        assertTrue(new Stamp(2, 2).isAfter(new Stamp(2, 1)));
        assertEquals(0, new Stamp(3, 4).compareTo(new Stamp(3, 4)));
    }

    @Test
    void mintIncreasesWithinEpoch() {
        StampAuthority authority = new StampAuthority(1, 0);
        Stamp a = authority.mint();
        Stamp b = authority.mint();
        assertEquals(new Stamp(1, 1), a);
        assertTrue(b.isAfter(a));
    }

    @Test
    void newEpochOutranksEveryStampOfALostSession() {
        // Session epoch 5 minted up to counter 1000, then crashed before the ledger saved counter 1000.
        StampAuthority persisted = new StampAuthority(5, 10);
        Stamp lostSessionStamp = new Stamp(5, 1000);
        persisted.beginEpoch();
        Stamp fresh = persisted.mint();
        assertEquals(new Stamp(6, 1), fresh);
        assertTrue(fresh.isAfter(lostSessionStamp));
    }

    @Test
    void observeKeepsCurrentEpochMintsUnique() {
        StampAuthority authority = new StampAuthority(7, 3);
        authority.observe(new Stamp(7, 50));
        assertEquals(new Stamp(7, 51), authority.mint());
        authority.observe(new Stamp(6, 999));
        assertEquals(new Stamp(7, 52), authority.mint());
    }
}
