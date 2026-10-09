package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.battery.BatteryBlockEntity;
import dev.micolash.jasm.battery.BatteryGroup;
import java.util.Arrays;
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
 * How a Power Acceptor moves power between a JASM network and other mods' blocks, as its mode says. In (the default) it
 * accepts the FE other mods' blocks push into it and hands it straight to the network, but only as much as the network's
 * machines and Batteries have room for; it never pulls power out of anything. Out, it pushes the power stored in Batteries
 * into outside blocks that take FE. It holds no power of its own and has no speed limit. It never touches JASM generators
 * or other Acceptors, and never takes the power inside machines. A side keeps the way it first went (a block that pushed
 * power in is never filled, one it filled is never taken from) until the mode is switched or another block takes its
 * place, so power never goes round in circles.
 *
 * <p>The block and the thin acceptor on a cable share this. Each one says what it has around it through a {@link Site}.
 */
final class AcceptorFlow {
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
    }

    /** Which way power goes through a side. */
    private enum Way {
        FREE,
        /** It pushed power in. */
        FROM,
        /** It was given power. */
        TO
    }

    private final Site site;
    /** What other mods see on each side, and on no side in particular. */
    private final Intake[] intakes = new Intake[6];
    private final Intake anySide = new Intake(null);
    private final Map<Direction, BlockCapabilityCache<EnergyHandler, @Nullable Direction>> neighbours = new EnumMap<>(Direction.class);
    private final Way[] ways = new Way[6];
    /** The outside block each side's way was learned from: a different one there starts again. */
    private final EnergyHandler[] wayOf = new EnergyHandler[6];
    private AcceptorMode mode = AcceptorMode.INPUT;
    /** The network's room, worked out at most once a tick and only when someone asks. */
    private long roomTick = Long.MIN_VALUE;
    private int room;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return mode.ordinal();
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
        Arrays.fill(ways, Way.FREE);
        for (Direction side : Direction.values()) {
            intakes[side.ordinal()] = new Intake(side);
        }
    }

    AcceptorMode mode() {
        return mode;
    }

    /** Returns whether the mode changed. Switching it is how a player has the acceptor look again at which way each side goes. */
    boolean setMode(AcceptorMode mode) {
        if (this.mode == mode) {
            return false;
        }
        this.mode = mode;
        Arrays.fill(ways, Way.FREE);
        return true;
    }

    /** What other mods see on that side: they may push power in, never take it out. */
    EnergyHandler input(@Nullable Direction side) {
        return side == null ? anySide : intakes[side.ordinal()];
    }

    ContainerData data() {
        return data;
    }

    void tick(ServerLevel level) {
        site.refresh(level);
        if (mode.out()) {
            give(level);
        }
    }

    private @Nullable EnergyHandler outside(ServerLevel level, Direction side) {
        EnergyHandler handler = null;
        if (site.open(side)) {
            handler = neighbours
                    .computeIfAbsent(side, s -> BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, site.pos().relative(s), s.getOpposite()))
                    .getCapability();
        }
        if (wayOf[side.ordinal()] != handler) {
            wayOf[side.ordinal()] = handler;
            ways[side.ordinal()] = Way.FREE;
        }
        return handler;
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

    /** Pushes battery power into each outside block beside it that has room. The batteries are only looked at when one does. */
    private void give(ServerLevel level) {
        List<EnergyHandler> batteries = null;
        for (Direction side : Direction.values()) {
            EnergyHandler target = outside(level, side);
            if (target == null || ways[side.ordinal()] == Way.FROM) {
                continue;
            }
            int room = room(target);
            if (room <= 0) {
                continue;
            }
            if (batteries == null) {
                batteries = BatteryGroup.distinct(level, site.batteries(level));
            }
            if (give(target, room, batteries)) {
                ways[side.ordinal()] = Way.TO;
            }
        }
    }

    private static int room(EnergyHandler target) {
        try (Transaction tx = Transaction.openRoot()) {
            return target.insert(Integer.MAX_VALUE, tx);
        }
    }

    private static boolean give(EnergyHandler target, int room, List<EnergyHandler> batteries) {
        long held = 0;
        for (EnergyHandler battery : batteries) {
            held += battery.getAmountAsLong();
        }
        int want = (int) Math.min(room, held);
        if (want <= 0) {
            return false;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int accepted = target.insert(want, tx);
            if (accepted > 0 && drain(batteries, accepted, tx) == accepted) {
                tx.commit();
                return true;
            }
        }
        return false;
    }

    /** Takes {@code amount} from the batteries, an equal part from each first and the rest from whoever still holds some. */
    private static int drain(List<EnergyHandler> batteries, int amount, TransactionContext transaction) {
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
     * network takes. A push that goes through marks its side as one power comes in from.
     */
    private final class Intake extends SnapshotJournal<Integer> implements EnergyHandler {
        private final @Nullable Direction side;
        /** Taken in the transfer going on now; it only counts once that transfer is kept. */
        private int received;

        Intake(@Nullable Direction side) {
            this.side = side;
        }

        private boolean refused() {
            return !mode.in() || side != null && ways[side.ordinal()] == Way.TO;
        }

        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            return refused() ? 0 : room();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            if (amount <= 0 || refused()) {
                return 0;
            }
            if (site.level() instanceof ServerLevel serverLevel) {
                site.refresh(serverLevel);
            }
            int taken = site.forward(amount, transaction);
            if (taken > 0 && side != null) {
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
            if (received > 0 && side != null && site.level() instanceof ServerLevel level) {
                outside(level, side);
                ways[side.ordinal()] = Way.FROM;
            }
            received = 0;
        }
    }
}
