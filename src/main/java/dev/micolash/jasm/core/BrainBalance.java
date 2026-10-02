package dev.micolash.jasm.core;

import dev.micolash.jasm.config.JasmConfig;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The brain numbers. In game they come from the server config; a list of the wrong length falls back to its default. */
public record BrainBalance(int limitWithoutBrain, List<Integer> machineLimits, List<Integer> levelPoints, List<Integer> levelCaps,
        List<Integer> learnSpeed, List<Integer> drainPerTick, int chipBonus, int advancedChipBonus, int eatEvery) {
    public static final int MAX_LEVEL = 10;

    public static BrainBalance defaults() {
        return new BrainBalance(4, List.of(8, 12, 16, 24, 32, 48, 64, 96, 128, 256),
                List.of(72_000, 108_000, 144_000, 216_000, 288_000, 432_000, 576_000, 864_000, 1_152_000),
                List.of(3, 6, 10), List.of(1, 2, 3), List.of(8, 16, 32), 2, 10, 10);
    }

    private static volatile @Nullable BrainBalance cached;
    /** Goes up each time the config changes, so numbers read before a change are never kept after it. */
    private static int version;

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

    private static BrainBalance read() {
        BrainBalance d = defaults();
        return new BrainBalance(JasmConfig.BRAIN_LIMIT_WITHOUT_BRAIN.getAsInt(),
                sized(JasmConfig.BRAIN_MACHINE_LIMITS.get(), d.machineLimits()),
                sized(JasmConfig.BRAIN_LEVEL_POINTS.get(), d.levelPoints()),
                sized(JasmConfig.BRAIN_LEVEL_CAPS.get(), d.levelCaps()),
                sized(JasmConfig.BRAIN_LEARN_SPEED.get(), d.learnSpeed()),
                sized(JasmConfig.BRAIN_DRAIN.get(), d.drainPerTick()),
                JasmConfig.BRAIN_CHIP_BONUS.getAsInt(), JasmConfig.BRAIN_ADVANCED_CHIP_BONUS.getAsInt(),
                JasmConfig.BRAIN_EAT_EVERY.getAsInt());
    }

    private static List<Integer> sized(List<? extends Integer> configured, List<Integer> fallback) {
        return configured.size() == fallback.size() ? List.copyOf(configured) : fallback;
    }
}
