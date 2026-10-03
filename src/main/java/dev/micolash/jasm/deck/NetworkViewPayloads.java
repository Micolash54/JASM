package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import io.netty.buffer.ByteBuf;
import java.util.List;
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

    /**
     * The network as a whole. {@code state}: 0 shown, 1 not linked, 2 not reachable (terminal gone or unloaded),
     * 3 no access. {@code level} is the leading brain's, or -1 without one.
     */
    public record Header(int state, int level, int count, int limit, boolean stopped, boolean brainUnpowered, boolean partial) {
        static final StreamCodec<ByteBuf, Header> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Header::state,
                ByteBufCodecs.VAR_INT, Header::level,
                ByteBufCodecs.VAR_INT, Header::count,
                ByteBufCodecs.VAR_INT, Header::limit,
                ByteBufCodecs.BOOL, Header::stopped,
                ByteBufCodecs.BOOL, Header::brainUnpowered,
                ByteBufCodecs.BOOL, Header::partial,
                Header::new);

        public static Header of(int state) {
            return new Header(state, -1, 0, 0, false, false, false);
        }
    }

    /**
     * One row: the row it hangs under (-1 for the first), how deep it sits, whether it is a junction, what it looks
     * like and where it is. {@code status}: 0 working, 1 stopped or no power, 2 not loaded.
     */
    public record Node(int parent, int depth, boolean junction, ItemStack icon, Component name, BlockPos pos,
            ResourceKey<Level> dimension, int status) {
        static final StreamCodec<RegistryFriendlyByteBuf, Node> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Node::parent,
                ByteBufCodecs.VAR_INT, Node::depth,
                ByteBufCodecs.BOOL, Node::junction,
                ItemStack.OPTIONAL_STREAM_CODEC, Node::icon,
                ComponentSerialization.STREAM_CODEC, Node::name,
                BlockPos.STREAM_CODEC, Node::pos,
                ResourceKey.streamCodec(Registries.DIMENSION), Node::dimension,
                ByteBufCodecs.VAR_INT, Node::status,
                Node::new);
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

    /** Client → server: light up the block at {@code pos}. */
    public record Locate(int containerId, BlockPos pos) implements CustomPacketPayload {
        public static final Type<Locate> TYPE = new Type<>(Jasm.id("network_view_locate"));
        public static final StreamCodec<ByteBuf, Locate> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Locate::containerId,
                BlockPos.STREAM_CODEC, Locate::pos,
                Locate::new);

        @Override
        public Type<Locate> type() {
            return TYPE;
        }
    }

    /** Server → client: outline this block for a while, through walls. */
    public record Glow(BlockPos pos, ResourceKey<Level> dimension) implements CustomPacketPayload {
        public static final Type<Glow> TYPE = new Type<>(Jasm.id("network_glow"));
        public static final StreamCodec<ByteBuf, Glow> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Glow::pos,
                ResourceKey.streamCodec(Registries.DIMENSION), Glow::dimension,
                Glow::new);

        @Override
        public Type<Glow> type() {
            return TYPE;
        }
    }
}
