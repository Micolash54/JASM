package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.PowerShare;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * How a port with a Power Upgrade passes power on. The port holds none: what a source offers goes straight into the
 * machines the port powers, inside the source's own transfer, and only what they take is accepted. Each side the port
 * may power is a target of its own, so when there isn't enough to go round every machine gets an equal part.
 *
 * <p>The targets never change for a port; each one looks at the port and its machine when asked, so a list of them kept
 * by the network stays right when machines or the upgrade come and go.
 */
final class PortPower {
    /** Ticks the power passed on is averaged over for the screen. */
    private static final int FLOW_TICKS = 20;

    private final AccessPortBlockEntity port;
    private final Side[] sides = new Side[Direction.values().length];
    private final Whole whole = new Whole();
    /** The sides this port may power, found the first time they are asked for: a thin port's face is set by then. */
    private @Nullable List<Side> inlets;
    /** FE passed on since the window began, and the FE a tick the last full window came to. */
    private long windowStart = Long.MIN_VALUE;
    private long moved;
    private int flow;

    PortPower(AccessPortBlockEntity port) {
        this.port = port;
        for (Direction side : Direction.values()) {
            sides[side.ordinal()] = new Side(side);
        }
    }

    /** One target per side the port may power, for the cables of its network. */
    List<? extends EnergyHandler> inlets() {
        List<Side> found = inlets;
        if (found == null) {
            List<Side> made = new ArrayList<>(6);
            for (Direction side : port.powerFaces()) made.add(sides[side.ordinal()]);
            inlets = found = List.copyOf(made);
        }
        return found;
    }

    /** What a source touching the port pushes into: it shares what it is given out between the sides. */
    EnergyHandler whole() {
        return whole;
    }

    /** FE a tick passed on to the machines, averaged over the last second. */
    int flow() {
        roll(now());
        return flow;
    }

    private long now() {
        return port.getLevel() == null ? 0 : port.getLevel().getGameTime();
    }

    private void passed(int amount) {
        roll(now());
        moved += amount;
    }

    private void roll(long now) {
        if (windowStart == Long.MIN_VALUE || now < windowStart) {
            windowStart = now;
            moved = 0;
            flow = 0;
        } else if (now - windowStart >= FLOW_TICKS) {
            // A window nobody looked at for a while ended with nothing passed since.
            flow = now - windowStart < 2L * FLOW_TICKS ? (int) Math.min(Integer.MAX_VALUE, Math.round(moved / (double) FLOW_TICKS)) : 0;
            windowStart = now;
            moved = 0;
        }
    }

    /** FE handed to one side in one tick. */
    private record Used(long tick, int amount) {}

    /** The machine on one side. It reports the room the machine had this tick, less what it has been given since. */
    private final class Side extends SnapshotJournal<Used> implements EnergyHandler {
        private final Direction side;
        private long roomTick = Long.MIN_VALUE;
        private int room;
        private Used used = new Used(Long.MIN_VALUE, 0);

        Side(Direction side) {
            this.side = side;
        }

        /**
         * What the machine took in a dry run, at most once a tick and only when someone asks. Done inside whatever transfer
         * is going on, so it sees what that transfer has moved already. Whether the port passes power on at all (its
         * upgrade, a blocked or stopped network) is looked at every time, so a change counts at once.
         */
        private int room() {
            if (!(port.getLevel() instanceof ServerLevel level) || !port.passesPower()) {
                return 0;
            }
            long now = level.getGameTime();
            if (now == roomTick) {
                return room;
            }
            Transaction.Lifecycle lifecycle = Transaction.getLifecycle();
            if (lifecycle == Transaction.Lifecycle.CLOSING || lifecycle == Transaction.Lifecycle.ROOT_CLOSING) {
                return 0;
            }
            EnergyHandler target = port.powerTarget(side);
            int found = 0;
            if (target != null) {
                TransactionContext outer = Transaction.getCurrentOpenedTransaction();
                try (Transaction dryRun = outer == null ? Transaction.openRoot() : Transaction.open(outer)) {
                    found = target.insert(Integer.MAX_VALUE, dryRun);
                }
            }
            room = found;
            roomTick = now;
            return found;
        }

        private int usedNow() {
            return used.tick() == now() ? used.amount() : 0;
        }

        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            return Math.max(0, room() - usedNow());
        }

        /** Never more in a tick than the machine said it takes: some take a fixed amount each time they are asked. */
        @Override
        public int insert(int amount, TransactionContext transaction) {
            int most = Math.min(amount, room() - usedNow());
            if (most <= 0) {
                return 0;
            }
            EnergyHandler target = port.powerTarget(side);
            int taken = target == null ? 0 : target.insert(most, transaction);
            if (taken > 0) {
                updateSnapshots(transaction);
                used = new Used(now(), usedNow() + taken);
            }
            return taken;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        protected Used createSnapshot() {
            return used;
        }

        @Override
        protected void revertToSnapshot(Used snapshot) {
            used = snapshot;
        }

        @Override
        protected void onRootCommit(Used original) {
            passed(used.tick() == original.tick() ? used.amount() - original.amount() : used.amount());
        }
    }

    /** The port as one block, for a source that touches it: it holds nothing and shares what it is given between the sides. */
    private final class Whole implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            long room = 0;
            for (EnergyHandler side : inlets()) room += side.getCapacityAsLong();
            return room;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            return PowerShare.spread(inlets(), amount, now(), transaction);
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }
    }
}
