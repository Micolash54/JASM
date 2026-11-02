package dev.micolash.jasm.chunkload;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.Feature;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.storage.ArchiveRecord.Placement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import org.jspecify.annotations.Nullable;

/**
 * Every Chunkload Upgrade in a machine, in the order they went in, and which of them keep a chunk loaded. In each chunk
 * the first one loads it and the rest wait; a waiting one takes over as soon as the one before it stops. Kept with the
 * world, so the chunks stay loaded while their owners are away and across restarts.
 *
 * <p>Machines only ask for their position to be looked at (an upgrade slot changed, the machine loaded, went away or
 * changed owner); the list checks those against the world once, at the end of the tick. Each tick only the loading
 * upgrades are visited, to pay for their chunk. Server thread only.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class ChunkLoaders extends SavedData {
    /** Loads one chunk, owned by the chunk's own corner, whichever upgrade pays for it. */
    public static final TicketController TICKETS = new TicketController(Jasm.id("chunkload_upgrade"), ChunkLoaders::validate);
    /** Counts toward the limit for machines with no owner. */
    private static final UUID NOBODY = new UUID(0, 0);
    private static final int RECHECK_TICKS = 20;

    /** Where an upgrade sits: a machine block, or the thin port on that face of a cable. */
    public record Spot(Placement at, @Nullable Direction face) {
        ChunkKey chunk() {
            return new ChunkKey(at.dimension(), ChunkPos.containing(at.pos()).pack());
        }
    }

    private record ChunkKey(ResourceKey<Level> dimension, long chunk) {}

    private static final class Entry {
        UUID owner;
        boolean unpowered;

        Entry(UUID owner, boolean unpowered) {
            this.owner = owner;
            this.unpowered = unpowered;
        }
    }

    private record Saved(Placement at, Optional<Direction> face, UUID owner, boolean unpowered) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Placement.CODEC.fieldOf("at").forGetter(Saved::at),
                Direction.CODEC.optionalFieldOf("face").forGetter(Saved::face),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Saved::owner),
                Codec.BOOL.optionalFieldOf("unpowered", false).forGetter(Saved::unpowered))
                .apply(i, Saved::new));
    }

    public static final Codec<ChunkLoaders> CODEC = RecordCodecBuilder.create(i -> i.group(
            Saved.CODEC.listOf().optionalFieldOf("upgrades", List.of()).forGetter(ChunkLoaders::saved))
            .apply(i, ChunkLoaders::new));
    public static final SavedDataType<ChunkLoaders> TYPE = new SavedDataType<>(Jasm.id("chunk_loaders"), ChunkLoaders::new, CODEC);

    /** What players asked for: every upgrade, first in first. */
    private final LinkedHashMap<Spot, Entry> entries = new LinkedHashMap<>();
    private final Map<Placement, Set<Spot>> byPlace = new HashMap<>();
    /** The upgrades that couldn't pay for their chunk: they step aside until their machine has the power again. */
    private final Set<Spot> unpowered = new LinkedHashSet<>();

    // Worked out from the above, never saved.
    private final Set<Placement> pending = new HashSet<>();
    /** The chunks loaded right now, each with the upgrade paying for it. Its keys are the tickets held. */
    private Map<ChunkKey, Spot> loading = Map.of();
    private final Map<Spot, MachineBlockEntity> machines = new HashMap<>();
    private boolean replan = true;
    private @Nullable Boolean lastOn;
    private int lastLimit = -1;

    public ChunkLoaders() {}

    private ChunkLoaders(List<Saved> saved) {
        for (Saved one : saved) {
            Spot spot = new Spot(one.at(), one.face().orElse(null));
            add(spot, new Entry(one.owner(), one.unpowered()));
        }
    }

    private List<Saved> saved() {
        List<Saved> out = new ArrayList<>(entries.size());
        entries.forEach((spot, entry) -> out.add(new Saved(spot.at(), Optional.ofNullable(spot.face()), entry.owner, entry.unpowered)));
        return out;
    }

    /** The list, made the first time an upgrade is seen. */
    public static ChunkLoaders get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    private static @Nullable ChunkLoaders peek(MinecraftServer server) {
        return server.getDataStorage().get(TYPE);
    }

    private static Placement placement(ServerLevel level, BlockPos pos) {
        return new Placement(level.dimension(), pos.immutable());
    }

    // --- what machines tell the list ---

    /** An upgrade slot of the machine changed. */
    public static void look(MachineBlockEntity machine) {
        if (machine.getLevel() instanceof ServerLevel level) {
            get(level.getServer()).pending.add(placement(level, machine.getBlockPos()));
        }
    }

    /** The machine loaded or was placed: worth a look if it holds the upgrade or the list knows its place. */
    public static void loaded(MachineBlockEntity machine) {
        if (ChunkloadUpgradeItem.installedIn(machine)) look(machine);
        else known(machine);
    }

    /** The machine went away or changed owner: worth a look only if the list knows its place. */
    public static void known(MachineBlockEntity machine) {
        if (!(machine.getLevel() instanceof ServerLevel level)) return;
        ChunkLoaders data = peek(level.getServer());
        if (data == null) return;
        Placement at = placement(level, machine.getBlockPos());
        if (data.byPlace.containsKey(at)) data.pending.add(at);
    }

    // --- what tests and screens can ask ---

    public enum Status { NONE, LOADING, WAITING }

    /** Whether the upgrade at {@code pos} (a thin port's {@code face}, or null for a machine block) loads its chunk, waits, or isn't listed. */
    public Status status(ServerLevel level, BlockPos pos, @Nullable Direction face) {
        Spot spot = new Spot(placement(level, pos), face);
        if (!entries.containsKey(spot)) return Status.NONE;
        return spot.equals(loading.get(spot.chunk())) ? Status.LOADING : Status.WAITING;
    }

    /** Whether a ticket for {@code chunk}, owned by {@code owner}, is one the list wants. What a loading world keeps. */
    public boolean wantsTicket(ServerLevel level, BlockPos owner, long chunk) {
        return wanted(plan(), level, owner, chunk);
    }

    private static boolean wanted(Map<ChunkKey, Spot> plan, ServerLevel level, BlockPos owner, long chunk) {
        return plan.containsKey(new ChunkKey(level.dimension(), chunk)) && owner.equals(corner(chunk));
    }

    // --- the tick ---

    @SubscribeEvent
    static void onRegisterTickets(RegisterTicketControllersEvent event) {
        event.register(TICKETS);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        ChunkLoaders data = peek(event.getServer());
        if (data != null) data.tick(event.getServer());
    }

    /** As the world loads, before its saved tickets take effect: drops every ticket the list no longer backs. */
    private static void validate(ServerLevel level, TicketHelper helper) {
        ChunkLoaders data = peek(level.getServer());
        Map<ChunkKey, Spot> plan = data == null ? Map.of() : data.plan();
        helper.getBlockTickets().forEach((owner, tickets) -> {
            for (long chunk : tickets.normal()) {
                if (!wanted(plan, level, owner, chunk)) helper.removeTicket(owner, chunk, false);
            }
            for (long chunk : tickets.naturalSpawning()) helper.removeTicket(owner, chunk, true);
        });
        helper.getEntityTickets().keySet().forEach(helper::removeAllTickets);
    }

    private void tick(MinecraftServer server) {
        boolean on = Feature.CHUNKLOAD_UPGRADE.on();
        int limit = JasmConfig.CHUNKS_PER_PLAYER.getAsInt();
        if (lastOn == null || on != lastOn || limit != lastLimit) {
            lastOn = on;
            lastLimit = limit;
            replan = true;
        }
        if (!pending.isEmpty()) checkPending(server);
        if (on && !unpowered.isEmpty() && server.getTickCount() % RECHECK_TICKS == 0) recheckUnpowered(server);
        if (replan) assign(server);
        if (pay(server)) assign(server);
    }

    /** Matches the list to what now stands at each position a machine asked about. Unloaded positions wait for their chunk. */
    private void checkPending(MinecraftServer server) {
        for (Placement at : pending) {
            ServerLevel level = server.getLevel(at.dimension());
            if (level == null || !level.isLoaded(at.pos())) continue;
            Map<Spot, MachineBlockEntity> found = installedAt(level, at);
            for (Spot spot : List.copyOf(byPlace.getOrDefault(at, Set.of()))) {
                if (!found.containsKey(spot)) {
                    remove(spot);
                    replan = true;
                }
            }
            found.forEach((spot, machine) -> {
                UUID owner = machine.owner() == null ? NOBODY : machine.owner();
                Entry entry = entries.get(spot);
                if (entry == null) {
                    add(spot, new Entry(owner, false));
                    replan = true;
                } else if (!entry.owner.equals(owner)) {
                    entry.owner = owner;
                    setDirty();
                    replan = true;
                }
            });
        }
        pending.clear();
    }

    /** Upgrades that stepped aside for lack of power go back in line, at the end, once their machine can pay again. */
    private void recheckUnpowered(MinecraftServer server) {
        List<Spot> back = new ArrayList<>();
        for (Spot spot : unpowered) {
            ServerLevel level = server.getLevel(spot.at().dimension());
            if (level == null || !level.isLoaded(spot.at().pos())) continue;
            MachineBlockEntity machine = machineAt(level, spot);
            if (machine != null && machine.chunkloadPower().getAmountAsInt() >= Tuning.CHUNKLOAD_UPGRADE_DRAIN) back.add(spot);
        }
        for (Spot spot : back) {
            Entry entry = entries.remove(spot);
            entry.unpowered = false;
            entries.put(spot, entry);
            unpowered.remove(spot);
        }
        if (!back.isEmpty()) {
            setDirty();
            replan = true;
        }
    }

    /** Who loads which chunk: first in line per chunk, among those with power and owners under the limit. */
    private Map<ChunkKey, Spot> plan() {
        if (!Feature.CHUNKLOAD_UPGRADE.on()) return Map.of();
        int limit = JasmConfig.CHUNKS_PER_PLAYER.getAsInt();
        Map<ChunkKey, Spot> next = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        entries.forEach((spot, entry) -> {
            if (entry.unpowered) return;
            ChunkKey key = spot.chunk();
            if (next.containsKey(key)) return;
            if (limit > 0 && counts.getOrDefault(entry.owner, 0) >= limit) return;
            next.put(key, spot);
            counts.merge(entry.owner, 1, Integer::sum);
        });
        return next;
    }

    /** Takes or lets go of tickets so exactly the planned chunks are held. A chunk changing hands keeps its ticket. */
    private void assign(MinecraftServer server) {
        replan = false;
        Map<ChunkKey, Spot> next = plan();
        for (ChunkKey key : next.keySet()) {
            if (!loading.containsKey(key)) force(server, key, true);
        }
        for (ChunkKey key : loading.keySet()) {
            if (!next.containsKey(key)) force(server, key, false);
        }
        loading = next;
        machines.keySet().retainAll(next.values());
    }

    /** Each loading upgrade pays for its tick. Returns whether one had to stop: its machine is gone or out of power. */
    private boolean pay(MinecraftServer server) {
        int cost = Tuning.CHUNKLOAD_UPGRADE_DRAIN;
        boolean changed = false;
        for (Spot spot : loading.values()) {
            ServerLevel level = server.getLevel(spot.at().dimension());
            if (level == null || !level.isLoaded(spot.at().pos())) continue;
            MachineBlockEntity machine = machines.get(spot);
            if (machine == null || machine.isRemoved()) {
                machine = machineAt(level, spot);
                if (machine == null) {
                    remove(spot);
                    changed = true;
                    continue;
                }
                machines.put(spot, machine);
            }
            SimpleEnergyHandler power = machine.chunkloadPower();
            int amount = power.getAmountAsInt();
            if (amount < cost) {
                entries.get(spot).unpowered = true;
                unpowered.add(spot);
                setDirty();
                changed = true;
            } else if (cost > 0) {
                power.set(amount - cost);
            }
        }
        return changed;
    }

    private static void force(MinecraftServer server, ChunkKey key, boolean add) {
        ServerLevel level = server.getLevel(key.dimension());
        if (level == null) return;
        ChunkPos pos = ChunkPos.unpack(key.chunk());
        TICKETS.forceChunk(level, corner(key.chunk()), pos.x(), pos.z(), add, false);
    }

    /** The block a chunk's ticket is filed under. */
    private static BlockPos corner(long chunk) {
        ChunkPos pos = ChunkPos.unpack(chunk);
        return new BlockPos(pos.getMinBlockX(), 0, pos.getMinBlockZ());
    }

    // --- the list ---

    private void add(Spot spot, Entry entry) {
        entries.put(spot, entry);
        byPlace.computeIfAbsent(spot.at(), at -> new HashSet<>()).add(spot);
        if (entry.unpowered) unpowered.add(spot);
        setDirty();
    }

    private void remove(Spot spot) {
        if (entries.remove(spot) == null) return;
        Set<Spot> here = byPlace.get(spot.at());
        if (here != null && here.remove(spot) && here.isEmpty()) byPlace.remove(spot.at());
        unpowered.remove(spot);
        machines.remove(spot);
        setDirty();
    }

    /** The machines at {@code at} holding the upgrade: the machine block, or each thin port of the cable there. */
    private static Map<Spot, MachineBlockEntity> installedAt(ServerLevel level, Placement at) {
        BlockEntity entity = level.getBlockEntity(at.pos());
        if (entity instanceof MachineBlockEntity machine) {
            return ChunkloadUpgradeItem.installedIn(machine) ? Map.of(new Spot(at, null), machine) : Map.of();
        }
        if (!(entity instanceof DataCableBlockEntity cable)) return Map.of();
        Map<Spot, MachineBlockEntity> found = new HashMap<>();
        for (Direction side : Direction.values()) {
            MachineBlockEntity port = cable.port(side);
            if (port != null && ChunkloadUpgradeItem.installedIn(port)) found.put(new Spot(at, side), port);
        }
        return found;
    }

    private static @Nullable MachineBlockEntity machineAt(ServerLevel level, Spot spot) {
        BlockEntity entity = level.getBlockEntity(spot.at().pos());
        if (spot.face() == null) return entity instanceof MachineBlockEntity machine ? machine : null;
        return entity instanceof DataCableBlockEntity cable ? cable.port(spot.face()) : null;
    }
}
