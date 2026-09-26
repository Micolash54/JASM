package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import java.util.function.IntSupplier;

/** Processor tiers: how many crafts one runs at once, each made from 4 of the tier below. */
public enum ProcessorTier {
    BASIC("basic_processor", 1, JasmConfig.PROCESSOR_DRAIN_BASIC::getAsInt),
    ADVANCED("advanced_processor", 4, JasmConfig.PROCESSOR_DRAIN_ADVANCED::getAsInt),
    ELITE("elite_processor", 16, JasmConfig.PROCESSOR_DRAIN_ELITE::getAsInt);

    private final String registryName;
    private final int crafts;
    private final IntSupplier drain;

    ProcessorTier(String registryName, int crafts, IntSupplier drain) {
        this.registryName = registryName;
        this.crafts = crafts;
        this.drain = drain;
    }

    public String registryName() {
        return registryName;
    }

    /** Crafts this Processor runs at the same time. */
    public int crafts() {
        return crafts;
    }

    /** FE it adds to its server's drain each tick. */
    public int drainPerTick() {
        return drain.getAsInt();
    }
}
