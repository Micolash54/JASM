package dev.micolash.jasm.core;

/** The wafer validation decision table. Pure: callers apply the effects. */
public final class StampPolicy {
    public enum Verdict {
        /** No identity: a blank wafer. */
        UNFORMATTED,
        /** Identity names a record the ledger does not know. */
        ORPHAN,
        /** Item capacity disagrees with the record. */
        TAMPERED,
        /** Older than the last recovery: the lost original. */
        RECOVERED_ORIGINAL,
        /** Matches the ledger. */
        VALID,
        /** Newer than the ledger (item saved, ledger lost): valid after fast-forward. */
        VALID_AHEAD,
        /** Older than the ledger but not a recovered original: a copy. */
        DUPLICATE;

        public boolean grantsAccess() {
            return this == VALID || this == VALID_AHEAD;
        }
    }

    /** What the ledger knows about a wafer. {@code recoveryFloor} may be null. */
    public record RecordView(Stamp current, Stamp recoveryFloor, int capacity) {}

    private StampPolicy() {}

    /**
     * @param itemStamp    the stamp on the item, or null if the item has no identity
     * @param itemCapacity the capacity of the physical item
     * @param record       the ledger view, or null if no record exists
     */
    public static Verdict judge(Stamp itemStamp, int itemCapacity, RecordView record) {
        if (itemStamp == null) {
            return Verdict.UNFORMATTED;
        }
        if (record == null) {
            return Verdict.ORPHAN;
        }
        // Floor before capacity: recovery may move a record onto a larger wafer tier, and the lost original
        // (old tier) must still dissolve rather than be blanked.
        if (record.recoveryFloor() != null && itemStamp.isBefore(record.recoveryFloor())) {
            return Verdict.RECOVERED_ORIGINAL;
        }
        if (record.capacity() != itemCapacity) {
            return Verdict.TAMPERED;
        }
        int cmp = itemStamp.compareTo(record.current());
        if (cmp > 0) {
            return Verdict.VALID_AHEAD;
        }
        return cmp == 0 ? Verdict.VALID : Verdict.DUPLICATE;
    }
}
