package dev.micolash.jasm.acceptor;

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
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Takes FE other mods push in and draws FE from outside power blocks beside it, and hands it straight to touching cables
 * and machines. It holds nothing and has no speed limit: it only takes what the cables and machines it touches can use
 * right now, so power from another mod never piles up in it. It never gives power to an outside block and never touches
 * JASM generators or other Acceptors.
 */
public class PowerAcceptorBlockEntity extends BlockEntity implements NetworkPowerSource {
    private final EnergyHandler input = new Intake();
    private final PowerSides sides = new PowerSides();
    private final Map<Direction, BlockCapabilityCache<EnergyHandler, @Nullable Direction>> neighbours = new EnumMap<>(Direction.class);

    public PowerAcceptorBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.POWER_ACCEPTOR_ENTITY.get(), pos, state);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, PowerAcceptorBlockEntity acceptor) {
        ServerLevel serverLevel = (ServerLevel) level;
        acceptor.sides.refresh(serverLevel, pos);
        acceptor.pull(serverLevel);
    }

    private void pull(ServerLevel level) {
        for (Direction side : Direction.values()) {
            if (sides.jasm(side)) {
                continue;
            }
            EnergyHandler source = neighbours
                    .computeIfAbsent(side,
                            s -> BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, worldPosition.relative(s), s.getOpposite()))
                    .getCapability();
            if (source == null) {
                continue;
            }
            int room = room();
            if (room <= 0) {
                return;
            }
            take(source, room);
        }
    }

    // out first, then in: some batteries give less than asked
    private void take(EnergyHandler source, int limit) {
        try (Transaction tx = Transaction.openRoot()) {
            int got = source.extract(limit, tx);
            if (got > 0 && forward(got, tx) == got) {
                tx.commit();
            }
        }
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

    /** What other mods see: they may push power in, never take it out. */
    public EnergyHandler input() {
        return input;
    }

    @Override
    public void neighboursChanged() {
        sides.changed();
    }

    // holds nothing, but says it has room or some mods won't push at all
    private final class Intake implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            if (amount <= 0) {
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
