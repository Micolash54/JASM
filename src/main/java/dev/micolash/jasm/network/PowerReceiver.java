package dev.micolash.jasm.network;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.battery.BatteryBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A JASM block that takes network power: a cable, a machine, an Archive or a Battery. Only JASM power sources hold one;
 * other mods never see it. A cable holds nothing: what is pushed into it goes to the machines on its network that have
 * room for it, then to its batteries, and only that much is taken. Nothing comes back out.
 */
public final class PowerReceiver implements EnergyHandler {
    private final BlockEntity owner;
    /** The buffer power goes into, or null for a cable, which passes it on to its network. */
    private final @Nullable EnergyHandler energy;

    private PowerReceiver(BlockEntity owner, @Nullable EnergyHandler energy) {
        this.owner = owner;
        this.energy = energy;
    }

    public static @Nullable PowerReceiver of(BlockEntity entity) {
        if (entity instanceof DataCableBlockEntity cable) return new PowerReceiver(cable, null);
        if (entity instanceof MachineBlockEntity machine) return new PowerReceiver(machine, machine.energy());
        if (entity instanceof ArchiveBlockEntity archive) return new PowerReceiver(archive, archive.energy());
        if (entity instanceof BatteryBlockEntity battery) return new PowerReceiver(battery, battery.groupEnergy());
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
        CableNetwork network = network();
        return network == null ? 0 : network.feed(amount, transaction);
    }

    /** Power out of a battery: a cable passes it only to the machines of its network, and a battery takes none. */
    public int insertFromBattery(int amount, TransactionContext transaction) {
        if (!live() || owner instanceof BatteryBlockEntity) return 0;
        if (energy != null) return energy.insert(amount, transaction);
        CableNetwork network = network();
        return network == null ? 0 : network.feedMachines(amount, transaction);
    }

    /** What the power ends up in: the block's buffer, or a cable's network. Two cables of one network share it. */
    public @Nullable Object target() {
        return energy != null ? energy : network();
    }

    private @Nullable CableNetwork network() {
        return owner.getLevel() instanceof ServerLevel level ? Networks.at(level, owner.getBlockPos()) : null;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return 0;
    }
}
