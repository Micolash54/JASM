package dev.micolash.jasm.wafer;

import java.util.Optional;

/** Capacity Wafer tiers. Capacities are fixed: they appear in names and registry IDs. */
public enum WaferTier {
    BASIC("capacity_wafer_basic", 256),
    K1("capacity_wafer_1k", 1_024),
    K4("capacity_wafer_4k", 4_096),
    K16("capacity_wafer_16k", 16_384),
    K64("capacity_wafer_64k", 65_536);

    private final String registryName;
    private final int capacity;

    WaferTier(String registryName, int capacity) {
        this.registryName = registryName;
        this.capacity = capacity;
    }

    public String registryName() {
        return registryName;
    }

    public int capacity() {
        return capacity;
    }

    public static Optional<WaferTier> byCapacity(int capacity) {
        for (WaferTier tier : values()) {
            if (tier.capacity == capacity) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
