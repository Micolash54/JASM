package dev.micolash.jasm.wafer;

import java.util.Optional;

/**
 * Wafer tiers. Fluid wafers have the same two lines, sized in buckets and with fewer types. Capacity Wafers hold any mix of items up to a total. Type Wafers hold a few types, each up to a
 * fixed amount; what counts as one type follows vanilla stacking (see {@link TypeRules}). Values are fixed: they
 * appear in names and registry IDs.
 */
public enum WaferTier {
    BASIC("capacity_wafer_basic", 256, 0, 0),
    K1("capacity_wafer_1k", 1_024, 0, 0),
    K4("capacity_wafer_4k", 4_096, 0, 0),
    K16("capacity_wafer_16k", 16_384, 0, 0),
    K64("capacity_wafer_64k", 65_536, 0, 0),
    T4("type_wafer_4", 4 * 2_048, 4, 2_048),
    T8("type_wafer_8", 8 * 8_192, 8, 8_192),
    T16("type_wafer_16", 16 * 32_768, 16, 32_768),
    T32("type_wafer_32", 32 * 131_072, 32, 131_072),
    T64("type_wafer_64", 64 * 524_288, 64, 524_288),
    FLUID_BASIC(WaferKind.FLUID, "fluid_capacity_wafer_basic", 256, 0, 0),
    FLUID_K1(WaferKind.FLUID, "fluid_capacity_wafer_1k", 1_024, 0, 0),
    FLUID_K4(WaferKind.FLUID, "fluid_capacity_wafer_4k", 4_096, 0, 0),
    FLUID_K16(WaferKind.FLUID, "fluid_capacity_wafer_16k", 16_384, 0, 0),
    FLUID_K64(WaferKind.FLUID, "fluid_capacity_wafer_64k", 65_536, 0, 0),
    FLUID_T1(WaferKind.FLUID, "fluid_type_wafer_1", 8_192, 1, 8_192),
    FLUID_T2(WaferKind.FLUID, "fluid_type_wafer_2", 2 * 32_768, 2, 32_768),
    FLUID_T4(WaferKind.FLUID, "fluid_type_wafer_4", 4 * 131_072, 4, 131_072),
    FLUID_T8(WaferKind.FLUID, "fluid_type_wafer_8", 8 * 524_288, 8, 524_288),
    FLUID_T16(WaferKind.FLUID, "fluid_type_wafer_16", 16 * 2_097_152, 16, 2_097_152);

    private final WaferKind kind;
    private final String registryName;
    private final int capacity;
    private final int types;
    private final int perType;

    WaferTier(String registryName, int capacity, int types, int perType) {
        this(WaferKind.ITEM, registryName, capacity, types, perType);
    }

    WaferTier(WaferKind kind, String registryName, int capacity, int types, int perType) {
        this.kind = kind;
        this.registryName = registryName;
        this.capacity = capacity;
        this.types = types;
        this.perType = perType;
    }

    public WaferKind kind() {
        return kind;
    }

    /** Total room in the wafer's own unit: items, or millibuckets on a fluid wafer. */
    public long capacityAmount() {
        return isFluid() ? FluidAmounts.roomMb(capacity) : capacity;
    }

    /** Most of one type in the wafer's own unit, or 0 for a Capacity Wafer. */
    public long perTypeAmount() {
        return isFluid() ? FluidAmounts.roomMb(perType) : perType;
    }

    public boolean isFluid() {
        return kind == WaferKind.FLUID;
    }

    public String registryName() {
        return registryName;
    }

    /** Most items in total; on a fluid wafer, most buckets. */
    public int capacity() {
        return capacity;
    }

    /** Most types, or 0 for a Capacity Wafer (any number of types). */
    public int types() {
        return types;
    }

    /** Most items of one type, or 0 for a Capacity Wafer. */
    public int perType() {
        return perType;
    }

    public boolean isTyped() {
        return types > 0;
    }

    public static Optional<WaferTier> byLimits(WaferKind kind, int capacity, int types) {
        for (WaferTier tier : values()) {
            if (tier.kind == kind && tier.capacity == capacity && tier.types == types) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
