package dev.micolash.jasm.core;

import dev.micolash.jasm.config.JasmConfig;
import org.jspecify.annotations.Nullable;

/** The brain numbers. In game they come from the server config. */
public record BrainBalance(int limitWithoutBrain, int machineLimit, int floorBonus, int maxFloors, int drainPerFloor) {
    public static BrainBalance defaults() {
        return new BrainBalance(4, 12, 12, 8, 8);
    }

    private static volatile @Nullable BrainBalance cached;
    /** Goes up each time the config changes, so numbers read before a change are never kept after it. */
    private static volatile int version;

    /** Machines a network may hold: without a working brain, or with a brain whose tower has this many floors. */
    public int limit(boolean brain, int floors) {
        if (!brain) {
            return limitWithoutBrain;
        }
        return machineLimit + Math.clamp(floors, 0, Math.max(1, maxFloors)) * floorBonus;
    }

    /** The config's numbers, read once and kept until the config loads or changes again. */
    public static BrainBalance fromConfig() {
        BrainBalance balance = cached;
        if (balance == null) {
            if (!JasmConfig.SPEC.isLoaded()) {
                return defaults();
            }
            int seen;
            synchronized (BrainBalance.class) {
                seen = version;
            }
            balance = read();
            synchronized (BrainBalance.class) {
                if (version == seen) {
                    cached = balance;
                }
            }
        }
        return balance;
    }

    /** Drops the kept numbers, so the next {@link #fromConfig()} reads the config again. */
    public static void forget() {
        synchronized (BrainBalance.class) {
            version++;
            cached = null;
        }
    }

    /** Changes whenever the config does, so a brain can tell when to stack its tower again. */
    public static int stamp() {
        return version;
    }

    private static BrainBalance read() {
        return new BrainBalance(JasmConfig.BRAIN_LIMIT_WITHOUT_BRAIN.getAsInt(), JasmConfig.BRAIN_MACHINE_LIMIT.getAsInt(),
                JasmConfig.BRAIN_FLOOR_BONUS.getAsInt(), JasmConfig.BRAIN_MAX_FLOORS.getAsInt(), JasmConfig.BRAIN_DRAIN_PER_FLOOR.getAsInt());
    }
}
