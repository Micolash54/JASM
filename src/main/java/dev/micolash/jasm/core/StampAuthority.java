package dev.micolash.jasm.core;

/** Mints unique, increasing stamps. The epoch must be advanced (and persisted) once per server start. */
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

    public void beginEpoch() {
        epoch++;
        counter = 0;
    }

    public Stamp mint() {
        return new Stamp(epoch, ++counter);
    }

    /** Keeps future mints unique if a stamp from the current epoch is observed beyond our counter. */
    public void observe(Stamp stamp) {
        if (stamp.epoch() == epoch && stamp.counter() > counter) {
            counter = stamp.counter();
        }
    }
}
