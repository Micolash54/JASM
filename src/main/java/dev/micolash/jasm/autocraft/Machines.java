package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineBlockEntity;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/** The machines behind Access Ports: finding them, naming them, and putting a set of ingredients in. */
public final class Machines {
    private Machines() {}

    /** One machine: the Access Port it touches, and which side of the port it is on. */
    public record At(BlockPos port, Direction side) {
        public static final Codec<At> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("port").forGetter(At::port),
                Direction.CODEC.fieldOf("side").forGetter(At::side))
                .apply(i, At::new));

        public static final StreamCodec<ByteBuf, At> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, At::port,
                Direction.STREAM_CODEC, At::side,
                At::new);

        public At {
            port = port.immutable();
        }
    }

    /** The machine at {@code at}, if its port stands on {@code network}, is loaded, and something there takes items. */
    public static @Nullable AccessPortBlockEntity reach(ServerLevel level, @Nullable CableNetwork network, At at) {
        AccessPortBlockEntity port = port(level, network, at.port(), at.side());
        return port != null && port.hasMachine(at.side()) ? port : null;
    }

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

    public static @Nullable AccessPortBlockEntity port(ServerLevel level, @Nullable CableNetwork network, BlockPos pos, Direction side) {
        AccessPortBlockEntity full = port(level, network, pos);
        if (full != null) return full;
        if (network == null || !network.contains(pos) || !level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos) instanceof DataCableBlockEntity cable ? cable.port(side) : null;
    }

    /** Where items go into the machine on a port's {@code side}, or null if nothing there takes items. */
    public static @Nullable ResourceHandler<ItemResource> inlet(AccessPortBlockEntity port, Direction side) {
        if (!(port.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        return inlet(level, port.getBlockPos().relative(side), side.getOpposite());
    }

    /** Where items go into the block at {@code pos} through its {@code side}, or null if it takes none. */
    public static @Nullable ResourceHandler<ItemResource> inlet(Level level, BlockPos pos, Direction side) {
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity
                || level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
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

    /** Where fluids go into the block at {@code pos} through its {@code side}, or null if it takes none. */
    public static @Nullable ResourceHandler<FluidResource> fluidInlet(Level level, BlockPos pos, Direction side) {
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity
                || level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
            return null;
        }
        return level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
    }

    /** Whether any block touching the port takes items. */
    public static boolean hasMachine(AccessPortBlockEntity port) {
        return !port.machineSides().isEmpty();
    }

    /**
     * Puts one set of ingredients into the machine on the port's {@code side}: all of it, or nothing at all. Returns
     * whether it went in. The machine decides where each item goes, as with a hopper.
     */
    public static boolean push(AccessPortBlockEntity port, Direction side, List<ProcessingCard.Amount> set) {
        int count = set.stream().mapToInt(ProcessingCard.Amount::count).sum();
        if (!port.canSendBatch(count)) return false;
        ResourceHandler<ItemResource> inlet = inlet(port, side);
        if (inlet == null) {
            return false;
        }
        if (port.blockingMode() && containsIngredient(inlet, set)) return false;
        try (Transaction tx = Transaction.openRoot()) {
            for (ProcessingCard.Amount amount : set) {
                if (inlet.insert(amount.item(), amount.count(), tx) != amount.count()) {
                    return false;
                }
            }
            tx.commit();
            port.transferred(count);
            return true;
        }
    }

    private static boolean containsIngredient(ResourceHandler<ItemResource> inlet, List<ProcessingCard.Amount> set) {
        for (int slot = 0; slot < inlet.size(); slot++) {
            ItemResource held = inlet.getResource(slot);
            if (!held.isEmpty() && set.stream().anyMatch(amount -> amount.item().getItem() == held.getItem())) return true;
        }
        return false;
    }

    /** The name of the block at {@code pos}, as a player would call it. */
    public static Component blockName(@Nullable Level level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos) || level.getBlockState(pos).isAir()) {
            return Component.translatable("screen.jasm.port.no_machine");
        }
        return level.getBlockState(pos).getBlock().getName();
    }
}
