package dev.micolash.jasm.core;

/**
 * Mints unique, increasing stamps. A new epoch starts (and is saved) on every server start. Epochs follow the
 * wall clock, so restoring an older copy of the saved data can never bring back an epoch that was already used.
 */
public final class StampAuthority {
    private long epoch;
    private long counter;

    public StampAuthority(long epoch, long counter) {
        this.epoch = epoch;
        this.counter = counter;
    }

    public long epoch() {
        return epoch;
    }

    public long counter() {
        return counter;
    }

    /** @param clock the current time in milliseconds; the epoch still advances if the clock went backwards */
    public void beginEpoch(long clock) {
        epoch = Math.max(epoch + 1, clock);
        counter = 0;
    }

    public Stamp mint() {
        return new Stamp(epoch, ++counter);
    }

    /**
     * Called when a stamp shows up that this authority did not expect to exist yet (the saved data is older than
     * the item). Moves to a fresh epoch past it, so no later mint can equal any stamp handed out alongside it.
     */
    public void observe(Stamp stamp) {
        if (stamp.isAfter(new Stamp(epoch, counter))) {
            epoch = stamp.epoch() + 1;
            counter = 0;
        }
    }
}
