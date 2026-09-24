package dev.micolash.jasm.core;

/**
 * Identifies one physical wafer instance generation. Ordered first by server-start epoch, then by counter,
 * so a stamp minted in a later epoch is always newer than any stamp from an earlier one.
 */
public record Stamp(long epoch, long counter) implements Comparable<Stamp> {
    @Override
    public int compareTo(Stamp other) {
        int byEpoch = Long.compare(epoch, other.epoch);
        return byEpoch != 0 ? byEpoch : Long.compare(counter, other.counter);
    }

    public boolean isBefore(Stamp other) {
        return compareTo(other) < 0;
    }

    public boolean isAfter(Stamp other) {
        return compareTo(other) > 0;
    }
}
