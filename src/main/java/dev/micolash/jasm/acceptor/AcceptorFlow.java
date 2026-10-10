package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.battery.BatteryBlockEntity;
import dev.micolash.jasm.battery.BatteryGroup;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * How a Power Acceptor moves power between a JASM network and other mods' blocks, one way only, as its mode says. In (the
 * default) it accepts the FE other mods' blocks push into it and hands it straight to the network, but only as much as the
 * network's machines and Batteries have room for; it never pulls power out of anything. Out, it gives the power stored in
 * Batteries first, then combustion generators once every battery is empty. It holds no power of its own and has no speed
 * limit. Other Acceptors and the power inside machines are never sources.
 *
 * <p>The block and the thin acceptor on a cable share this. Each one says what it has around it through a {@link Site}.
 */
final class AcceptorFlow {
    /** Ticks the power flow is averaged over. */
    private static final int FLOW_TICKS = 20;

    /** What one kind of acceptor has around it. */
    interface Site {
        @Nullable Level level();

        /** Where the acceptor is: outside blocks are the neighbours of this spot. */
        BlockPos pos();

        /** Looks again at what is around, once something changed. Called every tick, so it must be cheap when nothing did. */
        void refresh(ServerLevel level);

        /** Whether an outside block may sit on that side: any block that is not a JASM one. */
        boolean open(Direction side);

        /** Pushes power into the network and returns how much was taken. */
        int forward(int amount, TransactionContext transaction);

        /** The Batteries it may empty. */
        List<BatteryBlockEntity> batteries(ServerLevel level);

        /** Combustion generators only, never machines that happen to store FE. */
        List<CombustionGeneratorBlockEntity> generators(ServerLevel level);
    }

    private final Site site;
    /** What other mods see, on every side. */
    private final Intake intake = new Intake();
    private final Map<Direction, BlockCapabilityCache<EnergyHandler, @Nullable Direction>> neighbours = new EnumMap<>(Direction.class);
    private AcceptorMode mode = AcceptorMode.INPUT;
    /** The network's room, worked out at most once a tick and only when someone asks. */
    private long roomTick = Long.MIN_VALUE;
    private int room;
    /** FE moved since the flow was last worked out, and the FE a tick that makes, averaged over the last second. */
    private long moved;
    private int flow;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            // Synced numbers travel as 16-bit words, so the flow goes as two of them.
            return index == 0 ? mode.ordinal() : flow >>> 16 * (index - 1) & 0xFFFF;
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return PowerAcceptorMenu.DATA_COUNT;
        }
    };

    AcceptorFlow(Site site) {
        this.site = site;
    }

    AcceptorMode mode() {
        return mode;
    }

    /** Returns whether the mode changed. */
    boolean setMode(AcceptorMode mode) {
        if (this.mode == mode) {
            return false;
        }
        this.mode = mode;
        moved = 0;
        flow = 0;
        return true;
    }

    /** What other mods see on that side: they may push power in, never take it out. */
    EnergyHandler input(@Nullable Direction side) {
        return intake;
    }

    ContainerData data() {
        return data;
    }

    void tick(ServerLevel level) {
        site.refresh(level);
        if (mode == AcceptorMode.OUTPUT) {
            give(level);
        }
        if ((level.getGameTime() + site.pos().asLong()) % FLOW_TICKS == 0) {
            flow = (int) Math.min(Integer.MAX_VALUE, Math.round(moved / (double) FLOW_TICKS));
            moved = 0;
        }
    }

    private @Nullable EnergyHandler outside(ServerLevel level, Direction side) {
        if (!site.open(side) || !level.isLoaded(site.pos().relative(side))) {
            return null;
        }
        return neighbours
                .computeIfAbsent(side, s -> BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, site.pos().relative(s), s.getOpposite()))
                .getCapability();
    }

    /**
     * How much the network could take right now, for mods that ask before they push. Worked out with a dry run inside
     * whatever transfer is going on, or the last answer this tick if that can't be done.
     */
    private int room() {
        if (!(site.level() instanceof ServerLevel level)) {
            return 0;
        }
        long now = level.getGameTime();
        Transaction.Lifecycle lifecycle = Transaction.getLifecycle();
        if (now == roomTick || lifecycle == Transaction.Lifecycle.CLOSING || lifecycle == Transaction.Lifecycle.ROOT_CLOSING) {
            return now == roomTick ? room : 0;
        }
        site.refresh(level);
        TransactionContext outer = Transaction.getCurrentOpenedTransaction();
        try (Transaction dryRun = outer == null ? Transaction.openRoot() : Transaction.open(outer)) {
            room = site.forward(Integer.MAX_VALUE, dryRun);
        }
        roomTick = now;
        return room;
    }

    /** Gives battery power to each outside block beside it that has room. The batteries are only looked at when one does. */
    private void give(ServerLevel level) {
        List<EnergyHandler> batteries = null;
        for (Direction side : Direction.values()) {
            EnergyHandler target = outside(level, side);
            if (target == null) {
                continue;
            }
            int room = room(target);
            if (room <= 0) {
                continue;
            }
            if (batteries == null) {
                batteries = BatteryGroup.distinct(level, site.batteries(level));
            }
            moved += give(target, room, batteries);
            // A partially charged battery still comes first; fallback starts only once all are empty.
            if (batteries.stream().allMatch(battery -> battery.getAmountAsLong() == 0)) {
                List<EnergyHandler> generators = new ArrayList<>();
                for (CombustionGeneratorBlockEntity generator : site.generators(level)) {
                    if (!generator.isRemoved()) generators.add(generator.output());
                }
                moved += give(target, room(target), generators);
            }
        }
    }

    private static int room(EnergyHandler target) {
        try (Transaction tx = Transaction.openRoot()) {
            return target.insert(Integer.MAX_VALUE, tx);
        }
    }

    /** Gives the target as much as it said it takes, or what the batteries hold if less. Returns what went. */
    private static int give(EnergyHandler target, int room, List<EnergyHandler> batteries) {
        long held = 0;
        for (EnergyHandler battery : batteries) {
            held += battery.getAmountAsLong();
        }
        int want = (int) Math.min(room, held);
        if (want <= 0) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int accepted = target.insert(want, tx);
            if (accepted > 0 && drain(batteries, accepted, tx) == accepted) {
                tx.commit();
                return accepted;
            }
        }
        return 0;
    }

    /** Takes {@code amount} from the batteries, an equal part from each first and the rest from whoever still holds some. */
    private static int drain(List<EnergyHandler> batteries, int amount, TransactionContext transaction) {
        if (batteries.isEmpty()) return 0;
        int taken = 0;
        int share = Math.max(1, amount / batteries.size());
        for (int pass = 0; pass < 2 && taken < amount; pass++) {
            for (EnergyHandler battery : batteries) {
                if (taken >= amount) {
                    break;
                }
                taken += battery.extract(pass == 0 ? Math.min(share, amount - taken) : amount - taken, transaction);
            }
        }
        return taken;
    }

    /**
     * What other mods push into. It holds nothing: it reports the network's room as its own, and passes on only what the
     * network takes. What it took counts towards the flow once the transfer is kept.
     */
    private final class Intake extends SnapshotJournal<Integer> implements EnergyHandler {
        /** Taken in the transfer going on now; it only counts once that transfer is kept. */
        private int received;

        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            return mode == AcceptorMode.INPUT ? room() : 0;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            if (amount <= 0 || mode != AcceptorMode.INPUT) {
                return 0;
            }
            if (site.level() instanceof ServerLevel serverLevel) {
                site.refresh(serverLevel);
            }
            int taken = site.forward(amount, transaction);
            if (taken > 0) {
                updateSnapshots(transaction);
                received += taken;
            }
            return taken;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        protected Integer createSnapshot() {
            return received;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            received = snapshot;
        }

        @Override
        protected void onRootCommit(Integer original) {
            moved += received;
            received = 0;
        }
    }
}
