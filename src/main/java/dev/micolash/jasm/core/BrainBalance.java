package dev.micolash.jasm.core;

import dev.micolash.jasm.config.JasmConfig;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The brain numbers. In game they come from the server config. Each list has one entry per size of brain: a lone brain
 * first, then a tower of 1 floor, 2 floors and so on. A tower taller than the list uses its last entry.
 */
public record BrainBalance(int limitWithoutBrain, int maxFloors, List<Integer> machines, List<Integer> drains, List<Integer> pools,
        boolean noLimit, double multiplier) {
    /** The limit with the no-limit switch on. */
    public static final int UNLIMITED = Integer.MAX_VALUE;
    public static final List<Integer> DEFAULT_MACHINES = List.of(12, 36, 54, 80, 120, 175, 260, 385, 576);
    public static final List<Integer> DEFAULT_DRAINS = List.of(8, 24, 52, 110, 240, 510, 1_100, 2_300, 5_000);
    public static final List<Integer> DEFAULT_POOLS = List.of(50_000, 145_000, 310_000, 660_000, 1_450_000, 3_050_000, 6_600_000, 13_800_000, 30_000_000);

    public BrainBalance(int limitWithoutBrain, int maxFloors, List<Integer> machines, List<Integer> drains, List<Integer> pools) {
        this(limitWithoutBrain, maxFloors, machines, drains, pools, false, 1.0);
    }

    public static BrainBalance defaults() {
        return new BrainBalance(4, 8, DEFAULT_MACHINES, DEFAULT_DRAINS, DEFAULT_POOLS);
    }

    private static volatile @Nullable BrainBalance cached;
    /** Goes up each time the config changes, so numbers read before a change are never kept after it. */
    private static volatile int version;

    /** Machines a network may hold: without a working brain, or with a brain whose tower has this many floors. */
    public int limit(boolean brain, int floors) {
        if (noLimit) {
            return UNLIMITED;
        }
        int base = brain ? at(machines, floors) : limitWithoutBrain;
        if (base == 0) {
            return 0;
        }
        return (int) Math.clamp((long) Math.floor(base * multiplier), 1L, UNLIMITED - 1L);
    }

    /** A limit as players read it: the number, or an infinity sign with no limit. */
    public static String shown(int limit) {
        return limit == UNLIMITED ? "∞" : String.format("%,d", limit);
    }

    /** FE a whole tower of this many floors uses every tick; 0 floors is a lone brain. */
    public int drain(int floors) {
        return at(drains, floors);
    }

    /** FE a whole tower of this many floors holds. */
    public int pool(int floors) {
        return at(pools, floors);
    }

    private int at(List<Integer> values, int floors) {
        int size = Math.clamp(floors, 0, Math.max(1, maxFloors));
        return values.get(Math.min(size, values.size() - 1));
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
        return new BrainBalance(JasmConfig.BRAIN_LIMIT_WITHOUT_BRAIN.getAsInt(), JasmConfig.BRAIN_MAX_FLOORS.getAsInt(),
                list(JasmConfig.BRAIN_MACHINES.get(), DEFAULT_MACHINES), list(JasmConfig.BRAIN_DRAINS.get(), DEFAULT_DRAINS),
                list(JasmConfig.BRAIN_POOLS.get(), DEFAULT_POOLS), JasmConfig.BRAIN_NO_LIMIT.get(), JasmConfig.BRAIN_MULTIPLIER.get());
    }

    private static List<Integer> list(List<? extends Integer> values, List<Integer> fallback) {
        return values.isEmpty() ? fallback : List.copyOf(values);
    }
}
