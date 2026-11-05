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
 * on machines with a tank, once for fluids. A newly placed machine starts every face at the mode it is given (Input on
 * the Workshop and Foundry and, apart from its front, the Deployment Bay; None on the Demolition Bay). The screen's face buttons step through the modes this machine offers.
 * Machines with an Auto Output switch only push their results out on their own while it is on; the bays always do.
 * Machines that take power straight from other mods' cables (the bays) also have an energy face on each side, Input or
 * None, Input on every side of a new one.
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
    /** Menu button id of the Auto Output switch, just after the items' and fluids' face buttons. */
    public static final int AUTO_OUTPUT_BUTTON = BUTTON_FIRST + 2 * FACES;
    /** Menu button ids of the energy faces, after the Auto Output switch. */
    private static final int ENERGY_BUTTON_FIRST = AUTO_OUTPUT_BUTTON + 1;
    /** Where the energy faces sit in what players' games are told: one bit a face, set for Input, after items and fluids. */
    private static final int ENERGY_SHIFT = 4 * FACES;
    private static final String AUTO_OUTPUT = "auto_output";

    public enum Kind {
        ITEMS("io_items"),
        FLUIDS("io_fluids"),
        ENERGY("io_energy");

        private final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /** Hears about every change a player makes; {@code faces} is false when only the Auto Output switch moved. */
    @FunctionalInterface
    public interface Listener {
        void changed(boolean faces);
    }

    private final List<FaceMode> offered;
    private final boolean fluids;
    private final boolean energy;
    private final Listener changed;
    private final FaceMode[][] modes = new FaceMode[Kind.values().length][FACES];
    private final boolean switchable;
    private boolean anyOut;
    private boolean autoOutput;

    /**
     * Every face starts at {@code start}, if offered, else None. With {@code switchable}, the machine has an Auto Output
     * switch, off on a new machine. {@code changed} hears about every change a player makes.
     */
    public MachineSides(List<FaceMode> offered, boolean fluids, FaceMode start, boolean switchable, Listener changed) {
        this(offered, fluids, false, start, switchable, changed);
    }

    /** As above; with {@code energy}, the machine also has energy faces, each Input or None, all Input to start with. */
    public MachineSides(List<FaceMode> offered, boolean fluids, boolean energy, FaceMode start, boolean switchable, Listener changed) {
        this.offered = offered;
        this.fluids = fluids;
        this.energy = energy;
        this.switchable = switchable;
        this.changed = changed;
        fill(offered.contains(start) ? start : FaceMode.NONE);
    }

    /** The modes a face of this kind steps through. */
    private List<FaceMode> offered(Kind kind) {
        return kind == Kind.ENERGY ? IN_ONLY : offered;
    }

    /** Whether this machine has faces of that kind at all. */
    public boolean has(Kind kind) {
        return switch (kind) {
            case ITEMS -> true;
            case FLUIDS -> fluids;
            case ENERGY -> energy;
        };
    }

    /**
     * Sets one item and fluid face on a machine that is just being built, if offered, without telling anyone. Energy
     * faces stay as they start.
     */
    public void preset(MachineFace face, FaceMode mode) {
        if (!offered.contains(mode)) return;
        for (Kind kind : Kind.values()) {
            if (kind == Kind.ENERGY || !has(kind)) continue;
            modes[kind.ordinal()][face.ordinal()] = mode;
        }
        refreshOut();
    }

    public boolean hasAutoOutputSwitch() {
        return switchable;
    }

    /** Whether the machine pushes its results out of its Output sides on its own. Without a switch it always does. */
    public boolean autoOutput() {
        return !switchable || autoOutput;
    }

    public void setAutoOutput(boolean on) {
        if (!switchable || autoOutput == on) return;
        autoOutput = on;
        changed.changed(false);
    }

    /**
     * Every item and fluid face this machine has set to {@code mode}; fluid faces stay None on a machine without a tank.
     * Energy faces are Input on a machine that has them.
     */
    private void fill(FaceMode mode) {
        for (Kind kind : Kind.values()) {
            FaceMode set = !has(kind) ? FaceMode.NONE : kind == Kind.ENERGY ? FaceMode.INPUT : mode;
            Arrays.fill(modes[kind.ordinal()], set);
        }
        refreshOut();
    }

    public boolean hasFluids() {
        return fluids;
    }

    public boolean hasEnergy() {
        return energy;
    }

    public FaceMode mode(Kind kind, MachineFace face) {
        return modes[kind.ordinal()][face.ordinal()];
    }

    /** Sets a face, if this machine offers that mode there. */
    public void set(Kind kind, MachineFace face, FaceMode mode) {
        if (!offered(kind).contains(mode) || !has(kind) || mode(kind, face) == mode) return;
        modes[kind.ordinal()][face.ordinal()] = mode;
        refreshOut();
        changed.changed(true);
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
        return kind == Kind.ENERGY ? ENERGY_BUTTON_FIRST + face.ordinal() : BUTTON_FIRST + kind.ordinal() * FACES + face.ordinal();
    }

    /** A face button pressed in the screen: that face steps to the next mode. False if {@code id} isn't one of ours. */
    public boolean click(int id) {
        if (id == AUTO_OUTPUT_BUTTON) {
            if (!switchable) return false;
            setAutoOutput(!autoOutput);
            return true;
        }
        Kind kind;
        int index;
        if (id >= ENERGY_BUTTON_FIRST && id < ENERGY_BUTTON_FIRST + FACES) {
            kind = Kind.ENERGY;
            index = id - ENERGY_BUTTON_FIRST;
        } else {
            index = id - BUTTON_FIRST;
            if (index < 0 || index >= 2 * FACES) return false;
            kind = Kind.values()[index / FACES];
        }
        if (!has(kind)) return false;
        MachineFace face = MachineFace.byId(index % FACES);
        List<FaceMode> steps = offered(kind);
        int at = steps.indexOf(mode(kind, face));
        set(kind, face, steps.get((at + 1) % steps.size()));
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

    /**
     * Every face as players' games are told it: items in the low 12 bits, fluids in the next 12, then one bit an energy
     * face, set for Input.
     */
    public int synced() {
        int synced = packed(Kind.ITEMS) | (fluids ? packed(Kind.FLUIDS) << 2 * FACES : 0);
        if (energy) {
            for (MachineFace face : MachineFace.values()) {
                if (mode(Kind.ENERGY, face).in()) synced |= 1 << ENERGY_SHIFT + face.ordinal();
            }
        }
        return synced;
    }

    /** Takes the faces the server sent, on a player's game. Whether any face changed. */
    public boolean applySynced(int synced) {
        boolean moved = false;
        for (Kind kind : Kind.values()) {
            if (!has(kind)) continue;
            int packed = synced >> kind.ordinal() * 2 * FACES;
            for (MachineFace face : MachineFace.values()) {
                FaceMode mode = kind != Kind.ENERGY ? unpack(packed, face)
                        : (synced >> ENERGY_SHIFT + face.ordinal() & 1) != 0 ? FaceMode.INPUT : FaceMode.NONE;
                if (!offered(kind).contains(mode)) mode = FaceMode.NONE;
                moved |= modes[kind.ordinal()][face.ordinal()] != mode;
                modes[kind.ordinal()][face.ordinal()] = mode;
            }
        }
        if (moved) refreshOut();
        return moved;
    }

    public void save(ValueOutput output) {
        output.putInt(Kind.ITEMS.key, packed(Kind.ITEMS));
        if (fluids) output.putInt(Kind.FLUIDS.key, packed(Kind.FLUIDS));
        if (energy) output.putInt(Kind.ENERGY.key, packed(Kind.ENERGY));
        if (switchable) output.putBoolean(AUTO_OUTPUT, autoOutput);
    }

    /**
     * A saved machine keeps the faces it saved. Machines saved before the grid existed saved none: every face stays at
     * None, as it was for them, whatever a new machine starts at. Machines saved before the Auto Output switch came load
     * with it on, so they keep pushing as they did. Machines saved before the energy faces came take power on every side.
     */
    public void load(ValueInput input) {
        fill(FaceMode.NONE);
        for (Kind kind : Kind.values()) {
            if (!has(kind)) continue;
            int packed = input.getIntOr(kind.key, kind == Kind.ENERGY ? -1 : 0);
            // no energy faces saved: they stay Input, as fill left them
            if (packed < 0) continue;
            for (MachineFace face : MachineFace.values()) {
                FaceMode mode = unpack(packed, face);
                modes[kind.ordinal()][face.ordinal()] = offered(kind).contains(mode) ? mode : FaceMode.NONE;
            }
        }
        if (switchable) autoOutput = input.getBooleanOr(AUTO_OUTPUT, true);
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
