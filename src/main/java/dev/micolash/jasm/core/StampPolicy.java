package dev.micolash.jasm.core;

/** The wafer validation decision table. Pure: callers apply the effects. */
public final class StampPolicy {
    public enum Verdict {
        /** No identity: a blank wafer. */
        UNFORMATTED,
        /** Identity names a record that does not exist. No access; the identity is kept. */
        ORPHAN,
        /** The record exists but cannot be read right now (for example its data needs a missing mod). No access; the identity is kept. */
        UNREADABLE,
        /** Item capacity disagrees with the record. No access; the identity is kept. */
        TAMPERED,
        /** Older than the last recovery: the lost original. */
        RECOVERED_ORIGINAL,
        /** Matches the record. */
        VALID,
        /** Newer than the record (the item was saved, the record was not): valid after fast-forward. */
        VALID_AHEAD,
        /** Older than the record while the newest copy is known to exist: a copy. */
        DUPLICATE,
        /**
         * Older than the record, but the newest copy has not shown up since the server started. After a crash
         * this can be the only surviving copy, so it is locked rather than wiped. No access; the identity is kept.
         */
        STALE;

        public boolean grantsAccess() {
            return this == VALID || this == VALID_AHEAD;
        }
    }

    /**
     * What the record knows about a wafer. {@code recoveryFloor} may be null. {@code newestSeen} is true once a
     * copy with the {@code current} stamp was handed out, seen or saved during this server session.
     */
    public record RecordView(Stamp current, Stamp recoveryFloor, long capacity, boolean newestSeen) {}

    private StampPolicy() {}

    /**
     * @param itemStamp    the stamp on the item, or null if the item has no identity
     * @param itemCapacity the capacity of the physical item
     * @param record       the record view, or null if no record exists
     */
    public static Verdict judge(Stamp itemStamp, long itemCapacity, RecordView record) {
        if (itemStamp == null) {
            return Verdict.UNFORMATTED;
        }
        if (record == null) {
            return Verdict.ORPHAN;
        }
        // Floor before capacity: recovery may move a record onto a larger wafer tier, and the lost original
        // (old tier) must still dissolve rather than be locked.
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
        if (cmp == 0) {
            return Verdict.VALID;
        }
        return record.newestSeen() ? Verdict.DUPLICATE : Verdict.STALE;
    }
}
