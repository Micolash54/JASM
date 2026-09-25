package dev.micolash.jasm.generator;

/**
 * Combustion Generator tiers. Each tier burns fuel faster ({@code burnSpeed}: a fuel item lasts its furnace burn
 * time divided by this) and makes more FE from it. Coal: Basic 32,000 FE over 40 s, Advanced 64,000 over 20 s,
 * Elite 128,000 over 10 s.
 */
public enum GeneratorTier {
    BASIC("basic_combustion_generator", 2, 40, 100_000, 1_000),
    ADVANCED("advanced_combustion_generator", 4, 160, 400_000, 2_500),
    ELITE("elite_combustion_generator", 8, 640, 1_000_000, 5_000);

    private final String registryName;
    private final int burnSpeed;
    private final int fePerTick;
    private final int capacity;
    private final int transferPerTick;

    GeneratorTier(String registryName, int burnSpeed, int fePerTick, int capacity, int transferPerTick) {
        this.registryName = registryName;
        this.burnSpeed = burnSpeed;
        this.fePerTick = fePerTick;
        this.capacity = capacity;
        this.transferPerTick = transferPerTick;
    }

    public String registryName() {
        return registryName;
    }

    /** How many times faster than a furnace fuel burns. */
    public int burnSpeed() {
        return burnSpeed;
    }

    /** FE made each tick while burning. */
    public int fePerTick() {
        return fePerTick;
    }

    /** FE the buffer holds. */
    public int capacity() {
        return capacity;
    }

    /** FE per tick into each touching block, and into the charging slot. */
    public int transferPerTick() {
        return transferPerTick;
    }

    /** Ticks a fuel item burns for, from its furnace burn time. Anything a furnace burns lasts at least a tick. */
    public int burnTicks(int furnaceTicks) {
        return furnaceTicks <= 0 ? 0 : Math.max(1, furnaceTicks / burnSpeed);
    }
}
