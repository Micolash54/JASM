package dev.micolash.jasm.chunkload;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.ArchiveRecord.Placement;
import dev.micolash.jasm.tether.NetworkTetherBlockEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * The chunks each Network Tether keeps loaded, kept with the world, so they stay loaded while their owners are away and
 * come back by themselves after a restart. Several Tethers may hold one chunk: it has one ticket, filed under the
 * chunk's own corner, for as long as any of them holds it. Mobs spawn in held chunks as they do near a player.
 *
 * <p>Tethers say what they hold during their own tick; the tickets follow at the end of the tick. A chunk no ticket
 * holds yet is taken only a few a tick, as taking one loads it on the spot. Server thread only.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class ChunkLoaders extends SavedData {
    /** Loads the chunks Tethers hold, each owned by the chunk's own corner, whichever Tethers hold it. */
    public static final TicketController TICKETS = new TicketController(Jasm.id("network_tether"), ChunkLoaders::validate);
    /** New chunks taken a tick, across every Tether. Chunks that already have a ticket are free to share. */
    public static final int NEW_PER_TICK = 2;
    /** How often the list checks that each Tether it knows is still there, where its chunk is loaded. */
    private static final int SWEEP_TICKS = 100;

    /** One chunk in one dimension. */
    public record ChunkKey(ResourceKey<Level> dimension, long chunk) {
        static final Codec<ChunkKey> CODEC = RecordCodecBuilder.create(i -> i.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ChunkKey::dimension),
                Codec.LONG.fieldOf("chunk").forGetter(ChunkKey::chunk))
                .apply(i, ChunkKey::new));

        public static ChunkKey of(Level level, BlockPos pos) {
            return new ChunkKey(level.dimension(), ChunkPos.containing(pos).pack());
        }
    }

    private record Saved(Placement at, List<ChunkKey> chunks) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Placement.CODEC.fieldOf("at").forGetter(Saved::at),
                ChunkKey.CODEC.listOf().optionalFieldOf("chunks", List.of()).forGetter(Saved::chunks))
                .apply(i, Saved::new));
    }

    public static final Codec<ChunkLoaders> CODEC = RecordCodecBuilder.create(i -> i.group(
            Saved.CODEC.listOf().optionalFieldOf("tethers", List.of()).forGetter(ChunkLoaders::saved))
            .apply(i, ChunkLoaders::new));
    public static final SavedDataType<ChunkLoaders> TYPE = new SavedDataType<>(Jasm.id("network_tethers"), ChunkLoaders::new, CODEC);

    /** What each Tether holds. */
    private final Map<Placement, Set<ChunkKey>> held = new LinkedHashMap<>();

    // Worked out from the above, never saved.
    /** How many Tethers hold each chunk. Its keys are the chunks that should have a ticket. */
    private final Map<ChunkKey, Integer> holders = new HashMap<>();
    /** The chunks this list has a ticket for. After a load it starts empty and every held chunk is asked for again. */
    private final Set<ChunkKey> tickets = new LinkedHashSet<>();
    /** Chunks whose holders went from none to some or back since the tickets were last brought in line. */
    private final Set<ChunkKey> touched = new LinkedHashSet<>();
    private long budgetTick = -1;
    private int budget;

    public ChunkLoaders() {}

    private ChunkLoaders(List<Saved> saved) {
        for (Saved one : saved) {
            Set<ChunkKey> chunks = new LinkedHashSet<>(one.chunks());
            held.put(one.at(), chunks);
            chunks.forEach(this::addHolder);
        }
    }

    private List<Saved> saved() {
        List<Saved> out = new ArrayList<>(held.size());
        held.forEach((at, chunks) -> out.add(new Saved(at, List.copyOf(chunks))));
        return out;
    }

    /** The list, made the first time a Tether holds a chunk. */
    public static ChunkLoaders get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    private static @Nullable ChunkLoaders peek(MinecraftServer server) {
        return server.getDataStorage().get(TYPE);
    }

    private static Placement placement(ServerLevel level, BlockPos pos) {
        return new Placement(level.dimension(), pos.immutable());
    }

    // --- what Tethers tell the list ---

    /** The chunks the Tether at {@code pos} holds; empty if none. */
    public Set<ChunkKey> held(ServerLevel level, BlockPos pos) {
        Set<ChunkKey> chunks = held.get(placement(level, pos));
        return chunks == null ? Set.of() : Collections.unmodifiableSet(chunks);
    }

    /** How many chunks the Tether at {@code pos} holds, wherever it is asked from. */
    public static int count(ServerLevel level, BlockPos pos) {
        ChunkLoaders data = peek(level.getServer());
        return data == null ? 0 : data.held(level, pos).size();
    }

    /**
     * The Tether at {@code pos} takes what {@code wanted} has that it doesn't hold yet: at once for a chunk that already
     * has a ticket, otherwise only while this tick has room for new ones. With {@code trim} it also lets go of every
     * chunk {@code wanted} lacks. Returns whether it now holds all of {@code wanted}.
     */
    public boolean hold(ServerLevel level, BlockPos pos, Collection<ChunkKey> wanted, boolean trim) {
        Placement at = placement(level, pos);
        Set<ChunkKey> chunks = held.get(at);
        if (chunks == null) {
            if (wanted.isEmpty()) return true;
            chunks = new LinkedHashSet<>();
            held.put(at, chunks);
        }
        long now = level.getServer().getTickCount();
        if (budgetTick != now) {
            budgetTick = now;
            budget = NEW_PER_TICK;
        }
        // its own chunk is loaded, as it is ticking, and is always taken: a Tether holding chunks keeps itself loaded
        ChunkKey own = ChunkKey.of(level, pos);
        boolean all = true;
        boolean changed = false;
        for (ChunkKey key : wanted) {
            if (chunks.contains(key)) continue;
            boolean free = key.equals(own) || holders.containsKey(key) || tickets.contains(key);
            if (!free && budget <= 0) {
                all = false;
                continue;
            }
            if (!free) budget--;
            chunks.add(key);
            addHolder(key);
            changed = true;
        }
        if (trim) {
            Set<ChunkKey> keep = wanted instanceof Set<ChunkKey> set ? set : new LinkedHashSet<>(wanted);
            for (Iterator<ChunkKey> it = chunks.iterator(); it.hasNext(); ) {
                ChunkKey key = it.next();
                if (!keep.contains(key)) {
                    it.remove();
                    removeHolder(key);
                    changed = true;
                }
            }
        }
        if (chunks.isEmpty()) held.remove(at);
        if (changed) setDirty();
        return all;
    }

    /** The Tether at {@code pos} lets go of everything it holds. */
    public void release(ServerLevel level, BlockPos pos) {
        Set<ChunkKey> chunks = held.remove(placement(level, pos));
        if (chunks == null) return;
        chunks.forEach(this::removeHolder);
        setDirty();
    }

    /** The Tether at {@code pos} went away: what it held goes with it. */
    public static void removed(ServerLevel level, BlockPos pos) {
        ChunkLoaders data = peek(level.getServer());
        if (data != null) data.release(level, pos);
    }

    /**
     * Whether every chunk the Tether at {@code pos} holds is loaded right now. Until then its network may look smaller than
     * it is (just after a restart, its far blocks are still coming back), so nothing should be let go.
     */
    public boolean allLoaded(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        for (ChunkKey key : held(level, pos)) {
            ServerLevel there = server.getLevel(key.dimension());
            if (there == null) continue;
            ChunkPos chunk = ChunkPos.unpack(key.chunk());
            if (!there.hasChunk(chunk.x(), chunk.z())) return false;
        }
        return true;
    }

    /** Whether any Tether holds {@code chunk} in {@code level}, so it has a ticket from the end of this tick. */
    public boolean isHeld(ServerLevel level, long chunk) {
        return holders.containsKey(new ChunkKey(level.dimension(), chunk));
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

    /** As the world loads, before its saved tickets take effect: drops every ticket no Tether holds any more. */
    private static void validate(ServerLevel level, TicketHelper helper) {
        ChunkLoaders data = peek(level.getServer());
        helper.getBlockTickets().forEach((owner, tickets) -> {
            for (long chunk : tickets.naturalSpawning()) {
                if (data == null || !data.isHeld(level, chunk) || !owner.equals(corner(chunk))) helper.removeTicket(owner, chunk, true);
            }
            for (long chunk : tickets.normal()) helper.removeTicket(owner, chunk, false);
        });
        helper.getEntityTickets().keySet().forEach(helper::removeAllTickets);
    }

    private void tick(MinecraftServer server) {
        if (!touched.isEmpty()) applyTickets(server);
        if (server.getTickCount() % SWEEP_TICKS == 0 && !held.isEmpty()) sweep(server);
    }

    /** Takes or lets go of tickets so exactly the held chunks have one. */
    private void applyTickets(MinecraftServer server) {
        for (Iterator<ChunkKey> it = touched.iterator(); it.hasNext(); ) {
            ChunkKey key = it.next();
            boolean want = holders.containsKey(key);
            if (want == tickets.contains(key)) {
                it.remove();
                continue;
            }
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) {
                // the dimension isn't there now: try again when something else changes
                if (!want) tickets.remove(key);
                it.remove();
                continue;
            }
            ChunkPos pos = ChunkPos.unpack(key.chunk());
            TICKETS.forceChunk(level, corner(key.chunk()), pos.x(), pos.z(), want, true);
            if (want) tickets.add(key);
            else tickets.remove(key);
            it.remove();
        }
    }

    /**
     * Forgets Tethers that are no longer there, as far as can be seen without loading anything: one in a gone dimension,
     * one whose chunk is loaded but holds no Tether, and one that doesn't hold its own chunk, so it can't be ticking to
     * look after the rest.
     */
    private void sweep(MinecraftServer server) {
        List<Placement> gone = new ArrayList<>();
        held.forEach((at, chunks) -> {
            ServerLevel level = server.getLevel(at.dimension());
            if (level == null || !chunks.contains(new ChunkKey(at.dimension(), ChunkPos.containing(at.pos()).pack()))) {
                gone.add(at);
            } else if (level.isLoaded(at.pos()) && !(level.getBlockEntity(at.pos()) instanceof NetworkTetherBlockEntity)) {
                gone.add(at);
            }
        });
        for (Placement at : gone) {
            held.remove(at).forEach(this::removeHolder);
            setDirty();
        }
    }

    /** The block a chunk's ticket is filed under. */
    private static BlockPos corner(long chunk) {
        ChunkPos pos = ChunkPos.unpack(chunk);
        return new BlockPos(pos.getMinBlockX(), 0, pos.getMinBlockZ());
    }

    private void addHolder(ChunkKey key) {
        if (holders.merge(key, 1, Integer::sum) == 1) touched.add(key);
    }

    private void removeHolder(ChunkKey key) {
        Integer now = holders.computeIfPresent(key, (k, n) -> n > 1 ? n - 1 : null);
        if (now == null) touched.add(key);
    }
}
