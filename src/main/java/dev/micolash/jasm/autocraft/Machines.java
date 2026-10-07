package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.pool.Material;
import dev.micolash.jasm.pool.MaterialKinds;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.wafer.FluidAmounts;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
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
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity machine && !machine.opensToPorts()
                || level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
            // Another crafting block is no machine. Bays are, since ports fill and empty them.
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
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity machine && !machine.opensToPorts()
                || level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
            return null;
        }
        return level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
    }

    /** A handler for another mod's materials, with the capability it came from. */
    public record MaterialInlet(BlockCapability<ResourceHandler<Resource>, @Nullable Direction> kind, ResourceHandler<Resource> handler) {}

    /** Every material handler the block at {@code pos} offers through {@code side}, one per distinct handler. */
    public static List<MaterialInlet> materialInlets(Level level, BlockPos pos, Direction side) {
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity machine && !machine.opensToPorts()
                || level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
            return List.of();
        }
        List<MaterialInlet> found = new ArrayList<>(2);
        for (var kind : MaterialKinds.blocks()) {
            var handler = level.getCapability(kind, pos, side);
            if (handler != null && found.stream().noneMatch(known -> known.handler() == handler)) found.add(new MaterialInlet(kind, handler));
        }
        return found;
    }

    /** Whether any block touching the port takes items. */
    public static boolean hasMachine(AccessPortBlockEntity port) {
        return !port.machineSides().isEmpty();
    }

    /** How an attempt to put a set into a machine ended. */
    public enum Push {
        SENT,
        /** The machine is full, doesn't take it, or is in the way of itself. Nothing moved. */
        REFUSED,
        /** The network's storage holds too little of a material the set needs. Nothing moved. */
        SHORT
    }

    /**
     * Puts one set of ingredients into the machine on the port's {@code side}: all of it, or nothing at all. Returns
     * whether it went in. The machine decides where each item goes, as with a hopper. A set with materials needs
     * {@link #push(AccessPortBlockEntity, Direction, List, NetworkPool)}.
     */
    public static boolean push(AccessPortBlockEntity port, Direction side, List<ProcessingCard.Amount> set) {
        return push(port, side, set, null) == Push.SENT;
    }

    /**
     * As above, and materials come out of {@code pool} (the network's storage blocks). The machine is asked first with
     * nothing taken; the pool then gives up exactly what the set needs or nothing; the machine takes it, and if it
     * somehow doesn't, everything goes back to the pool.
     */
    public static Push push(AccessPortBlockEntity port, Direction side, List<ProcessingCard.Amount> set, @Nullable NetworkPool pool) {
        // A fluid or material counts in shares (an eighth of a bucket each) against the port's allowance, like an item.
        long shares = 0;
        boolean items = false;
        boolean fluids = false;
        boolean materials = false;
        for (ProcessingCard.Amount amount : set) {
            if (amount.isMaterial()) {
                materials = true;
                shares += FluidAmounts.shares(amount.count());
            } else if (amount.isFluid()) {
                fluids = true;
                shares += FluidAmounts.shares(amount.count());
            } else {
                items = true;
                shares += amount.count();
            }
        }
        int count = (int) Math.min(shares, Integer.MAX_VALUE);
        if (!port.canSendBatch(count)) return Push.REFUSED;
        ResourceHandler<ItemResource> inlet = items ? inlet(port, side) : null;
        ResourceHandler<FluidResource> tank = fluids ? fluidInlet(port, side) : null;
        List<MaterialInlet> vessels = materials && port.getLevel() instanceof ServerLevel level
                ? materialInlets(level, port.getBlockPos().relative(side), side.getOpposite())
                : List.of();
        if (items && inlet == null || fluids && tank == null || materials && vessels.isEmpty()) {
            return Push.REFUSED;
        }
        if (materials && pool == null) return Push.SHORT;
        if (port.blockingMode() && (inlet != null && containsIngredient(inlet, set) || tank != null && containsFluid(tank, set)
                || materials && containsMaterial(vessels, set))) return Push.REFUSED;
        // what the pool could give, by exact resource: the first resource of each name stands in for the dry run
        Map<MaterialKey, Material> sample = new HashMap<>();
        if (materials) {
            for (ProcessingCard.Amount amount : set) {
                if (!amount.isMaterial()) continue;
                List<Material> known = pool.materialsOf(amount.material());
                if (known.isEmpty()) return Push.SHORT;
                sample.put(amount.material(), known.getFirst());
            }
        }
        try (Transaction tx = Transaction.openRoot()) {
            if (!insertSet(set, inlet, tank, vessels, sample::get, tx)) return Push.REFUSED;
            // not committed: this was only asking
        }
        List<Map<Material, Long>> taken = new ArrayList<>();
        Map<MaterialKey, Map<Material, Long>> pieces = new HashMap<>();
        for (ProcessingCard.Amount amount : set) {
            if (!amount.isMaterial()) continue;
            Map<Material, Long> got = pool.takeMaterial(amount.material(), amount.count());
            if (got == null) {
                taken.forEach(pool::putBackMaterial);
                return Push.SHORT;
            }
            taken.add(got);
            pieces.put(amount.material(), got);
        }
        boolean in;
        try (Transaction tx = Transaction.openRoot()) {
            in = insertSet(set, inlet, tank, vessels, key -> null, pieces, tx);
            if (in) tx.commit();
        }
        if (!in) {
            taken.forEach(pool::putBackMaterial);
            return Push.REFUSED;
        }
        port.transferred(count);
        return Push.SENT;
    }

    private static boolean insertSet(List<ProcessingCard.Amount> set, @Nullable ResourceHandler<ItemResource> inlet,
            @Nullable ResourceHandler<FluidResource> tank, List<MaterialInlet> vessels, Function<MaterialKey, @Nullable Material> sample,
            TransactionContext tx) {
        return insertSet(set, inlet, tank, vessels, sample, Map.of(), tx);
    }

    /** Puts the set into the machine's handlers inside {@code tx}. Materials go in by {@code pieces} if given, else by the sample. */
    private static boolean insertSet(List<ProcessingCard.Amount> set, @Nullable ResourceHandler<ItemResource> inlet,
            @Nullable ResourceHandler<FluidResource> tank, List<MaterialInlet> vessels, Function<MaterialKey, @Nullable Material> sample,
            Map<MaterialKey, Map<Material, Long>> pieces, TransactionContext tx) {
        for (ProcessingCard.Amount amount : set) {
            if (amount.isMaterial()) {
                Map<Material, Long> parts = pieces.get(amount.material());
                if (parts == null) {
                    Material stand = sample.apply(amount.material());
                    if (stand == null) return false;
                    parts = Map.of(stand, (long) amount.count());
                }
                for (var part : parts.entrySet()) {
                    Material material = part.getKey();
                    ResourceHandler<Resource> vessel = vessels.stream().filter(v -> v.kind() == material.kind()).map(MaterialInlet::handler)
                            .findFirst().orElse(null);
                    if (vessel == null || vessel.insert(material.resource(), (int) (long) part.getValue(), tx) != part.getValue()) return false;
                }
            } else {
                int taken = amount.isFluid() ? tank.insert(amount.fluid(), amount.count(), tx) : inlet.insert(amount.item(), amount.count(), tx);
                if (taken != amount.count()) return false;
            }
        }
        return true;
    }

    private static boolean containsMaterial(List<MaterialInlet> vessels, List<ProcessingCard.Amount> set) {
        for (MaterialInlet vessel : vessels) {
            ResourceHandler<Resource> handler = vessel.handler();
            for (int slot = 0; slot < handler.size(); slot++) {
                MaterialKey held = MaterialKey.of(handler.getResource(slot));
                if (held != null && handler.getAmountAsLong(slot) > 0 && set.stream().anyMatch(amount -> held.equals(amount.material()))) return true;
            }
        }
        return false;
    }

    /** Every material handler of the machine on a port's {@code side}. */
    public static List<MaterialInlet> materialInlets(AccessPortBlockEntity port, Direction side) {
        if (!(port.getLevel() instanceof ServerLevel level)) {
            return List.of();
        }
        return materialInlets(level, port.getBlockPos().relative(side), side.getOpposite());
    }

    /** Where fluids go into the machine on a port's {@code side}, or null if nothing there takes fluids. */
    public static @Nullable ResourceHandler<FluidResource> fluidInlet(AccessPortBlockEntity port, Direction side) {
        if (!(port.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        return fluidInlet(level, port.getBlockPos().relative(side), side.getOpposite());
    }

    private static boolean containsFluid(ResourceHandler<FluidResource> tank, List<ProcessingCard.Amount> set) {
        for (int slot = 0; slot < tank.size(); slot++) {
            FluidResource held = tank.getResource(slot);
            if (!held.isEmpty() && tank.getAmountAsLong(slot) > 0 && set.stream().anyMatch(amount -> held.equals(amount.fluid()))) return true;
        }
        return false;
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
