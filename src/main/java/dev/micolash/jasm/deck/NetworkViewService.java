package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.brain.NetworkChamberBlockEntity;
import dev.micolash.jasm.core.NetworkTree;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.NetworkLimit;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.transfer.TransferPortBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * The Deck's Network tab on the server: works out the linked network as a tree of its machines, and lights up the one
 * the player clicks. Only the Deck's own network is shown, and only to players its terminal lets in.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class NetworkViewService {
    /** Server ticks between two answers to the same player: the walk over a big network isn't free. */
    private static final int ASK_EVERY = 20;
    /** When each player was last answered, in server ticks, and for which screen. */
    private static final Map<UUID, Answered> LAST_ASK = new HashMap<>();

    private record Answered(long tick, int containerId) {}

    private NetworkViewService() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(NetworkViewPayloads.Ask.TYPE, NetworkViewPayloads.Ask.STREAM_CODEC,
                        (payload, context) -> ask((ServerPlayer) context.player(), payload))
                .playToServer(NetworkViewPayloads.Locate.TYPE, NetworkViewPayloads.Locate.STREAM_CODEC,
                        (payload, context) -> locate((ServerPlayer) context.player(), payload))
                // Their handlers live on the client side only.
                .playToClient(NetworkViewPayloads.View.TYPE, NetworkViewPayloads.View.STREAM_CODEC)
                .playToClient(NetworkViewPayloads.Glow.TYPE, NetworkViewPayloads.Glow.STREAM_CODEC);
    }

    // --- server ---

    /**
     * Answers the open Deck's screen. A screen just opened is answered at once; after that, at most once a second per
     * player. Returns whether it answered.
     */
    public static boolean ask(ServerPlayer player, NetworkViewPayloads.Ask payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        long now = player.level().getServer().getTickCount();
        Answered last = LAST_ASK.get(player.getUUID());
        // The tick count starts again with each world, so an older time than now is from a world left earlier.
        if (menu == null || last != null && last.containerId() == payload.containerId() && now >= last.tick()
                && now - last.tick() < ASK_EVERY) {
            return false;
        }
        LAST_ASK.put(player.getUUID(), new Answered(now, payload.containerId()));
        NetworkViewPayloads.View view = build(player, menu.deck(), payload.containerId());
        if (player.connection.hasChannel(NetworkViewPayloads.View.TYPE)) {
            PacketDistributor.sendToPlayer(player, view);
        }
        return true;
    }

    /**
     * Lights up a block of the Deck's own network for its player. Anything else, or a player the network doesn't let
     * in, gets nothing. Returns whether it answered.
     */
    public static boolean locate(ServerPlayer player, NetworkViewPayloads.Locate payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null) {
            return false;
        }
        EncodingTerminalBlockEntity terminal = Jobs.terminalOf(player.level().getServer(), menu.deck());
        if (terminal == null || !(terminal.getLevel() instanceof ServerLevel level) || !MachineAccess.canUse(terminal, player)) {
            return false;
        }
        CableNetwork network = Networks.at(level, terminal.getBlockPos());
        if (network == null || !network.contains(payload.pos())) {
            return false;
        }
        if (player.connection.hasChannel(NetworkViewPayloads.Glow.TYPE)) {
            PacketDistributor.sendToPlayer(player, new NetworkViewPayloads.Glow(payload.pos().immutable(), level.dimension()));
        }
        return true;
    }

    private static @Nullable DeckMenu openMenu(ServerPlayer player, int containerId) {
        if (player.containerMenu instanceof DeckMenu menu && menu.containerId == containerId && menu.stillValid(player)
                && menu.dimensionAllowed()) {
            return menu;
        }
        return null;
    }

    /** The view of the network {@code deck} is linked to, as {@code player} may see it. */
    public static NetworkViewPayloads.View build(ServerPlayer player, ItemStack deck, int containerId) {
        if (!deck.has(JasmComponents.DECK_NETWORK.get())) {
            return empty(containerId, 1);
        }
        EncodingTerminalBlockEntity terminal = Jobs.terminalOf(player.level().getServer(), deck);
        if (terminal == null || !(terminal.getLevel() instanceof ServerLevel level) || !level.isLoaded(terminal.getBlockPos())) {
            return empty(containerId, 2);
        }
        if (!MachineAccess.canUse(terminal, player)) {
            return empty(containerId, 3);
        }
        CableNetwork network = Networks.at(level, terminal.getBlockPos());
        if (network == null) {
            return empty(containerId, 2);
        }
        Cells cells = new Cells(level, network);
        NetworkBrainBlockEntity leader = network.leader();
        Integer root = leader == null ? null : cells.index.get(leader.getBlockPos());
        if (root == null) root = cells.index.get(terminal.getBlockPos());
        if (root == null) root = cells.firstMachine();
        List<NetworkViewPayloads.Node> nodes = new ArrayList<>();
        boolean partial = !network.complete();
        if (root != null) {
            cells.root = root;
            List<NetworkTree.Row> rows = NetworkTree.build(root, cells);
            partial |= rows.size() > NetworkViewPayloads.MAX_NODES;
            // Rows come parents first, so cutting the list short never leaves a row without its parent.
            for (NetworkTree.Row row : rows.subList(0, Math.min(rows.size(), NetworkViewPayloads.MAX_NODES))) {
                nodes.add(cells.node(row));
            }
        }
        NetworkLimit.State limit = network.limitState();
        List<NetworkBrainBlockEntity> brains = network.machines(NetworkBrainBlockEntity.class);
        boolean brainUnpowered = !brains.isEmpty() && brains.stream().noneMatch(NetworkBrainBlockEntity::working);
        NetworkViewPayloads.Header header = new NetworkViewPayloads.Header(0, leader == null ? -1 : leader.shownLevel(),
                limit.count(), limit.limit(), limit.stopped(), brainUnpowered, partial);
        return new NetworkViewPayloads.View(containerId, header, nodes);
    }

    private static NetworkViewPayloads.View empty(int containerId, int state) {
        return new NetworkViewPayloads.View(containerId, NetworkViewPayloads.Header.of(state), List.of());
    }

    /** A port mounted on a cable: a cell of its own, joined only to its cable's cell. */
    private record PortCell(int cable, DataCableBlockEntity holder, AccessPortBlockEntity port) {}

    /**
     * The network's blocks numbered for the tree: every cable and machine by position, so the numbers stay the same
     * from one view to the next, then the ports on each cable.
     */
    private static final class Cells implements NetworkTree.Graph {
        private final ServerLevel level;
        private final List<BlockPos> positions;
        private final Map<BlockPos, Integer> index = new HashMap<>();
        private final List<PortCell> ports = new ArrayList<>();
        private final List<List<Integer>> neighbours = new ArrayList<>();
        private int root = -1;

        Cells(ServerLevel level, CableNetwork network) {
            this.level = level;
            List<BlockPos> all = new ArrayList<>(network.cables());
            all.addAll(network.machines());
            all.sort(Comparator.comparingLong(BlockPos::asLong));
            this.positions = all;
            for (int i = 0; i < positions.size(); i++) {
                index.put(positions.get(i), i);
            }
            List<List<Integer>> portsOn = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                List<Integer> on = new ArrayList<>();
                if (level.isLoaded(positions.get(i)) && level.getBlockEntity(positions.get(i)) instanceof DataCableBlockEntity cable) {
                    // ports() keeps the direction order.
                    for (AccessPortBlockEntity port : cable.ports()) {
                        on.add(positions.size() + ports.size());
                        ports.add(new PortCell(i, cable, port));
                    }
                }
                portsOn.add(on);
            }
            for (int i = 0; i < positions.size(); i++) {
                BlockPos pos = positions.get(i);
                List<Integer> next = new ArrayList<>();
                for (Direction side : Direction.values()) {
                    Integer other = index.get(pos.relative(side));
                    if (other != null && Networks.canConnect(level, pos, positions.get(other))) {
                        next.add(other);
                    }
                }
                next.addAll(portsOn.get(i));
                neighbours.add(next);
            }
            for (PortCell port : ports) {
                neighbours.add(List.of(port.cable()));
            }
        }

        @Override
        public List<Integer> neighbours(int cell) {
            return neighbours.get(cell);
        }

        /** Cables, chambers and brains are the way between machines; the brain that leads is the root, so it shows. */
        @Override
        public boolean machine(int cell) {
            if (cell >= positions.size()) {
                return true;
            }
            BlockPos pos = positions.get(cell);
            if (!level.isLoaded(pos) || level.getBlockState(pos).getBlock() instanceof DataCableBlock) {
                return false;
            }
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof NetworkChamberBlockEntity) {
                return false;
            }
            return !(entity instanceof NetworkBrainBlockEntity) || cell == root;
        }

        @Nullable Integer firstMachine() {
            for (int cell = 0; cell < positions.size(); cell++) {
                if (machine(cell)) return cell;
            }
            return null;
        }

        NetworkViewPayloads.Node node(NetworkTree.Row row) {
            int cell = row.cell();
            if (cell >= positions.size()) {
                PortCell port = ports.get(cell - positions.size());
                BlockPos pos = positions.get(port.cable());
                int status = !level.isLoaded(pos) ? 2 : port.port().running() ? 0 : 1;
                return new NetworkViewPayloads.Node(row.parent(), row.depth(), row.junction(), port.holder().portItem(port.port()),
                        portName(port.port()), pos, level.dimension(), status);
            }
            BlockPos pos = positions.get(cell);
            if (!level.isLoaded(pos)) {
                // Never read a block that isn't loaded: that would load its chunk.
                return new NetworkViewPayloads.Node(row.parent(), row.depth(), row.junction(), ItemStack.EMPTY,
                        Component.translatable("screen.jasm.network.status.unloaded"), pos, level.dimension(), 2);
            }
            BlockState state = level.getBlockState(pos);
            BlockEntity entity = level.getBlockEntity(pos);
            Component name = row.junction() ? Component.translatable("screen.jasm.network.junction") : name(entity, state);
            int status = row.junction() || running(entity) ? 0 : 1;
            return new NetworkViewPayloads.Node(row.parent(), row.depth(), row.junction(), new ItemStack(state.getBlock()), name, pos,
                    level.dimension(), status);
        }
    }

    private static boolean running(@Nullable BlockEntity entity) {
        if (entity instanceof NetworkBrainBlockEntity brain) {
            return brain.working();
        }
        if (entity instanceof MachineBlockEntity machine) {
            return machine.running();
        }
        return entity instanceof ArchiveBlockEntity archive && archive.energy().getAmountAsInt() > 0 && !archive.networkStopped();
    }

    private static Component name(@Nullable BlockEntity entity, BlockState state) {
        if (entity instanceof AccessPortBlockEntity port) {
            return portName(port);
        }
        if (entity instanceof MenuProvider provider) {
            return provider.getDisplayName();
        }
        return state.getBlock().getName();
    }

    /** A port's own name if it has one; an Access Port without one says which machines it serves. */
    private static Component portName(AccessPortBlockEntity port) {
        if (port instanceof TransferPortBlockEntity) {
            return port.getDisplayName();
        }
        if (!port.label().isEmpty()) {
            return Component.literal(port.label());
        }
        if (!port.machineSides().isEmpty()) {
            return Component.translatable("screen.jasm.network.port_machine", port.machineNames());
        }
        return port.getDisplayName();
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ASK.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        LAST_ASK.clear();
    }
}
