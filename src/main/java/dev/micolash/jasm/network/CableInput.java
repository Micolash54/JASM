package dev.micolash.jasm.network;

import dev.micolash.jasm.config.JasmConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.energy.EmptyEnergyHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** What a generator touching a cable sees: room in the cable's network, up to the cable rate per push. */
final class CableInput implements EnergyHandler {
    private final ServerLevel level;
    private final BlockPos pos;

    CableInput(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    private EnergyHandler buffer() {
        CableNetwork network = Networks.at(level, pos);
        return network == null ? EmptyEnergyHandler.INSTANCE : network.buffer();
    }

    @Override
    public long getAmountAsLong() {
        return buffer().getAmountAsLong();
    }

    @Override
    public long getCapacityAsLong() {
        return buffer().getCapacityAsLong();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        return buffer().insert(Math.min(amount, JasmConfig.CABLE_RATE.getAsInt()), transaction);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return 0;
    }
}
