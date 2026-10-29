package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.Networks;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * What a mined Access Port remembers, so the processing cards written for its machines find it again once it is placed
 * back on the same network: each machine's side and every spot a card may name for it, and a few blocks of the network
 * it was mined from, to know that network again. Cards themselves never change.
 */
public record PortMemory(List<Saved> machines, List<BlockPos> network) {
    /** Most spots kept for one machine (a port moved many times forgets the oldest) and most network blocks kept. */
    static final int MOST_SPOTS = 8;
    static final int MOST_MARKS = 8;

    /** One machine: the port's side it was on, its item for the tooltip (air if unknown), and the spots cards name it by. */
    public record Saved(Direction side, Item icon, List<Machines.At> spots) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Direction.CODEC.fieldOf("side").forGetter(Saved::side),
                BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("icon", Items.AIR).forGetter(Saved::icon),
                Machines.At.CODEC.listOf().fieldOf("spots").forGetter(Saved::spots))
                .apply(i, Saved::new));

        static final StreamCodec<RegistryFriendlyByteBuf, Saved> STREAM_CODEC = StreamCodec.composite(
                Direction.STREAM_CODEC, Saved::side,
                ByteBufCodecs.registry(Registries.ITEM), Saved::icon,
                Machines.At.STREAM_CODEC.apply(ByteBufCodecs.list(MOST_SPOTS)), Saved::spots,
                Saved::new);

        public Saved {
            spots = List.copyOf(spots.size() > MOST_SPOTS ? spots.subList(0, MOST_SPOTS) : spots);
        }
    }

    public static final Codec<PortMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
            Saved.CODEC.listOf().fieldOf("machines").forGetter(PortMemory::machines),
            BlockPos.CODEC.listOf().fieldOf("network").forGetter(PortMemory::network))
            .apply(i, PortMemory::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, PortMemory> STREAM_CODEC = StreamCodec.composite(
            Saved.STREAM_CODEC.apply(ByteBufCodecs.list(Direction.values().length)), PortMemory::machines,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MOST_MARKS)), PortMemory::network,
            PortMemory::new);

    public PortMemory {
        machines = List.copyOf(machines);
        network = List.copyOf(network.size() > MOST_MARKS ? network.subList(0, MOST_MARKS) : network);
    }

    /** The known machines' items, for the tooltip. */
    public List<ItemStack> icons() {
        return machines.stream().map(Saved::icon).filter(item -> item != Items.AIR).map(ItemStack::new).toList();
    }

    /** Whether {@code network} is the one the port was mined from: it still holds one of the blocks remembered. */
    boolean sameNetwork(@Nullable CableNetwork network) {
        return network != null && this.network.stream().anyMatch(network::contains);
    }

    /**
     * The side of {@code port} a remembered machine is on now. A thin port has only its face. A port that remembers one
     * machine and touches one finds it on any side; otherwise each machine is back on the side it had.
     */
    Direction sideNow(AccessPortBlockEntity port, Saved saved) {
        if (port.face() != null) return port.face();
        Direction only = machines.size() == 1 ? port.onlyMachineSide() : null;
        return only != null ? only : saved.side();
    }

    // --- mining a port ---

    /**
     * What the item of {@code port} keeps as it is mined: every side with a machine, or named by a processing card in one
     * of the network's racks, with what the port already remembered for them from the same network. Null when there is
     * nothing to keep.
     */
    static @Nullable PortMemory of(AccessPortBlockEntity port, ServerLevel level) {
        BlockPos here = port.getBlockPos();
        CableNetwork network = Networks.at(level, here);
        for (Direction side : Direction.values()) {
            if (network != null) break;
            if (level.isLoaded(here.relative(side))) network = Networks.at(level, here.relative(side));
        }
        if (network == null) return null;
        Set<Machines.At> used = new HashSet<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            for (Card card : rack.cards()) {
                if (card instanceof ProcessingCard processing) used.addAll(processing.spots());
            }
        }
        Map<Direction, LinkedHashSet<Machines.At>> spots = new EnumMap<>(Direction.class);
        Map<Direction, Item> icons = new EnumMap<>(Direction.class);
        for (Direction side : Direction.values()) {
            if (port.face() != null && port.face() != side) continue;
            Machines.At at = new Machines.At(here, side);
            if (port.hasMachine(side)) {
                icons.put(side, level.getBlockState(here.relative(side)).getBlock().asItem());
            } else if (!used.contains(at)) {
                continue;
            }
            spots.computeIfAbsent(side, s -> new LinkedHashSet<>()).add(at);
            icons.putIfAbsent(side, port.seenMachine(side));
        }
        PortMemory before = port.movedFrom();
        if (before != null && before.sameNetwork(network)) {
            for (Saved saved : before.machines) {
                Direction side = before.sideNow(port, saved);
                if (!spots.containsKey(side) && saved.spots().stream().noneMatch(used::contains)) continue;
                spots.computeIfAbsent(side, s -> new LinkedHashSet<>()).addAll(saved.spots());
                if (icons.getOrDefault(side, Items.AIR) == Items.AIR) icons.put(side, saved.icon());
            }
        }
        if (spots.isEmpty()) return null;
        List<Saved> machines = new ArrayList<>();
        spots.forEach((side, at) -> machines.add(new Saved(side, icons.getOrDefault(side, Items.AIR), List.copyOf(at))));
        return new PortMemory(machines, marks(network, here));
    }

    /** A few blocks of {@code network} to know it by later: its racks first (they hold the cards), then any others. */
    private static List<BlockPos> marks(CableNetwork network, BlockPos here) {
        Set<BlockPos> marks = new LinkedHashSet<>();
        for (RecipeRackBlockEntity rack : network.machines(RecipeRackBlockEntity.class)) {
            if (marks.size() < MOST_MARKS && !rack.getBlockPos().equals(here)) marks.add(rack.getBlockPos().immutable());
        }
        for (BlockPos pos : network.machines()) {
            if (marks.size() < MOST_MARKS && !pos.equals(here)) marks.add(pos.immutable());
        }
        for (BlockPos pos : network.cables()) {
            if (marks.size() < MOST_MARKS && !pos.equals(here)) marks.add(pos.immutable());
        }
        return List.copyOf(marks);
    }

    // --- finding a port placed back ---

    /** A spot a card names, taken over by a port placed back with a memory. */
    private record Claim(AccessPortBlockEntity port, PortMemory memory, Saved saved) {}

    /** Every spot the ports on a network placed back took over. Worked out once per network and kept with it. */
    private static final Function<CableNetwork, Map<Machines.At, Claim>> CLAIMS = network -> {
        Map<Machines.At, Claim> claims = new HashMap<>();
        for (AccessPortBlockEntity port : Machines.ports(network)) {
            PortMemory memory = port.movedFrom();
            if (memory == null || !memory.sameNetwork(network)) continue;
            for (Saved saved : memory.machines) {
                for (Machines.At at : saved.spots()) claims.putIfAbsent(at, new Claim(port, memory, saved));
            }
        }
        return claims.isEmpty() ? Map.of() : claims;
    };

    /** Where the machine a card names at {@code at} is now, if its port was mined and placed back on {@code network}. */
    static Machines.@Nullable At moved(CableNetwork network, Machines.At at) {
        Claim claim = network.derived(CLAIMS).get(at);
        if (claim == null || claim.port().isRemoved()) return null;
        return new Machines.At(claim.port().getBlockPos(), claim.memory().sideNow(claim.port(), claim.saved()));
    }
}
