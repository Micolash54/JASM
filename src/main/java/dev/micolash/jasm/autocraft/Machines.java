package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/** The machines behind Access Ports: finding them, naming them, and putting a set of ingredients in. */
public final class Machines {
    private Machines() {}

    /** The Access Ports on {@code network} that are loaded. */
    public static List<AccessPortBlockEntity> ports(CableNetwork network) {
        return network.machines(AccessPortBlockEntity.class);
    }

    /** The port at {@code pos}, if it stands there, is loaded and belongs to {@code network}. */
    public static @Nullable AccessPortBlockEntity port(ServerLevel level, @Nullable CableNetwork network, BlockPos pos) {
        if (network == null || !network.contains(pos) || !level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof AccessPortBlockEntity port ? port : null;
    }

    /** Where items go into the machine a port faces, or null if nothing there takes items. */
    public static @Nullable ResourceHandler<ItemResource> inlet(AccessPortBlockEntity port) {
        if (!(port.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos pos = port.machinePos();
        Direction side = port.facing().getOpposite();
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity) {
            // Another crafting block is no machine.
            return null;
        }
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        if (handler != null) {
            return handler;
        }
        if (level.getBlockEntity(pos) instanceof WorldlyContainer worldly) {
            return new WorldlyContainerWrapper(worldly, side);
        }
        return level.getBlockEntity(pos) instanceof Container container ? VanillaContainerWrapper.of(container) : null;
    }

    /** Whether the port faces something that takes items. */
    public static boolean hasMachine(AccessPortBlockEntity port) {
        return inlet(port) != null;
    }

    /**
     * Puts one set of ingredients into the port's machine: all of it, or nothing at all. Returns whether it went in.
     * The machine decides where each item goes, as with a hopper.
     */
    public static boolean push(AccessPortBlockEntity port, List<ProcessingCard.Amount> set) {
        ResourceHandler<ItemResource> inlet = inlet(port);
        if (inlet == null) {
            return false;
        }
        try (Transaction tx = Transaction.openRoot()) {
            for (ProcessingCard.Amount amount : set) {
                if (inlet.insert(amount.item(), amount.count(), tx) != amount.count()) {
                    return false;
                }
            }
            tx.commit();
            return true;
        }
    }

    /** The name of the block at {@code pos}, as a player would call it. */
    public static Component blockName(@Nullable Level level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos) || level.getBlockState(pos).isAir()) {
            return Component.translatable("screen.jasm.port.no_machine");
        }
        return level.getBlockState(pos).getBlock().getName();
    }
}
