package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckPayloads;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import dev.micolash.jasm.network.TrustList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Messages between a Crafting Deck's screen and the server about autocrafting. */
public final class CraftPayloads {
    private CraftPayloads() {}

    /** Most entries of one list sent to the screen. */
    public static final int MAX_LIST = 1_024;

    /** One job of this Deck as the screen shows it. {@code phase}: 0 crafting, 1 stopping, 2 returning. */
    /** {@code waiting}: the machine it has waited on longest, as a line to show. */
    public record JobView(BlockPos server, ItemResource target, long amount, int phase, int progress, int pause, Optional<Component> waiting) {
        static final StreamCodec<RegistryFriendlyByteBuf, JobView> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, JobView::server,
                ItemResource.STREAM_CODEC, JobView::target,
                ByteBufCodecs.VAR_LONG, JobView::amount,
                ByteBufCodecs.VAR_INT, JobView::phase,
                ByteBufCodecs.VAR_INT, JobView::progress,
                ByteBufCodecs.VAR_INT, JobView::pause,
                ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), JobView::waiting,
                JobView::new);
    }

    /** Server → client: what the open Crafting Server's job waits on at a machine, if anything. */
    public record ServerWaiting(int containerId, Optional<Component> line) implements CustomPacketPayload {
        public static final Type<ServerWaiting> TYPE = new Type<>(Jasm.id("server_waiting"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerWaiting> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ServerWaiting::containerId,
                ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), ServerWaiting::line,
                ServerWaiting::new);

        @Override
        public Type<ServerWaiting> type() {
            return TYPE;
        }
    }

    /** A server the request could go to. */
    public record ServerView(BlockPos pos, int memory, int parallel, boolean busy, boolean fits) {
        static final StreamCodec<RegistryFriendlyByteBuf, ServerView> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ServerView::pos,
                ByteBufCodecs.VAR_INT, ServerView::memory,
                ByteBufCodecs.VAR_INT, ServerView::parallel,
                ByteBufCodecs.BOOL, ServerView::busy,
                ByteBufCodecs.BOOL, ServerView::fits,
                ServerView::new);
    }

    /**
     * Server → client, every second while a Crafting Deck is open: whether its network can be reached, what it can
     * make, and this Deck's jobs. {@code network}: 0 not paired, 1 out of reach, 2 reachable.
     */
    /** {@code stalled}: the Deck's rules that can't go ahead right now, each with the message saying why. */
    public record Status(int containerId, int network, List<ItemResource> craftable, List<JobView> jobs, Map<UUID, String> stalled)
            implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(Jasm.id("craft_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Status::containerId,
                ByteBufCodecs.VAR_INT, Status::network,
                ItemResource.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LIST)), Status::craftable,
                JobView.STREAM_CODEC.apply(ByteBufCodecs.list(64)), Status::jobs,
                ByteBufCodecs.map(java.util.HashMap::new, net.minecraft.core.UUIDUtil.STREAM_CODEC, ByteBufCodecs.stringUtf8(128), 64),
                Status::stalled,
                Status::new);

        @Override
        public Type<Status> type() {
            return TYPE;
        }
    }

    /** Client → server: what would a request for {@code amount} of {@code target} take? */
    public record Ask(int containerId, ItemResource target, long amount, Optional<BlockPos> server) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(Jasm.id("craft_ask"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ask> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ask::containerId,
                ItemResource.STREAM_CODEC, Ask::target,
                ByteBufCodecs.VAR_LONG, Ask::amount,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), Ask::server,
                Ask::new);

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    /** Server → client: the answer. {@code problem} is a message key, empty when the request can start. */
    public record Answer(int containerId, ItemResource target, long amount, long made, String problem, List<DeckPayloads.Entry> taken,
            List<DeckPayloads.Entry> missing, List<DeckPayloads.Entry> crafts, List<ServerView> servers, int chosen) implements CustomPacketPayload {
        public static final Type<Answer> TYPE = new Type<>(Jasm.id("craft_answer"));
        private static final StreamCodec<RegistryFriendlyByteBuf, List<DeckPayloads.Entry>> ENTRIES =
                DeckPayloads.Entry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LIST));
        public static final StreamCodec<RegistryFriendlyByteBuf, Answer> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Answer decode(RegistryFriendlyByteBuf buf) {
                return new Answer(buf.readVarInt(), ItemResource.STREAM_CODEC.decode(buf), buf.readVarLong(), buf.readVarLong(), buf.readUtf(),
                        ENTRIES.decode(buf), ENTRIES.decode(buf), ENTRIES.decode(buf),
                        ServerView.STREAM_CODEC.apply(ByteBufCodecs.list(64)).decode(buf), buf.readVarInt());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, Answer answer) {
                buf.writeVarInt(answer.containerId);
                ItemResource.STREAM_CODEC.encode(buf, answer.target);
                buf.writeVarLong(answer.amount);
                buf.writeVarLong(answer.made);
                buf.writeUtf(answer.problem);
                ENTRIES.encode(buf, answer.taken);
                ENTRIES.encode(buf, answer.missing);
                ENTRIES.encode(buf, answer.crafts);
                ServerView.STREAM_CODEC.apply(ByteBufCodecs.list(64)).encode(buf, answer.servers);
                buf.writeVarInt(answer.chosen);
            }
        };

        @Override
        public Type<Answer> type() {
            return TYPE;
        }
    }

    /** Client → server: start the request (planned again on the server). */
    public record Start(int containerId, ItemResource target, long amount, Optional<BlockPos> server) implements CustomPacketPayload {
        public static final Type<Start> TYPE = new Type<>(Jasm.id("craft_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Start> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Start::containerId,
                ItemResource.STREAM_CODEC, Start::target,
                ByteBufCodecs.VAR_LONG, Start::amount,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), Start::server,
                Start::new);

        @Override
        public Type<Start> type() {
            return TYPE;
        }
    }

    /** Client → server: set rule {@code index} (a new one at the end if it is past the last), or delete it when empty. */
    public record SetRule(int containerId, int index, Optional<CraftRule> rule) implements CustomPacketPayload {
        public static final Type<SetRule> TYPE = new Type<>(Jasm.id("craft_set_rule"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetRule> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SetRule::containerId,
                ByteBufCodecs.VAR_INT, SetRule::index,
                ByteBufCodecs.optional(CraftRule.STREAM_CODEC), SetRule::rule,
                SetRule::new);

        @Override
        public Type<SetRule> type() {
            return TYPE;
        }
    }

    /** Client → server: fill the open terminal's processing grid and outputs from a recipe (JEI), with counts. */
    public record ProcessingGhost(int containerId, List<ItemStack> inputs, List<ItemStack> outputs) implements CustomPacketPayload {
        public static final Type<ProcessingGhost> TYPE = new Type<>(Jasm.id("terminal_processing_ghost"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ProcessingGhost> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ProcessingGhost::containerId,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(64)), ProcessingGhost::inputs,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(64)), ProcessingGhost::outputs,
                ProcessingGhost::new);

        @Override
        public Type<ProcessingGhost> type() {
            return TYPE;
        }
    }

    /** Server → client: the Access Ports the open terminal can write processing cards for. */
    public record TerminalMachines(int containerId, List<EncodingTerminalMenu.MachineView> machines) implements CustomPacketPayload {
        public static final Type<TerminalMachines> TYPE = new Type<>(Jasm.id("terminal_machines"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalMachines> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TerminalMachines::containerId,
                EncodingTerminalMenu.MachineView.STREAM_CODEC.apply(ByteBufCodecs.list(EncodingTerminalMenu.MAX_MACHINES)),
                TerminalMachines::machines,
                TerminalMachines::new);

        @Override
        public Type<TerminalMachines> type() {
            return TYPE;
        }
    }

    /**
     * Server → client: what is on the Deck the viewer of an Encoding Terminal carries (the one in hand, else the first
     * in the inventory), to pick examples from; {@code found} is false when they carry none.
     */
    public record TerminalDeck(int containerId, boolean found, List<DeckPayloads.Entry> items) implements CustomPacketPayload {
        public static final Type<TerminalDeck> TYPE = new Type<>(Jasm.id("terminal_deck"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalDeck> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TerminalDeck::containerId,
                ByteBufCodecs.BOOL, TerminalDeck::found,
                DeckPayloads.Entry.STREAM_CODEC.apply(ByteBufCodecs.list(EncodingTerminalMenu.MAX_DECK_ITEMS)), TerminalDeck::items,
                TerminalDeck::new);

        @Override
        public Type<TerminalDeck> type() {
            return TYPE;
        }
    }

    /** Client → server: set the open Encoding Terminal's ghost grid (JEI); slots 9-11 are the processing outputs. {@code slot} -1 sets all nine. */
    public record Ghost(int containerId, int slot, List<ItemStack> items) implements CustomPacketPayload {
        public static final Type<Ghost> TYPE = new Type<>(Jasm.id("terminal_ghost"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ghost> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ghost::containerId,
                ByteBufCodecs.VAR_INT, Ghost::slot,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(9)), Ghost::items,
                Ghost::new);

        @Override
        public Type<Ghost> type() {
            return TYPE;
        }
    }

    /** Server → client: the open terminal's trusted players, and whether the viewer owns it (only owners may change them). */
    public record TrustView(int containerId, boolean owner, TrustList trust) implements CustomPacketPayload {
        public static final Type<TrustView> TYPE = new Type<>(Jasm.id("terminal_trust_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TrustView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TrustView::containerId,
                ByteBufCodecs.BOOL, TrustView::owner,
                TrustList.STREAM_CODEC, TrustView::trust,
                TrustView::new);

        @Override
        public Type<TrustView> type() {
            return TYPE;
        }
    }

    /** Client → server: trust the player called {@code name} at the open terminal, or ({@code remove}) stop trusting {@code player}. */
    public record Trust(int containerId, String name, Optional<java.util.UUID> remove) implements CustomPacketPayload {
        public static final Type<Trust> TYPE = new Type<>(Jasm.id("terminal_trust"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Trust> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Trust::containerId,
                ByteBufCodecs.stringUtf8(16), Trust::name,
                ByteBufCodecs.optional(net.minecraft.core.UUIDUtil.STREAM_CODEC), Trust::remove,
                Trust::new);

        @Override
        public Type<Trust> type() {
            return TYPE;
        }
    }

    /** Server → client: machines touching the open Access Port. */
    public record PortMachines(int containerId, List<AccessPortMenu.MachineView> machines) implements CustomPacketPayload {
        public static final Type<PortMachines> TYPE = new Type<>(Jasm.id("port_machines"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortMachines> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PortMachines::containerId,
                AccessPortMenu.MachineView.STREAM_CODEC.apply(ByteBufCodecs.list(6)), PortMachines::machines,
                PortMachines::new);

        @Override public Type<PortMachines> type() { return TYPE; }
    }

    /** Client → server: rename the open Access Port; empty goes back to the machine's name. */
    public record PortName(int containerId, String name) implements CustomPacketPayload {
        public static final Type<PortName> TYPE = new Type<>(Jasm.id("port_name"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortName> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PortName::containerId,
                ByteBufCodecs.stringUtf8(AccessPortMenu.MAX_NAME), PortName::name,
                PortName::new);

        @Override
        public Type<PortName> type() {
            return TYPE;
        }
    }

    /** Client → server: open the screen of the server running one of this Deck's jobs. */
    public record OpenServer(int containerId, BlockPos server) implements CustomPacketPayload {
        public static final Type<OpenServer> TYPE = new Type<>(Jasm.id("craft_open_server"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenServer> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenServer::containerId,
                BlockPos.STREAM_CODEC, OpenServer::server,
                OpenServer::new);

        @Override
        public Type<OpenServer> type() {
            return TYPE;
        }
    }

    /** Client → server: stop this Deck's job on {@code server}. */
    public record Cancel(int containerId, BlockPos server) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = new Type<>(Jasm.id("craft_cancel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Cancel::containerId,
                BlockPos.STREAM_CODEC, Cancel::server,
                Cancel::new);

        @Override
        public Type<Cancel> type() {
            return TYPE;
        }
    }
}
