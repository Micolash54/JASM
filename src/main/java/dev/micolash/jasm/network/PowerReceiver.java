package dev.micolash.jasm.network;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A JASM block that takes network power: a cable, a machine or an Archive. Only JASM power sources hold one; other
 * mods never see it. A cable holds nothing: what is pushed into it goes to the machines on its network that have room
 * for it, and only that much is taken. Nothing comes back out.
 */
public final class PowerReceiver implements EnergyHandler {
    private final BlockEntity owner;
    /** The buffer power goes into, or null for a cable, which passes it on to its network. */
    private final @Nullable SimpleEnergyHandler energy;

    private PowerReceiver(BlockEntity owner, @Nullable SimpleEnergyHandler energy) {
        this.owner = owner;
        this.energy = energy;
    }

    public static @Nullable PowerReceiver of(BlockEntity entity) {
        if (entity instanceof DataCableBlockEntity cable) return new PowerReceiver(cable, null);
        if (entity instanceof MachineBlockEntity machine) return new PowerReceiver(machine, machine.energy());
        if (entity instanceof ArchiveBlockEntity archive) return new PowerReceiver(archive, archive.energy());
        return null;
    }

    /** False once the block is gone or its chunk unloaded. */
    public boolean live() {
        return !owner.isRemoved();
    }

    @Override
    public long getAmountAsLong() {
        return live() && energy != null ? energy.getAmountAsLong() : 0;
    }

    @Override
    public long getCapacityAsLong() {
        return live() && energy != null ? energy.getCapacityAsLong() : 0;
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        if (!live()) return 0;
        if (energy != null) return energy.insert(amount, transaction);
        if (!(owner.getLevel() instanceof ServerLevel level)) return 0;
        CableNetwork network = Networks.at(level, owner.getBlockPos());
        return network == null ? 0 : network.feed(amount, transaction);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return 0;
    }
}
