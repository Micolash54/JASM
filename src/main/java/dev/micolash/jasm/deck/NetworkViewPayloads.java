package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.NetworkNode;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Messages between the Deck's Network tab and the server: the network's blocks, and which one to light up. */
public final class NetworkViewPayloads {
    private NetworkViewPayloads() {}

    /** Most rows sent for one network. */
    public static final int MAX_NODES = 512;

    /** The groups the tab sorts its rows into, in their usual order. */
    public enum Category {
        POWER,
        BATTERIES,
        CRAFTING,
        PORTS,
        NETWORK,
        STORAGE;

        private static final Category[] ALL = values();
        static final StreamCodec<ByteBuf, Category> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
                i -> ALL[Math.clamp(i, 0, ALL.length - 1)], Category::ordinal);

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One category as a whole: how many rows it has and the FE a tick it adds (above 0) or uses (below 0). */
    public record Group(int count, long power) {
        static final StreamCodec<ByteBuf, Group> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Group::count,
                ByteBufCodecs.VAR_LONG, Group::power,
                Group::new);
    }

    /**
     * The network as a whole. {@code state}: 0 shown, 1 not linked, 2 not reachable (terminal gone or unloaded),
     * 3 no access. {@code groups} has one entry per {@link Category}, in its order; the Batteries' power is their own
     * In minus Out (above 0 they charge, below 0 they drain). {@code stored}, {@code capacity}, {@code in} and
     * {@code out} are every Battery's together, as their screens show them.
     */
    public record Header(int state, int count, int limit, boolean partial, List<Group> groups, long stored, long capacity,
            long in, long out) {
        static final StreamCodec<ByteBuf, Header> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Header::state,
                ByteBufCodecs.VAR_INT, Header::count,
                ByteBufCodecs.VAR_INT, Header::limit,
                ByteBufCodecs.BOOL, Header::partial,
                Group.STREAM_CODEC.apply(ByteBufCodecs.list(Category.ALL.length)), Header::groups,
                ByteBufCodecs.VAR_LONG, Header::stored,
                ByteBufCodecs.VAR_LONG, Header::capacity,
                ByteBufCodecs.VAR_LONG, Header::in,
                ByteBufCodecs.VAR_LONG, Header::out,
                Header::new);

        public static Header of(int state) {
            return new Header(state, 0, 0, false, List.of(), 0, 0, 0, 0);
        }

        public Group group(Category category) {
            return category.ordinal() < groups.size() ? groups.get(category.ordinal()) : new Group(0, 0);
        }

        /** Whether the network holds as many machines as its brain allows: one more stops it. */
        public boolean full() {
            return count == limit;
        }

        /** Whether it holds more machines than its brain allows, and so has stopped. */
        public boolean over() {
            return count > limit;
        }
    }

    /** The small badge on a row that shows the block a port or Power Interface serves: which kind of port it is. */
    public enum Badge {
        ACCESS_PORT,
        POWER_INTERFACE,
        INPUT_PORT,
        OUTPUT_PORT,
        TWO_WAY_PORT,
        STORAGE_PORT;

        private static final Badge[] ALL = values();
        static final StreamCodec<ByteBuf, Badge> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
                i -> ALL[Math.clamp(i, 0, ALL.length - 1)], Badge::ordinal);

        /** The sprite's name, like {@code badge_input_port}. */
        public String sprite() {
            return "badge_" + name().toLowerCase(Locale.ROOT);
        }
    }

    /** A row standing for the block a port or Power Interface serves: the hover's first line, and the badge to draw. */
    public record Serving(Component title, Badge badge) {
        static final StreamCodec<RegistryFriendlyByteBuf, Serving> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, Serving::title,
                Badge.STREAM_CODEC, Serving::badge,
                Serving::new);
    }

    /** A Battery's charge: what it holds out of its room, and the FE a tick going in and out, as its screen shows them. */
    public record Charge(long stored, long capacity, long in, long out) {
        public static final Charge NONE = new Charge(0, 0, 0, 0);
        static final StreamCodec<ByteBuf, Charge> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Charge::stored,
                ByteBufCodecs.VAR_LONG, Charge::capacity,
                ByteBufCodecs.VAR_LONG, Charge::in,
                ByteBufCodecs.VAR_LONG, Charge::out,
                Charge::new);
    }

    /**
     * One row. A port serving a machine shows that machine's icon and name, and {@code serving} holds the port's own name
     * ("Access Port (Crusher)") and its badge; every other port and a Power Interface show the block they work with the
     * same way ("Storage Port (Chest)", "Power Interface (Energy Cell)"). Every other row has none. {@code status}: {@link #WORKING}, {@link #STOPPED} (stopped,
     * or a power source giving nothing) or {@link #IDLE} (a machine with nothing to do). {@code power} is the FE a tick
     * it adds (above 0) or uses (below 0). A Battery shows its {@code charge} instead; every other row has {@link Charge#NONE}. {@code note} is one more hover line, like a
     * Relay Chatterbox's frequency.
     */
    public record Node(Category category, ItemStack icon, Component name, Optional<Serving> serving, BlockPos pos,
            ResourceKey<Level> dimension, int status, int power, Charge charge, Optional<Component> note) {
        public static final int WORKING = 0;
        public static final int STOPPED = 1;
        public static final int IDLE = 2;

        static final StreamCodec<RegistryFriendlyByteBuf, Node> STREAM_CODEC = StreamCodec.composite(
                Category.STREAM_CODEC, Node::category,
                ItemStack.OPTIONAL_STREAM_CODEC, Node::icon,
                ComponentSerialization.STREAM_CODEC, Node::name,
                Serving.STREAM_CODEC.apply(ByteBufCodecs::optional), Node::serving,
                BlockPos.STREAM_CODEC, Node::pos,
                ResourceKey.streamCodec(Registries.DIMENSION), Node::dimension,
                ByteBufCodecs.VAR_INT, Node::status,
                ByteBufCodecs.VAR_INT, Node::power,
                Charge.STREAM_CODEC, Node::charge,
                ComponentSerialization.OPTIONAL_STREAM_CODEC, Node::note,
                Node::new);

        /** What a Battery holds; 0 for every other row. */
        public long stored() {
            return charge.stored();
        }

        public long capacity() {
            return charge.capacity();
        }

        /** The port's or Power Interface's own name with the block it serves, for the hover; empty for every other row. */
        public Optional<Component> title() {
            return serving.map(Serving::title);
        }

        /** Whether the row is a port or Power Interface showing the block it serves. */
        public boolean servesMachine() {
            return serving.isPresent();
        }
    }

    /** Client → server: the open Deck's screen wants its network's view. */
    public record Ask(int containerId) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(Jasm.id("network_view_ask"));
        public static final StreamCodec<ByteBuf, Ask> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ask::containerId, Ask::new);

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    /** Server → client: the network as the tab shows it. */
    public record View(int containerId, Header header, List<Node> nodes) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(Jasm.id("network_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, View::containerId,
                Header.STREAM_CODEC, View::header,
                Node.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_NODES)), View::nodes,
                View::new);

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    /** Client → server: light up the block at {@code at}, in its dimension. */
    public record Locate(int containerId, NetworkNode at) implements CustomPacketPayload {
        public static final Type<Locate> TYPE = new Type<>(Jasm.id("network_view_locate"));
        public static final StreamCodec<ByteBuf, Locate> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Locate::containerId,
                NetworkNode.STREAM_CODEC, Locate::at,
                Locate::new);

        @Override
        public Type<Locate> type() {
            return TYPE;
        }
    }

    /** Most blocks lit at once. */
    public static final int MAX_GLOW = 64;

    /** Server → client: outline these blocks for a while, through walls. */
    public record Glow(List<BlockPos> positions, ResourceKey<Level> dimension) implements CustomPacketPayload {
        public static final Type<Glow> TYPE = new Type<>(Jasm.id("network_glow"));
        public static final StreamCodec<ByteBuf, Glow> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_GLOW)), Glow::positions,
                ResourceKey.streamCodec(Registries.DIMENSION), Glow::dimension,
                Glow::new);

        public Glow {
            positions = List.copyOf(positions.subList(0, Math.min(positions.size(), MAX_GLOW)));
        }

        public static Glow of(BlockPos pos, ResourceKey<Level> dimension) {
            return new Glow(List.of(pos.immutable()), dimension);
        }

        @Override
        public Type<Glow> type() {
            return TYPE;
        }
    }
}
