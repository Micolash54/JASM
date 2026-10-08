package dev.micolash.jasm.network;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.DelegatingResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A machine's I/O grid: for each of its six faces, what hoppers, pipes and ports may do through it, once for items and,
 * on machines with a tank, once for fluids. Every face starts at None. The screen's face buttons step through the modes
 * this machine offers.
 */
public final class MachineSides {
    /** The full cycle, in click order. */
    public static final List<FaceMode> ALL = List.of(FaceMode.INPUT, FaceMode.OUTPUT, FaceMode.BOTH, FaceMode.NONE);
    /** For a machine that only takes things in. */
    public static final List<FaceMode> IN_ONLY = List.of(FaceMode.INPUT, FaceMode.NONE);
    /** For a machine that only gives its results out. */
    public static final List<FaceMode> OUT_ONLY = List.of(FaceMode.OUTPUT, FaceMode.NONE);
    /** Menu button ids: one per face, the items' six first, then the fluids'. */
    public static final int BUTTON_FIRST = 100;
    private static final int FACES = MachineFace.values().length;

    public enum Kind {
        ITEMS("io_items"),
        FLUIDS("io_fluids");

        private final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    private final List<FaceMode> offered;
    private final boolean fluids;
    private final Runnable changed;
    private final FaceMode[][] modes = new FaceMode[Kind.values().length][FACES];
    private boolean anyOut;

    /** {@code changed} hears about every change a player makes. */
    public MachineSides(List<FaceMode> offered, boolean fluids, Runnable changed) {
        this.offered = offered;
        this.fluids = fluids;
        this.changed = changed;
        clear();
    }

    private void clear() {
        for (FaceMode[] kind : modes) Arrays.fill(kind, FaceMode.NONE);
        anyOut = false;
    }

    public boolean hasFluids() {
        return fluids;
    }

    public FaceMode mode(Kind kind, MachineFace face) {
        return modes[kind.ordinal()][face.ordinal()];
    }

    /** Sets a face, if this machine offers that mode there. */
    public void set(Kind kind, MachineFace face, FaceMode mode) {
        if (!offered.contains(mode) || kind == Kind.FLUIDS && !fluids || mode(kind, face) == mode) return;
        modes[kind.ordinal()][face.ordinal()] = mode;
        refreshOut();
        changed.run();
    }

    /** Whether any face sends results out, so the machine has pushing to do. */
    public boolean anyOut() {
        return anyOut;
    }

    private void refreshOut() {
        anyOut = false;
        for (FaceMode[] kind : modes) for (FaceMode mode : kind) anyOut |= mode.out();
    }

    public static int button(Kind kind, MachineFace face) {
        return BUTTON_FIRST + kind.ordinal() * FACES + face.ordinal();
    }

    /** A face button pressed in the screen: that face steps to the next mode. False if {@code id} isn't one of ours. */
    public boolean click(int id) {
        int index = id - BUTTON_FIRST;
        if (index < 0 || index >= Kind.values().length * FACES) return false;
        Kind kind = Kind.values()[index / FACES];
        if (kind == Kind.FLUIDS && !fluids) return false;
        MachineFace face = MachineFace.byId(index % FACES);
        int at = offered.indexOf(mode(kind, face));
        set(kind, face, offered.get((at + 1) % offered.size()));
        return true;
    }

    /** Two bits a face, for the screen; fits the 16 bits a menu value carries. */
    public int packed(Kind kind) {
        int packed = 0;
        for (MachineFace face : MachineFace.values()) packed |= mode(kind, face).ordinal() << face.ordinal() * 2;
        return packed;
    }

    public static FaceMode unpack(int packed, MachineFace face) {
        return FaceMode.byId(packed >> face.ordinal() * 2 & 3);
    }

    public void save(ValueOutput output) {
        output.putInt(Kind.ITEMS.key, packed(Kind.ITEMS));
        if (fluids) output.putInt(Kind.FLUIDS.key, packed(Kind.FLUIDS));
    }

    /** Older machines saved none: every face stays at None. */
    public void load(ValueInput input) {
        clear();
        for (Kind kind : Kind.values()) {
            if (kind == Kind.FLUIDS && !fluids) continue;
            int packed = input.getIntOr(kind.key, 0);
            for (MachineFace face : MachineFace.values()) {
                FaceMode mode = unpack(packed, face);
                modes[kind.ordinal()][face.ordinal()] = offered.contains(mode) ? mode : FaceMode.NONE;
            }
        }
        refreshOut();
    }

    /** One handler seen three ways: in only, out only, or both. None shows nothing at all. */
    public static final class Gated<T extends Resource> {
        private final ResourceHandler<T> both;
        private final ResourceHandler<T> in;
        private final ResourceHandler<T> out;

        public Gated(ResourceHandler<T> handler) {
            both = handler;
            in = new DelegatingResourceHandler<>(handler) {
                @Override
                public int extract(int index, T resource, int amount, TransactionContext transaction) {
                    return 0;
                }

                @Override
                public int extract(T resource, int amount, TransactionContext transaction) {
                    return 0;
                }
            };
            out = new DelegatingResourceHandler<>(handler) {
                @Override
                public int insert(int index, T resource, int amount, TransactionContext transaction) {
                    return 0;
                }

                @Override
                public int insert(T resource, int amount, TransactionContext transaction) {
                    return 0;
                }
            };
        }

        public @Nullable ResourceHandler<T> through(FaceMode mode) {
            return switch (mode) {
                case NONE -> null;
                case INPUT -> in;
                case OUTPUT -> out;
                case BOTH -> both;
            };
        }
    }
}
