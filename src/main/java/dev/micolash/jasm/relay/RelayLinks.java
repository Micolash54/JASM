package dev.micolash.jasm.relay;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.NetworkNode;
import dev.micolash.jasm.network.Networks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Pairs Relay Chatterboxes by frequency and runs the far link between each pair. Each owner's frequency is held by two
 * ends at most; a third set to it waits, and when one of the two is broken, retuned or found gone, the end that has
 * waited longest takes its place. A pair links once both ends are loaded, either one can run and, across dimensions,
 * one has a Dimension Upgrade; the end that can run pays for both, and the network power the link carries charges the
 * other. The link stops only when neither can run, and starts again once either can.
 *
 * <p>The block calls {@link #loaded} when it loads or is placed, {@link #changed} when its frequency, owner, upgrade or
 * ability to run changes, {@link #unloaded} when its chunk unloads and {@link #destroyed} when it is broken or replaced.
 * Unloading keeps an end's place. Nothing here loads a chunk: a saved end is only checked when its chunk is loaded
 * anyway, and given up only when the block there is gone or says otherwise. Server thread only.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class RelayLinks {
    /** FE each Chatterbox of a running link costs each tick. */
    public static final int COST_PER_BLOCK = 200;
    /** A Chatterbox with a Dimension Upgrade in its slot costs this many times as much. */
    public static final int UPGRADE_MULTIPLIER = 2;
    /** Loaded ends by where they stand. Each joins on load and leaves on unload or removal; cleared when the server stops. */
    private static final Map<NetworkNode, RelayEnd> LOADED = new HashMap<>();
    /** The far link each frequency runs right now; cleared when the server stops. */
    private static final Map<RelayPairs.Channel, Edge> LIVE = new HashMap<>();

    private RelayLinks() {}

    /** The two ends of a running link, either way round. */
    private record Edge(NetworkNode a, NetworkNode b) {
        boolean same(@Nullable Edge other) {
            return other != null && (a.equals(other.a) && b.equals(other.b) || a.equals(other.b) && b.equals(other.a));
        }
    }

    /** The block was placed, or its chunk loaded. */
    public static void loaded(RelayEnd end) {
        if (!(end.getLevel() instanceof ServerLevel level) || end.isRemoved()) {
            return;
        }
        NetworkNode node = NetworkNode.of(level, end.getBlockPos());
        LOADED.put(node, end);
        Work work = new Work(level.getServer());
        work.place(node, end);
        work.finish(true, end);
    }

    /** The block's frequency, owner, Dimension Upgrade or ability to run changed. */
    public static void changed(RelayEnd end) {
        loaded(end);
    }

    /** The block's chunk is unloading. Its place on the frequency is kept. */
    public static void unloaded(RelayEnd end) {
        if (!(end.getLevel() instanceof ServerLevel level)) {
            return;
        }
        NetworkNode node = NetworkNode.of(level, end.getBlockPos());
        LOADED.remove(node, end);
        Work work = new Work(level.getServer());
        RelayPairs.Channel channel = work.data.channelOf(node);
        if (channel != null) {
            work.touched.add(channel);
        }
        work.finish(false, null);
    }

    /** The block was broken, mined or replaced. Its place goes to the end that has waited longest. */
    public static void destroyed(RelayEnd end) {
        if (!(end.getLevel() instanceof ServerLevel level)) {
            return;
        }
        NetworkNode node = NetworkNode.of(level, end.getBlockPos());
        LOADED.remove(node, end);
        Work work = new Work(level.getServer());
        work.release(node);
        work.finish(true, null);
    }

    /** Where this end stands now. */
    public static RelayStatus status(RelayEnd end) {
        RelayPairs.Channel channel = channel(end);
        if (channel == null || !(end.getLevel() instanceof ServerLevel level)) {
            return RelayStatus.NO_FREQUENCY;
        }
        NetworkNode node = NetworkNode.of(level, end.getBlockPos());
        RelayPairs data = RelayPairs.get(level.getServer());
        List<NetworkNode> ends = data.ends(channel);
        if (!channel.equals(data.channelOf(node))) {
            return RelayStatus.NO_PARTNER;
        }
        if (!ends.contains(node)) {
            return RelayStatus.WAITING;
        }
        if (ends.size() < 2) {
            return RelayStatus.NO_PARTNER;
        }
        NetworkNode other = ends.get(0).equals(node) ? ends.get(1) : ends.get(0);
        RelayEnd partner = live(level.getServer(), other, channel);
        if (partner == null) {
            return RelayStatus.FAR_END_NOT_LOADED;
        }
        if (!reaches(node, other, end, partner)) {
            return RelayStatus.NEEDS_DIMENSION_UPGRADE;
        }
        if (!LIVE.containsKey(channel)) {
            // Either end able to run starts the link, so a pair that is there and not linked has two flat batteries.
            return RelayStatus.NEEDS_POWER;
        }
        return end.canRun() && partner.canRun() ? RelayStatus.LINKED : RelayStatus.LINKED_ONE_SIDE;
    }

    /** Where the other end of this end's running link stands, or null while it isn't linked. */
    public static @Nullable NetworkNode partner(RelayEnd end) {
        RelayPairs.Channel channel = channel(end);
        if (channel == null || !(end.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        Edge edge = LIVE.get(channel);
        if (edge == null) {
            return null;
        }
        NetworkNode node = NetworkNode.of(level, end.getBlockPos());
        return edge.a().equals(node) ? edge.b() : edge.b().equals(node) ? edge.a() : null;
    }

    /** The frequency an end's block says it is on, or null without one. */
    static RelayPairs.@Nullable Channel channel(RelayEnd end) {
        int frequency = end.frequency();
        if (frequency < 0 || frequency > RelayEnd.MAX_FREQUENCY) {
            return null;
        }
        return new RelayPairs.Channel(end.owner(), frequency);
    }

    /** Same dimension, or one end has a Dimension Upgrade. */
    private static boolean reaches(NetworkNode a, NetworkNode b, RelayEnd x, RelayEnd y) {
        return a.dimension().equals(b.dimension()) || x.hasDimensionUpgrade() || y.hasDimensionUpgrade();
    }

    /** The end at {@code node} if its block is loaded, is the one that joined, and still says {@code channel}. */
    private static @Nullable RelayEnd live(MinecraftServer server, NetworkNode node, RelayPairs.Channel channel) {
        RelayEnd end = LOADED.get(node);
        if (end == null || end.isRemoved()) {
            return null;
        }
        ServerLevel level = server.getLevel(node.dimension());
        if (level == null || !level.isLoaded(node.pos()) || level.getBlockEntity(node.pos()) != end) {
            return null;
        }
        return channel.equals(channel(end)) ? end : null;
    }

    /** One change: the saved pairs it touches, then the links and the ends to tell. */
    private static final class Work {
        final MinecraftServer server;
        final RelayPairs data;
        final Set<RelayPairs.Channel> touched = new LinkedHashSet<>();

        Work(MinecraftServer server) {
            this.server = server;
            this.data = RelayPairs.get(server);
        }

        /** Puts {@code node} on the frequency its block says, leaving any other one first. */
        void place(NetworkNode node, RelayEnd end) {
            RelayPairs.Channel want = channel(end);
            RelayPairs.Channel have = data.channelOf(node);
            if (!Objects.equals(want, have)) {
                release(node);
                if (want != null) {
                    data.join(node, want);
                }
            }
            if (want != null) {
                touched.add(want);
            }
        }

        /** Takes {@code node} off its frequency. If it was one of the two ends, the longest waiter still there steps up. */
        void release(NetworkNode node) {
            RelayPairs.Channel channel = data.channelOf(node);
            if (channel == null) {
                return;
            }
            touched.add(channel);
            if (data.leave(node) == null) {
                return;
            }
            for (NetworkNode next : data.waiting(channel)) {
                if (check(next, channel)) {
                    data.promote(next);
                    return;
                }
            }
        }

        /**
         * Whether {@code node} may keep its place on {@code channel}. Only a loaded chunk is looked at: there, a block
         * that is gone gives up its place, and one on another frequency moves to it.
         */
        boolean check(NetworkNode node, RelayPairs.Channel channel) {
            ServerLevel level = server.getLevel(node.dimension());
            if (level == null || !level.isLoaded(node.pos())) {
                return true;
            }
            return check(node, channel, level.getBlockEntity(node.pos()));
        }

        boolean check(NetworkNode node, RelayPairs.Channel channel, @Nullable BlockEntity found) {
            RelayEnd end = found instanceof RelayEnd e && !e.isRemoved() ? e : null;
            if (end != null && channel.equals(channel(end))) {
                return true;
            }
            if (LOADED.get(node) != end) {
                LOADED.remove(node);
            }
            release(node);
            RelayPairs.Channel other = end == null ? null : channel(end);
            if (other != null) {
                data.join(node, other);
                touched.add(other);
            }
            return false;
        }

        /** Checks what was touched if asked, starts or stops links, then tells the loaded ends. */
        void finish(boolean verify, @Nullable RelayEnd also) {
            if (verify) {
                Set<RelayPairs.Channel> verified = new HashSet<>();
                boolean more = true;
                while (more) {
                    more = false;
                    for (RelayPairs.Channel channel : List.copyOf(touched)) {
                        if (verified.add(channel)) {
                            more = true;
                            for (NetworkNode node : nodes(channel)) {
                                if (channel.equals(data.channelOf(node))) {
                                    check(node, channel);
                                }
                            }
                        }
                    }
                }
            }
            Map<RelayPairs.Channel, Edge> wanted = new LinkedHashMap<>();
            for (RelayPairs.Channel channel : touched) {
                wanted.put(channel, wanted(channel));
            }
            // Stop links first, so a link that moves to another frequency isn't stopped after it starts again.
            wanted.forEach((channel, want) -> {
                Edge have = LIVE.get(channel);
                if (have != null && !have.same(want)) {
                    LIVE.remove(channel);
                    Networks.unlink(server, have.a(), have.b());
                }
            });
            wanted.forEach((channel, want) -> {
                if (want != null && !want.same(LIVE.get(channel))) {
                    LIVE.put(channel, want);
                    Networks.link(server, want.a(), want.b());
                }
            });
            Set<RelayEnd> tell = new LinkedHashSet<>();
            if (also != null) {
                tell.add(also);
            }
            for (RelayPairs.Channel channel : touched) {
                for (NetworkNode node : nodes(channel)) {
                    RelayEnd end = live(server, node, channel);
                    if (end != null) {
                        tell.add(end);
                    }
                }
            }
            tell.forEach(RelayEnd::pairingChanged);
        }

        private List<NetworkNode> nodes(RelayPairs.Channel channel) {
            List<NetworkNode> nodes = new ArrayList<>(data.ends(channel));
            nodes.addAll(data.waiting(channel));
            return nodes;
        }

        /** The link {@code channel} should run now, starting or ending its pair's link as it goes. */
        private @Nullable Edge wanted(RelayPairs.Channel channel) {
            List<NetworkNode> ends = data.ends(channel);
            if (ends.size() < 2) {
                return null;
            }
            RelayEnd a = live(server, ends.get(0), channel);
            RelayEnd b = live(server, ends.get(1), channel);
            if (a == null || b == null) {
                // An end away keeps the pair as it was.
                return null;
            }
            boolean run = reaches(ends.get(0), ends.get(1), a, b) && (a.canRun() || b.canRun());
            data.setEstablished(channel, run);
            return run ? new Edge(ends.get(0), ends.get(1)) : null;
        }
    }

    /** A saved end whose chunk comes back without its block, or with it on another frequency, gives up its place. */
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        RelayPairs data = RelayPairs.get(level.getServer());
        Set<NetworkNode> here = data.in(level.dimension(), chunk.getPos().pack());
        if (here.isEmpty()) {
            return;
        }
        Work work = new Work(level.getServer());
        for (NetworkNode node : here) {
            RelayPairs.Channel channel = data.channelOf(node);
            if (channel != null) {
                work.check(node, channel, chunk.getBlockEntity(node.pos()));
            }
        }
        work.finish(false, null);
    }

    /**
     * Each running link pays for each of its two blocks once a tick, from their Link Bytelings, after topping them up
     * from their blocks: each its own {@link #cost} while both have charge, one paying for both when the other is flat.
     * A battery short of its share gives what it has and the other pays the rest. An end whose battery runs flat or comes back is told to the
     * pairing afterwards, which stops the link once neither can run.
     */
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (LIVE.isEmpty()) {
            return;
        }
        List<RelayEnd> flipped = new ArrayList<>();
        for (Edge edge : LIVE.values()) {
            pay(LOADED.get(edge.a()), LOADED.get(edge.b()), flipped);
        }
        for (RelayEnd end : flipped) {
            changed(end);
        }
    }

    private static void pay(@Nullable RelayEnd a, @Nullable RelayEnd b, List<RelayEnd> flipped) {
        boolean ranA = a != null && a.canRun();
        boolean ranB = b != null && b.canRun();
        if (a != null) a.topUp();
        if (b != null) b.topUp();
        int chargeA = a == null ? 0 : a.charge();
        int chargeB = b == null ? 0 : b.charge();
        int costA = a == null ? 0 : cost(a);
        int cost = costA + (b == null ? 0 : cost(b));
        int fromA = Math.min(chargeA, chargeA > 0 && chargeB > 0 ? costA : cost);
        int fromB = Math.min(chargeB, cost - fromA);
        fromA = Math.min(chargeA, cost - fromB);
        if (a != null) {
            if (fromA > 0) a.spend(fromA);
            if (a.canRun() != ranA) flipped.add(a);
        }
        if (b != null) {
            if (fromB > 0) b.spend(fromB);
            if (b.canRun() != ranB) flipped.add(b);
        }
    }

    /** FE {@code end} costs each tick while linked: double with a Dimension Upgrade in its slot, linking across or not. */
    public static int cost(RelayEnd end) {
        return end.hasDimensionUpgrade() ? COST_PER_BLOCK * UPGRADE_MULTIPLIER : COST_PER_BLOCK;
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        LOADED.clear();
        LIVE.clear();
    }
}
