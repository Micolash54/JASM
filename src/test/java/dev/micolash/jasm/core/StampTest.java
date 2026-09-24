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
        // Session epoch 5 minted up to counter 1000, then crashed before the saved data caught up with counter 1000.
        StampAuthority persisted = new StampAuthority(5, 10);
        Stamp lostSessionStamp = new Stamp(5, 1000);
        persisted.beginEpoch(0);
        Stamp fresh = persisted.mint();
        assertEquals(new Stamp(6, 1), fresh);
        assertTrue(fresh.isAfter(lostSessionStamp));
    }

    @Test
    void epochFollowsTheClock() {
        StampAuthority authority = new StampAuthority(5, 10);
        authority.beginEpoch(1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, authority.epoch());
        assertEquals(0, authority.counter());
    }

    @Test
    void epochAdvancesEvenIfTheClockWentBack() {
        StampAuthority authority = new StampAuthority(1_700_000_000_000L, 4);
        authority.beginEpoch(1_600_000_000_000L);
        assertEquals(1_700_000_000_001L, authority.epoch());
    }

    @Test
    void restoredOldDataNeverReusesAnEpoch() {
        // Saved data restored from an old copy: its epoch is behind sessions that already handed out stamps.
        Stamp handedOutAfterTheCopy = new Stamp(1_700_000_500_000L, 40);
        StampAuthority restored = new StampAuthority(1_700_000_000_000L, 12);
        restored.beginEpoch(1_700_000_900_000L);
        assertTrue(restored.mint().isAfter(handedOutAfterTheCopy));
    }

    @Test
    void observingAStampAheadMovesPastIt() {
        StampAuthority authority = new StampAuthority(7, 3);
        Stamp ahead = new Stamp(7, 50);
        authority.observe(ahead);
        Stamp next = authority.mint();
        assertTrue(next.isAfter(ahead));
        assertTrue(next.isAfter(new Stamp(7, Long.MAX_VALUE)), "skips the whole epoch of the unexpected stamp");
    }

    @Test
    void observingAFutureEpochMovesPastIt() {
        StampAuthority authority = new StampAuthority(7, 3);
        Stamp future = new Stamp(9, 2);
        authority.observe(future);
        assertTrue(authority.mint().isAfter(new Stamp(9, Long.MAX_VALUE)));
    }

    @Test
    void observingAnOlderStampChangesNothing() {
        StampAuthority authority = new StampAuthority(7, 3);
        authority.observe(new Stamp(6, 999));
        authority.observe(new Stamp(7, 2));
        assertEquals(new Stamp(7, 4), authority.mint());
    }
}
