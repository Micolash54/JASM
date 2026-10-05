package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.NetworkPowerSource;
import dev.micolash.jasm.network.PowerReceiver;
import dev.micolash.jasm.network.PowerSides;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.LimitingEnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Takes FE other mods push in and draws FE from outside power blocks beside it, then hands it to touching cables and
 * machines. It never gives power to an outside block and never touches JASM generators or other Acceptors.
 */
public class PowerAcceptorBlockEntity extends BlockEntity implements NetworkPowerSource {
    private final int rate = JasmConfig.orDefault(JasmConfig.ACCEPTOR_RATE);
    private final int capacity = rate * 4;
    private final SimpleEnergyHandler energy;
    private final EnergyHandler input = new Intake();
    private final EnergyHandler output;
    private final PowerSides sides = new PowerSides();
    private final Map<Direction, BlockCapabilityCache<EnergyHandler, @Nullable Direction>> neighbours = new EnumMap<>(Direction.class);

    public PowerAcceptorBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.POWER_ACCEPTOR_ENTITY.get(), pos, state);
        this.energy = new SimpleEnergyHandler(capacity, capacity, capacity) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                setChanged();
            }
        };
        this.output = new LimitingEnergyHandler(energy, 0, Integer.MAX_VALUE);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, PowerAcceptorBlockEntity acceptor) {
        ServerLevel serverLevel = (ServerLevel) level;
        acceptor.sides.refresh(serverLevel, pos);
        acceptor.pull(serverLevel);
        acceptor.push();
    }

    /** Draws up to the rate from the outside blocks beside it, as much as the buffer has room for. */
    private void pull(ServerLevel level) {
        int budget = rate;
        for (Direction side : Direction.values()) {
            int room = Math.min(budget, energy.getCapacityAsInt() - energy.getAmountAsInt());
            if (room <= 0) {
                return;
            }
            if (sides.jasm(side)) {
                continue;
            }
            EnergyHandler source = neighbours
                    .computeIfAbsent(side,
                            s -> BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, worldPosition.relative(s), s.getOpposite()))
                    .getCapability();
            if (source != null) {
                budget -= take(source, room);
            }
        }
    }

    private int take(EnergyHandler source, int limit) {
        try (Transaction tx = Transaction.openRoot()) {
            int got = source.extract(limit, tx);
            if (got > 0 && energy.insert(got, tx) == got) {
                tx.commit();
                return got;
            }
        }
        return 0;
    }

    /** Up to the rate into each touching cable or machine. */
    private void push() {
        for (Direction side : Direction.values()) {
            int available = energy.getAmountAsInt();
            if (available <= 0) {
                return;
            }
            PowerReceiver target = sides.receiver(side);
            if (target == null) {
                continue;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int accepted = target.insert(Math.min(rate, available), tx);
                if (accepted > 0 && energy.extract(accepted, tx) == accepted) {
                    tx.commit();
                }
            }
        }
    }

    public SimpleEnergyHandler energy() {
        return energy;
    }

    /** What other mods see: they may push power in, never take it out. */
    public EnergyHandler input() {
        return input;
    }

    @Override
    public EnergyHandler networkOutput() {
        return output;
    }

    @Override
    public void neighboursChanged() {
        sides.changed();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("energy", energy.getAmountAsInt());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, capacity));
    }

    private final class Intake implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return energy.getAmountAsLong();
        }

        @Override
        public long getCapacityAsLong() {
            return energy.getCapacityAsLong();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            return energy.insert(Math.min(amount, rate), transaction);
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }
    }
}
