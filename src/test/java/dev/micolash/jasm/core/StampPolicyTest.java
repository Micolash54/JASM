package dev.micolash.jasm.core;

import static dev.micolash.jasm.core.StampPolicy.Verdict.DUPLICATE;
import static dev.micolash.jasm.core.StampPolicy.Verdict.ORPHAN;
import static dev.micolash.jasm.core.StampPolicy.Verdict.RECOVERED_ORIGINAL;
import static dev.micolash.jasm.core.StampPolicy.Verdict.STALE;
import static dev.micolash.jasm.core.StampPolicy.Verdict.TAMPERED;
import static dev.micolash.jasm.core.StampPolicy.Verdict.UNFORMATTED;
import static dev.micolash.jasm.core.StampPolicy.Verdict.UNREADABLE;
import static dev.micolash.jasm.core.StampPolicy.Verdict.VALID;
import static dev.micolash.jasm.core.StampPolicy.Verdict.VALID_AHEAD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.micolash.jasm.core.StampPolicy.RecordView;
import org.junit.jupiter.api.Test;

class StampPolicyTest {
    private static final int CAP = 1024;
    private static final Stamp FLOOR = new Stamp(2, 5);
    private static final Stamp CURRENT = new Stamp(3, 7);
    private static final RecordView RECORD = new RecordView(CURRENT, FLOOR, CAP, true);

    @Test
    void noIdentityIsUnformatted() {
        assertEquals(UNFORMATTED, StampPolicy.judge(null, CAP, RECORD));
    }

    @Test
    void unknownRecordIsOrphan() {
        assertEquals(ORPHAN, StampPolicy.judge(CURRENT, CAP, null));
    }

    @Test
    void capacityMismatchIsTampered() {
        assertEquals(TAMPERED, StampPolicy.judge(CURRENT, 4096, RECORD));
    }

    @Test
    void belowFloorIsRecoveredOriginal() {
        assertEquals(RECOVERED_ORIGINAL, StampPolicy.judge(new Stamp(2, 4), CAP, RECORD));
        assertEquals(RECOVERED_ORIGINAL, StampPolicy.judge(new Stamp(1, 900), CAP, RECORD));
    }

    @Test
    void belowFloorWinsOverCapacityMismatch() {
        // Recovered onto a larger tier: the old, smaller original must still dissolve.
        assertEquals(RECOVERED_ORIGINAL, StampPolicy.judge(new Stamp(2, 4), 256, RECORD));
    }

    @Test
    void exactlyFloorButBelowCurrentIsDuplicate() {
        assertEquals(DUPLICATE, StampPolicy.judge(FLOOR, CAP, RECORD));
    }

    @Test
    void betweenFloorAndCurrentIsDuplicate() {
        assertEquals(DUPLICATE, StampPolicy.judge(new Stamp(3, 6), CAP, RECORD));
    }

    @Test
    void noFloorAndOlderIsDuplicate() {
        RecordView neverRecovered = new RecordView(CURRENT, null, CAP, true);
        assertEquals(DUPLICATE, StampPolicy.judge(new Stamp(1, 1), CAP, neverRecovered));
    }

    @Test
    void olderCopyIsOnlyLockedWhileTheNewestHasNotShownUp() {
        RecordView afterRestart = new RecordView(CURRENT, FLOOR, CAP, false);
        assertEquals(STALE, StampPolicy.judge(new Stamp(3, 6), CAP, afterRestart));
        assertEquals(VALID, StampPolicy.judge(CURRENT, CAP, afterRestart));
        assertEquals(RECOVERED_ORIGINAL, StampPolicy.judge(new Stamp(2, 4), CAP, afterRestart), "recovery floor still applies");
    }

    @Test
    void equalIsValidAndNewerIsAhead() {
        assertEquals(VALID, StampPolicy.judge(CURRENT, CAP, RECORD));
        assertEquals(VALID_AHEAD, StampPolicy.judge(new Stamp(3, 8), CAP, RECORD));
    }

    @Test
    void onlyValidVerdictsGrantAccess() {
        assertTrue(VALID.grantsAccess());
        assertTrue(VALID_AHEAD.grantsAccess());
        for (StampPolicy.Verdict v : new StampPolicy.Verdict[] {UNFORMATTED, ORPHAN, UNREADABLE, TAMPERED, RECOVERED_ORIGINAL, DUPLICATE, STALE}) {
            assertFalse(v.grantsAccess(), v.name());
        }
    }
}
