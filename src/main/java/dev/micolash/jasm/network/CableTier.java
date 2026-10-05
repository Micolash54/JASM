package dev.micolash.jasm.network;

import dev.micolash.jasm.config.JasmConfig;

/** Data Cable tiers. Each cable passes power on at its own rate; between two cables, the slower one sets it. */
public enum CableTier {
    BASIC("data_cable"),
    ADVANCED("advanced_data_cable"),
    ELITE("elite_data_cable");

    private final String registryName;

    CableTier(String registryName) {
        this.registryName = registryName;
    }

    public String registryName() {
        return registryName;
    }

    /** FE per tick this cable moves into or out of each block its network touches. */
    public int rate() {
        return switch (this) {
            case BASIC -> JasmConfig.orDefault(JasmConfig.CABLE_RATE);
            case ADVANCED -> JasmConfig.orDefault(JasmConfig.ADVANCED_CABLE_RATE);
            case ELITE -> JasmConfig.orDefault(JasmConfig.ELITE_CABLE_RATE);
        };
    }

    /** FE this cable holds while passing it on: ten ticks of its rate. */
    public int buffer() {
        return (int) Math.min(Integer.MAX_VALUE, 10L * rate());
    }
}
