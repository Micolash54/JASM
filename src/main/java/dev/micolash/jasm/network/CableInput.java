package dev.micolash.jasm.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** What a generator touching a cable sees: room in that cable, up to its rate per push. Nothing comes back out. */
final class CableInput implements EnergyHandler {
    private final ServerLevel level;
    private final BlockPos pos;

    CableInput(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    private DataCableBlockEntity cable() {
        return level.getBlockEntity(pos) instanceof DataCableBlockEntity cable ? cable : null;
    }

    @Override
    public long getAmountAsLong() {
        var cable = cable();
        return cable == null ? 0 : cable.energy().getAmountAsLong();
    }

    @Override
    public long getCapacityAsLong() {
        var cable = cable();
        return cable == null ? 0 : cable.energy().getCapacityAsLong();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        var cable = cable();
        return cable == null ? 0 : cable.energy().insert(Math.min(amount, cable.tier().rate()), transaction);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return 0;
    }
}
