package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.battery.BatteryBlockEntity;
import dev.micolash.jasm.battery.BatteryGroup;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.NetworkPowerSource;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.network.PowerReceiver;
import dev.micolash.jasm.network.PowerSides;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Moves power between a JASM network and other mods' blocks, as its mode says. In (the default) it takes FE other mods
 * push in and draws FE from outside power blocks beside it, and hands it straight to touching cables and machines. Out,
 * it pushes the power stored in Batteries into outside blocks that take FE: those it touches, and those touching the
 * cables of the networks it touches. It holds nothing
 * and has no speed limit: it only moves what the other side can take right now. It never touches JASM generators or
 * other Acceptors, and never takes the power inside machines.
 */
public class PowerAcceptorBlockEntity extends BlockEntity implements NetworkPowerSource, MenuProvider {
    /** Ticks a side keeps its direction after power last moved through it, so a two-way store isn't filled and emptied in turns. */
    private static final int HOLD = 20;

    private final EnergyHandler input = new Intake();
    private final PowerSides sides = new PowerSides();
    private final Map<Direction, BlockCapabilityCache<EnergyHandler, @Nullable Direction>> neighbours = new EnumMap<>(Direction.class);
    private final long[] tookAt = new long[6];
    private final long[] gaveAt = new long[6];
    private final CableNetwork[] poolNetworks = new CableNetwork[6];
    private final PowerReceiver[] poolSides = new PowerReceiver[6];
    private @Nullable List<BatteryBlockEntity> pool;
    private AcceptorMode mode = AcceptorMode.INPUT;

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

    public PowerAcceptorBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.POWER_ACCEPTOR_ENTITY.get(), pos, state);
        Arrays.fill(tookAt, Long.MIN_VALUE / 2);
        Arrays.fill(gaveAt, Long.MIN_VALUE / 2);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, PowerAcceptorBlockEntity acceptor) {
        ServerLevel serverLevel = (ServerLevel) level;
        acceptor.sides.refresh(serverLevel, pos);
        if (acceptor.mode.in()) {
            acceptor.pull(serverLevel);
        }
        if (acceptor.mode.out()) {
            acceptor.give(serverLevel);
        }
    }

    public AcceptorMode mode() {
        return mode;
    }

    public void setMode(AcceptorMode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            setChanged();
        }
    }

    private @Nullable EnergyHandler outside(ServerLevel level, Direction side) {
        if (sides.jasm(side)) {
            return null;
        }
        return neighbours
                .computeIfAbsent(side, s -> BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, worldPosition.relative(s), s.getOpposite()))
                .getCapability();
    }

    private void pull(ServerLevel level) {
        long now = level.getGameTime();
        for (Direction side : Direction.values()) {
            if (now - gaveAt[side.ordinal()] < HOLD) {
                continue;
            }
            EnergyHandler source = outside(level, side);
            if (source == null) {
                continue;
            }
            int room = room();
            if (room <= 0) {
                return;
            }
            if (take(source, room)) {
                tookAt[side.ordinal()] = now;
            }
        }
    }

    // out first, then in: some batteries give less than asked
    private boolean take(EnergyHandler source, int limit) {
        try (Transaction tx = Transaction.openRoot()) {
            int got = source.extract(limit, tx);
            if (got > 0 && forward(got, tx) == got) {
                tx.commit();
                return true;
            }
        }
        return false;
    }

    // a dry run, nothing moves
    private int room() {
        try (Transaction tx = Transaction.openRoot()) {
            return forward(Integer.MAX_VALUE, tx);
        }
    }

    private int forward(int amount, TransactionContext transaction) {
        int taken = 0;
        for (Direction side : Direction.values()) {
            if (taken >= amount) {
                break;
            }
            PowerReceiver target = sides.receiver(side);
            if (target != null) {
                taken += target.insert(amount - taken, transaction);
            }
        }
        return taken;
    }

    /** Pushes battery power into each outside block beside it that has room. The batteries are only looked at when one does. */
    private void give(ServerLevel level) {
        long now = level.getGameTime();
        List<EnergyHandler> batteries = null;
        for (Direction side : Direction.values()) {
            if (now - tookAt[side.ordinal()] < HOLD) {
                continue;
            }
            EnergyHandler target = outside(level, side);
            if (target == null) {
                continue;
            }
            int room = room(target);
            if (room <= 0) {
                continue;
            }
            if (batteries == null) {
                batteries = BatteryGroup.distinct(level, pool(level));
            }
            if (give(target, room, batteries)) {
                gaveAt[side.ordinal()] = now;
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
     * The Battery blocks it may empty: those it touches, and those on the cables of the networks of the blocks it touches.
     * A network is replaced whenever something on it changes, so the list is only built again when a side or a network
     * is new.
     */
    private List<BatteryBlockEntity> pool(ServerLevel level) {
        boolean same = true;
        for (Direction side : Direction.values()) {
            PowerReceiver receiver = sides.receiver(side);
            CableNetwork network = receiver == null ? null : Networks.at(level, worldPosition.relative(side));
            if (poolNetworks[side.ordinal()] != network || poolSides[side.ordinal()] != receiver) {
                poolNetworks[side.ordinal()] = network;
                poolSides[side.ordinal()] = receiver;
                same = false;
            }
        }
        if (same && pool != null) {
            return pool;
        }
        List<BatteryBlockEntity> found = new ArrayList<>();
        for (Direction side : Direction.values()) {
            if (poolSides[side.ordinal()] != null && level.getBlockEntity(worldPosition.relative(side)) instanceof BatteryBlockEntity battery) {
                found.add(battery);
            }
        }
        for (CableNetwork network : poolNetworks) {
            if (network != null) {
                found.addAll(network.batteries());
            }
        }
        pool = found;
        return found;
    }

    /** What other mods see: they may push power in, never take it out. */
    public EnergyHandler input() {
        return input;
    }

    @Override
    public void neighboursChanged() {
        sides.changed();
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PowerAcceptorMenu(containerId, this, data, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("mode", AcceptorMode.CODEC, mode);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // Acceptors saved before it had modes only took power in.
        mode = input.read("mode", AcceptorMode.CODEC).orElse(AcceptorMode.INPUT);
    }

    // holds nothing, but says it has room or some mods won't push at all
    private final class Intake implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            return mode.in() ? Integer.MAX_VALUE : 0;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            if (amount <= 0 || !mode.in()) {
                return 0;
            }
            if (level instanceof ServerLevel serverLevel) {
                sides.refresh(serverLevel, worldPosition);
            }
            return forward(amount, transaction);
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }
    }
}
