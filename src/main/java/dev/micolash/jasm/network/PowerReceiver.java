package dev.micolash.jasm.network;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A JASM block that takes network power: a cable, a machine or an Archive. Only JASM power sources hold one; other
 * mods never see it. A cable takes up to its rate per push. Nothing comes back out.
 */
public final class PowerReceiver implements EnergyHandler {
    private final BlockEntity owner;
    private final SimpleEnergyHandler energy;
    private final int limit;

    private PowerReceiver(BlockEntity owner, SimpleEnergyHandler energy, int limit) {
        this.owner = owner;
        this.energy = energy;
        this.limit = limit;
    }

    public static @Nullable PowerReceiver of(BlockEntity entity) {
        if (entity instanceof DataCableBlockEntity cable) return new PowerReceiver(cable, cable.energy(), cable.tier().rate());
        if (entity instanceof MachineBlockEntity machine) return new PowerReceiver(machine, machine.energy(), Integer.MAX_VALUE);
        if (entity instanceof ArchiveBlockEntity archive) return new PowerReceiver(archive, archive.energy(), Integer.MAX_VALUE);
        return null;
    }

    /** False once the block is gone or its chunk unloaded. */
    public boolean live() {
        return !owner.isRemoved();
    }

    @Override
    public long getAmountAsLong() {
        return live() ? energy.getAmountAsLong() : 0;
    }

    @Override
    public long getCapacityAsLong() {
        return live() ? energy.getCapacityAsLong() : 0;
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        return live() ? energy.insert(Math.min(amount, limit), transaction) : 0;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return 0;
    }
}
