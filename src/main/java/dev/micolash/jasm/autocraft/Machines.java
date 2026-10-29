package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.MachineFace;
import dev.micolash.jasm.network.MachineSides;
import dev.micolash.jasm.pool.Material;
import dev.micolash.jasm.pool.MaterialKinds;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.wafer.FluidAmounts;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import net.minecraft.world.level.block.entity.BlockEntity;
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

    /**
     * Where the machine a card names at {@code at} is now: there while a port stands there, or where its port was placed
     * back on the same network after being mined.
     */
    public static At current(ServerLevel level, @Nullable CableNetwork network, At at) {
        if (network == null || port(level, network, at.port(), at.side()) != null) {
            return at;
        }
        At moved = PortMemory.moved(network, at);
        return moved != null ? moved : at;
    }

    /** {@code card} with each machine where it is now; the card itself when none moved. */
    public static ProcessingCard located(ServerLevel level, @Nullable CableNetwork network, ProcessingCard card) {
        if (network == null) {
            return card;
        }
        List<ProcessingCard.Machine> machines = null;
        for (int i = 0; i < card.machines().size(); i++) {
            ProcessingCard.Machine machine = card.machines().get(i);
            At now = current(level, network, machine.at());
            if (!now.equals(machine.at())) {
                if (machines == null) {
                    machines = new ArrayList<>(card.machines());
                }
                machines.set(i, new ProcessingCard.Machine(now.port(), now.side(), machine.name()));
            }
        }
        return machines == null ? card : new ProcessingCard(card.inputs(), card.outputs(), machines, card.faces());
    }

    /** The machines {@code card} may use, where they are now, each once. */
    public static List<At> spots(ServerLevel level, @Nullable CableNetwork network, ProcessingCard card) {
        ProcessingCard here = located(level, network, card);
        return here == card ? card.spots() : here.spots().stream().distinct().toList();
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
            // Another crafting block is no machine. The ones with an I/O grid are, through the faces it opens.
            return null;
        }
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        if (handler != null || level.getBlockEntity(pos) instanceof MachineBlockEntity) {
            // a face the grid keeps shut is shut, even though the machine is a container underneath
            return handler;
        }
        if (level.getBlockEntity(pos) instanceof WorldlyContainer worldly) {
            return new WorldlyContainerWrapper(worldly, side);
        }
        return level.getBlockEntity(pos) instanceof Container container ? VanillaContainerWrapper.of(container) : null;
    }

    /**
     * Whether the machine on the port's {@code side} turns {@code other} away for good where the card's {@code own} item
     * would go in: none of its slots may ever hold {@code other} though one may hold {@code own}, or it stands empty and
     * would take {@code own} but not {@code other}. A machine that is only busy with something else turns nothing away.
     */
    public static boolean turnsAway(AccessPortBlockEntity port, Direction side, ProcessingCard.Amount own, ProcessingCard.Amount other) {
        return turnsAway(port, side, InputFace.DEFAULT, own, other);
    }

    /** As above, where the card sends this ingredient through {@code face}. */
    public static boolean turnsAway(AccessPortBlockEntity port, Direction side, InputFace face, ProcessingCard.Amount own,
            ProcessingCard.Amount other) {
        ResourceHandler<ItemResource> inlet = port.getLevel() instanceof ServerLevel level
                ? inlet(level, port.getBlockPos().relative(side), face(side, face))
                : null;
        if (inlet == null || own.item().isEmpty() || other.item().isEmpty()) {
            return false;
        }
        boolean ownFits = false;
        boolean otherFits = false;
        boolean empty = true;
        for (int i = 0; i < inlet.size(); i++) {
            ownFits |= inlet.isValid(i, own.item());
            otherFits |= inlet.isValid(i, other.item());
            empty &= inlet.getResource(i).isEmpty() || inlet.getAmountAsLong(i) <= 0;
        }
        if (ownFits && !otherFits) {
            return true;
        }
        if (!empty) {
            return false;
        }
        try (Transaction tx = Transaction.openRoot()) {
            if (inlet.insert(own.item(), own.count(), tx) != own.count()) {
                return false;
            }
        }
        try (Transaction tx = Transaction.openRoot()) {
            return inlet.insert(other.item(), other.count(), tx) != other.count();
        }
    }

    /** Where fluids go into the block at {@code pos} through its {@code side}, or null if it takes none. */
    public static @Nullable ResourceHandler<FluidResource> fluidInlet(Level level, BlockPos pos, Direction side) {
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity machine && !machine.opensToPorts()
                || level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
            return null;
        }
        return level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
    }

    /**
     * Whether the block at {@code pos} is a machine with an I/O grid whose {@code side} lets in none of what a set brings.
     * Set to Output, that face still shows a handler, so asking it alone would read as a full machine.
     */
    public static boolean shut(Level level, BlockPos pos, Direction side, boolean items, boolean fluids) {
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine) || machine.sides() == null) {
            return false;
        }
        MachineSides sides = machine.sides();
        MachineFace face = MachineFace.of(machine.frontSide(), side);
        return items && !sides.mode(MachineSides.Kind.ITEMS, face).in()
                || fluids && sides.hasFluids() && !sides.mode(MachineSides.Kind.FLUIDS, face).in();
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
        /** The machine is full, or doesn't take these. Nothing moved. */
        REFUSED,
        /** The network's storage holds too little of a material the set needs. Nothing moved. */
        SHORT,
        /** The port isn't due to move anything yet, or is saving up for a big set. Nothing was tried. */
        WAIT,
        /** Blocking Mode: the machine still holds ingredients from before. Nothing moved. */
        BUSY,
        /** The machine takes nothing of this kind through that side. Nothing moved. */
        SHUT
    }

    /**
     * Puts one set of ingredients into the machine on the port's {@code side}: all of it, or nothing at all. Returns
     * whether it went in. The machine decides where each item goes, as with a hopper. A set with materials needs
     * {@link #push(AccessPortBlockEntity, Direction, List, NetworkPool)}.
     */
    public static boolean push(AccessPortBlockEntity port, Direction side, List<ProcessingCard.Amount> set) {
        return push(port, side, set, null) == Push.SENT;
    }

    /** As below, every ingredient through the face the port touches. */
    public static Push push(AccessPortBlockEntity port, Direction side, List<ProcessingCard.Amount> set, @Nullable NetworkPool pool) {
        return push(port, side, set, List.of(), pool);
    }

    /**
     * Puts one set into the machine on the port's {@code side}, each ingredient through its face ({@code faces} goes
     * with {@code set} entry by entry; a missing one is Default, the face the port touches). Materials come out of
     * {@code pool} (the network's storage blocks). The machine is asked first with nothing taken; the pool then gives up
     * exactly what the set needs or nothing; the machine takes it, and if it somehow doesn't, everything goes back to the
     * pool. One transaction covers every face, so a face that refuses keeps the whole set out.
     */
    public static Push push(AccessPortBlockEntity port, Direction side, List<ProcessingCard.Amount> set, List<InputFace> faces,
            @Nullable NetworkPool pool) {
        Level level = port.getLevel();
        if (!(level instanceof ServerLevel)) {
            return Push.SHUT;
        }
        BlockPos pos = port.getBlockPos().relative(side);
        // Which face each entry goes through, and what kinds go through each face.
        List<Direction> through = new ArrayList<>(set.size());
        Map<Direction, Kinds> kinds = new LinkedHashMap<>();
        // A fluid or material counts in shares (an eighth of a bucket each) against the port's allowance, like an item.
        long shares = 0;
        boolean materials = false;
        for (int i = 0; i < set.size(); i++) {
            ProcessingCard.Amount amount = set.get(i);
            Direction face = face(side, i < faces.size() ? faces.get(i) : InputFace.DEFAULT);
            through.add(face);
            Kinds here = kinds.computeIfAbsent(face, f -> new Kinds());
            if (amount.isMaterial()) {
                here.materials = materials = true;
                shares += FluidAmounts.shares(amount.count());
            } else if (amount.isFluid()) {
                here.fluids = true;
                shares += FluidAmounts.shares(amount.count());
            } else {
                here.items = true;
                shares += amount.count();
            }
        }
        int count = (int) Math.min(shares, Integer.MAX_VALUE);
        for (Map.Entry<Direction, Kinds> entry : kinds.entrySet()) {
            if (shut(level, pos, entry.getKey(), entry.getValue().items, entry.getValue().fluids)) {
                return Push.SHUT;
            }
        }
        if (!port.canSendBatch(count)) return Push.WAIT;
        Map<Direction, Handlers> handlers = new HashMap<>();
        for (Map.Entry<Direction, Kinds> entry : kinds.entrySet()) {
            Direction face = entry.getKey();
            Kinds wanted = entry.getValue();
            Handlers found = new Handlers(wanted.items ? inlet(level, pos, face) : null, wanted.fluids ? fluidInlet(level, pos, face) : null,
                    wanted.materials ? materialInlets(level, pos, face) : List.of());
            if (wanted.items && found.inlet() == null || wanted.fluids && found.tank() == null || wanted.materials && found.vessels().isEmpty()) {
                return Push.SHUT;
            }
            handlers.put(face, found);
        }
        if (materials && pool == null) return Push.SHORT;
        if (port.blockingMode()) {
            // Every face the set uses is looked at: the machine may show its ingredients on any of them.
            for (Handlers face : handlers.values()) {
                if (face.inlet() != null && containsIngredient(face.inlet(), set) || face.tank() != null && containsFluid(face.tank(), set)
                        || !face.vessels().isEmpty() && containsMaterial(face.vessels(), set)) {
                    return Push.BUSY;
                }
            }
        }
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
            if (!insertSet(set, through, handlers, sample::get, List.of(), tx)) return Push.REFUSED;
            // not committed: this was only asking
        }
        // What the pool gave for each entry of the set, in set order (nothing for items and fluids).
        List<@Nullable Map<Material, Long>> pieces = new ArrayList<>(set.size());
        for (ProcessingCard.Amount amount : set) {
            if (!amount.isMaterial()) {
                pieces.add(null);
                continue;
            }
            Map<Material, Long> got = pool.takeMaterial(amount.material(), amount.count());
            if (got == null) {
                pieces.stream().filter(java.util.Objects::nonNull).forEach(pool::putBackMaterial);
                return Push.SHORT;
            }
            pieces.add(got);
        }
        boolean in;
        try (Transaction tx = Transaction.openRoot()) {
            in = insertSet(set, through, handlers, key -> null, pieces, tx);
            if (in) tx.commit();
        }
        if (!in) {
            pieces.stream().filter(java.util.Objects::nonNull).forEach(pool::putBackMaterial);
            return Push.REFUSED;
        }
        port.transferred(count);
        return Push.SENT;
    }

    /** The face of the machine on a port's {@code side} that an input set to {@code face} goes in through. */
    public static Direction face(Direction side, InputFace face) {
        return face.direction() != null ? face.direction() : side.getOpposite();
    }

    /**
     * Whether the block at {@code pos} takes anything (items, fluids or materials) through any of its faces. A card can
     * send its ingredients through faces the port doesn't touch. Only blocks with a block entity are asked, since ports
     * ask this of every neighbour every half second. JASM's own machines and cables don't count: their I/O grid says
     * which faces the port may use.
     */
    public static boolean takesAnywhere(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity instanceof MachineBlockEntity) {
            return false;
        }
        for (Direction face : Direction.values()) {
            if (inlet(level, pos, face) != null || fluidInlet(level, pos, face) != null || !materialInlets(level, pos, face).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** What kinds of ingredient go through one face. */
    private static final class Kinds {
        boolean items;
        boolean fluids;
        boolean materials;
    }

    /** One face's handlers: null or empty for a kind the set doesn't bring through it. */
    private record Handlers(@Nullable ResourceHandler<ItemResource> inlet, @Nullable ResourceHandler<FluidResource> tank,
            List<MaterialInlet> vessels) {}

    /**
     * Puts the set into the machine inside {@code tx}, each entry through its face's handlers. Materials go in by what
     * the pool gave for that entry ({@code pieces}) if given, else by the sample.
     */
    private static boolean insertSet(List<ProcessingCard.Amount> set, List<Direction> through, Map<Direction, Handlers> handlers,
            Function<MaterialKey, @Nullable Material> sample, List<@Nullable Map<Material, Long>> pieces, TransactionContext tx) {
        for (int i = 0; i < set.size(); i++) {
            ProcessingCard.Amount amount = set.get(i);
            Handlers face = handlers.get(through.get(i));
            if (amount.isMaterial()) {
                Map<Material, Long> parts = i < pieces.size() ? pieces.get(i) : null;
                if (parts == null) {
                    Material stand = sample.apply(amount.material());
                    if (stand == null) return false;
                    parts = Map.of(stand, (long) amount.count());
                }
                for (var part : parts.entrySet()) {
                    Material material = part.getKey();
                    ResourceHandler<Resource> vessel = face.vessels().stream().filter(v -> v.kind() == material.kind()).map(MaterialInlet::handler)
                            .findFirst().orElse(null);
                    if (vessel == null || vessel.insert(material.resource(), (int) (long) part.getValue(), tx) != part.getValue()) return false;
                }
            } else {
                int taken = amount.isFluid() ? face.tank().insert(amount.fluid(), amount.count(), tx) : face.inlet().insert(amount.item(), amount.count(), tx);
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
