package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.acceptor.AcceptorMode;
import dev.micolash.jasm.acceptor.PowerAcceptorBlockEntity;
import dev.micolash.jasm.acceptor.ThinPowerAcceptor;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.autocraft.AdvancedCraftingServerTopBlockEntity;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.battery.BatteryBlockEntity;
import dev.micolash.jasm.battery.BatteryGroup;
import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.brain.NetworkChamberBlockEntity;
import dev.micolash.jasm.chunkload.ChunkLoaders;
import dev.micolash.jasm.core.JasmServerData;
import dev.micolash.jasm.core.JasmServerData.NetworkAnswer;
import dev.micolash.jasm.deck.NetworkViewPayloads.Category;
import dev.micolash.jasm.dock.DockingStationBlockEntity;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.NetworkLimit;
import dev.micolash.jasm.network.NetworkNode;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.pool.StoragePortBlockEntity;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.relay.RelayChatterboxBlockEntity;
import dev.micolash.jasm.relay.RelayChatterboxTopBlockEntity;
import dev.micolash.jasm.relay.RelayEnd;
import dev.micolash.jasm.transfer.TransferPortBlockEntity;
import dev.micolash.jasm.workshop.ChipWorkshopBlockEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * The Deck's Network tab on the server: lists the linked network's machines, power sources and Batteries with the power
 * each adds or uses, and lights up the one the player clicks. Only the Deck's own network is shown, and only to players
 * its terminal lets in.
 *
 * <p>Power is what its sources give the network (an Out interface counts against it), every other group is what its
 * machines use, and the Batteries show their own In minus Out, as their screens do. Power a source hands straight to a
 * port's machine never passes through the network, so it is left out on both sides.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class NetworkViewService {
    /** Server ticks between two answers to the same player. */
    private static final int ASK_EVERY = 20;
    /** What the tab finds on a network once, kept as long as that network. */
    private static final Function<CableNetwork, Layout> LAYOUT = NetworkViewService::layout;

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
        Map<UUID, NetworkAnswer> asked = JasmServerData.of(player.level().getServer()).networkAsked;
        NetworkAnswer last = asked.get(player.getUUID());
        // The tick count starts again with each world, so an older time than now is from a world left earlier.
        if (menu == null || last != null && last.containerId() == payload.containerId() && now >= last.tick()
                && now - last.tick() < ASK_EVERY) {
            return false;
        }
        asked.put(player.getUUID(), new NetworkAnswer(now, payload.containerId()));
        NetworkViewPayloads.View view = build(player, menu.deck(), payload.containerId());
        if (player.connection.hasChannel(NetworkViewPayloads.View.TYPE)) {
            PacketDistributor.sendToPlayer(player, view);
        }
        return true;
    }

    /**
     * Lights up a block of the Deck's own network for its player: one of its blocks, a power source touching it, or a
     * Battery the tab lists. Anything else, or a player the network doesn't let in, gets nothing. Returns whether it
     * answered.
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
        if (network == null || !network.contains(payload.at()) && !network.derived(LAYOUT).sources.contains(payload.at())
                && !batteryAt(network, payload.at())) {
            return false;
        }
        if (player.connection.hasChannel(NetworkViewPayloads.Glow.TYPE)) {
            PacketDistributor.sendToPlayer(player, NetworkViewPayloads.Glow.of(payload.at().pos(), payload.at().dimension()));
        }
        return true;
    }

    private static boolean batteryAt(CableNetwork network, NetworkNode at) {
        for (BatteryBlockEntity battery : network.allBatteries()) {
            if (!battery.isRemoved() && battery.getLevel() instanceof ServerLevel level
                    && at.equals(NetworkNode.of(level, battery.group(level).holder().getBlockPos()))) {
                return true;
            }
        }
        return false;
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
        Layout layout = network.derived(LAYOUT);
        List<NetworkViewPayloads.Node> rows = new ArrayList<>();
        boolean partial = !network.complete() || layout.missing;
        for (BlockEntity entity : layout.machines) {
            if (entity.isRemoved() || !(entity.getLevel() instanceof ServerLevel at)) {
                partial = true;
                continue;
            }
            // The floors above a tower's lowest show what it shows, and pay nothing: the lowest stands for them.
            if (entity instanceof NetworkBrainBlockEntity brain && !brain.leadsItsTower()) continue;
            if (entity instanceof AccessPortBlockEntity port) {
                rows.add(portRow(port, new ItemStack(port.getBlockState().getBlock()), at));
            } else {
                rows.add(machineRow(entity, at));
            }
        }
        for (PortOnCable port : layout.ports) {
            if (port.cable().isRemoved() || !(port.cable().getLevel() instanceof ServerLevel at)) {
                partial = true;
                continue;
            }
            rows.add(portRow(port.port(), port.cable().portItem(port.port()), at));
        }
        for (Object source : layout.sourceBlocks) {
            NetworkViewPayloads.Node row = sourceRow(source);
            if (row == null) {
                partial = true;
            } else {
                rows.add(row);
            }
        }
        long[] power = new long[Category.values().length];
        int[] counts = new int[power.length];
        for (NetworkViewPayloads.Node row : rows) {
            power[row.category().ordinal()] += row.power();
            counts[row.category().ordinal()]++;
        }
        long stored = 0;
        long capacity = 0;
        long in = 0;
        long out = 0;
        for (BatteryGroup group : batteries(network)) {
            BatteryBlockEntity holder = group.holder();
            ServerLevel at = (ServerLevel) holder.getLevel();
            long held = group.stored();
            long room = group.energy().getCapacityAsLong();
            stored += held;
            capacity += room;
            in += group.flowIn();
            out += group.flowOut();
            counts[Category.BATTERIES.ordinal()]++;
            rows.add(new NetworkViewPayloads.Node(Category.BATTERIES, new ItemStack(holder.getBlockState().getBlock()),
                    holder.getDisplayName(), Optional.empty(), holder.getBlockPos(), at.dimension(), NetworkViewPayloads.Node.WORKING, 0, 0,
                    new NetworkViewPayloads.Charge(held, room, group.flowIn(), group.flowOut()), Optional.empty()));
        }
        // The Batteries' own figures: a source or a use the tab doesn't see still shows in them.
        power[Category.BATTERIES.ordinal()] = in - out;
        List<NetworkViewPayloads.Group> groups = new ArrayList<>();
        for (int i = 0; i < power.length; i++) {
            groups.add(new NetworkViewPayloads.Group(counts[i], power[i]));
        }
        if (rows.size() > NetworkViewPayloads.MAX_NODES) {
            partial = true;
            rows = rows.subList(0, NetworkViewPayloads.MAX_NODES);
        }
        NetworkLimit.State limit = network.limitState();
        NetworkViewPayloads.Header header = new NetworkViewPayloads.Header(0, limit.count(), limit.limit(), partial, groups, stored, capacity,
                in, out);
        return new NetworkViewPayloads.View(containerId, header, rows);
    }

    private static NetworkViewPayloads.View empty(int containerId, int state) {
        return new NetworkViewPayloads.View(containerId, NetworkViewPayloads.Header.of(state), List.of());
    }

    /** Each Battery on the network once, in every dimension it stands in. */
    private static Set<BatteryGroup> batteries(CableNetwork network) {
        Set<BatteryGroup> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BatteryBlockEntity battery : network.allBatteries()) {
            if (!battery.isRemoved() && battery.getLevel() instanceof ServerLevel level) {
                found.add(battery.group(level));
            }
        }
        return found;
    }

    // --- rows ---

    private static NetworkViewPayloads.Node machineRow(BlockEntity entity, ServerLevel level) {
        int chunk = entity instanceof MachineBlockEntity machine ? ChunkLoaders.drawOf(machine) : 0;
        int use = power(entity) + chunk;
        return new NetworkViewPayloads.Node(category(entity), new ItemStack(entity.getBlockState().getBlock()), name(entity),
                Optional.empty(), entity.getBlockPos(), level.dimension(), status(entity), -use, chunk, NetworkViewPayloads.Charge.NONE,
                note(entity));
    }

    /**
     * A port. One serving a machine shows that machine, and an Input, Output, Two-Way or Storage Port the block it works
     * with, with the port's own name kept for the hover; one with nothing there shows itself. Its use is the power its
     * upgrade passes on from the cables; what a generator touching it hands straight on isn't the network's.
     */
    private static NetworkViewPayloads.Node portRow(AccessPortBlockEntity port, ItemStack portItem, ServerLevel level) {
        Category category = port instanceof StoragePortBlockEntity ? Category.STORAGE : Category.PORTS;
        ItemStack icon = portItem;
        Component name = port.label().isEmpty() ? port.getDisplayName() : Component.literal(port.label());
        Optional<NetworkViewPayloads.Serving> serving = Optional.empty();
        Component served = null;
        ItemStack servedIcon = ItemStack.EMPTY;
        if (port instanceof TransferPortBlockEntity transfer) {
            Block block = transfer.servedBlock();
            servedIcon = block == null ? ItemStack.EMPTY : new ItemStack(block);
            if (!servedIcon.isEmpty()) served = block.getName();
        } else if (!port.machineSides().isEmpty()) {
            Block block = port.servedType();
            servedIcon = block == null ? ItemStack.EMPTY : new ItemStack(block);
            served = port.machineNames();
        }
        if (served != null) {
            if (!servedIcon.isEmpty()) icon = servedIcon;
            if (port.label().isEmpty()) name = served;
            serving = Optional.of(new NetworkViewPayloads.Serving(
                    Component.translatable("screen.jasm.network.serving", portItem.getHoverName(), served), badge(port)));
        }
        int chunk = ChunkLoaders.drawOf(port);
        int use = port.networkPowerFlow() + chunk;
        // a port whose machine another port keeps serves nothing there: idle, with the reason
        boolean taken = port.anyTaken();
        int status = !port.running() ? NetworkViewPayloads.Node.STOPPED
                : taken && serving.isEmpty() ? NetworkViewPayloads.Node.IDLE : NetworkViewPayloads.Node.WORKING;
        return new NetworkViewPayloads.Node(category, icon, name, serving, port.getBlockPos(), level.dimension(),
                status, -use, chunk, NetworkViewPayloads.Charge.NONE,
                taken ? Optional.of(Component.translatable("screen.jasm.port.machine_taken")) : Optional.empty());
    }

    /** Which badge a port's row wears: its own kind. */
    private static NetworkViewPayloads.Badge badge(AccessPortBlockEntity port) {
        if (port instanceof StoragePortBlockEntity) return NetworkViewPayloads.Badge.STORAGE_PORT;
        if (!(port instanceof TransferPortBlockEntity transfer)) return NetworkViewPayloads.Badge.ACCESS_PORT;
        return switch (transfer.kind()) {
            case INPUT -> NetworkViewPayloads.Badge.INPUT_PORT;
            case OUTPUT -> NetworkViewPayloads.Badge.OUTPUT_PORT;
            case INPUT_OUTPUT -> NetworkViewPayloads.Badge.TWO_WAY_PORT;
            case STORAGE -> NetworkViewPayloads.Badge.STORAGE_PORT;
        };
    }

    /** One more hover line for the row: a Relay Chatterbox's frequency, as its screen shows it. */
    private static Optional<Component> note(BlockEntity entity) {
        if (!(entity instanceof RelayChatterboxBlockEntity relay)) return Optional.empty();
        int frequency = relay.frequency();
        return Optional.of(frequency == RelayEnd.NO_FREQUENCY ? Component.translatable("screen.jasm.network.no_frequency")
                : Component.translatable("tooltip.jasm.relay_chatterbox.frequency", String.format(Locale.ROOT, "%03d", frequency)));
    }

    /** A generator, Creative Battery or Power Interface touching the network; null once it is gone. */
    private static NetworkViewPayloads.@Nullable Node sourceRow(Object source) {
        if (source instanceof ThinOnCable thin) {
            if (thin.cable().isRemoved() || thin.cable().acceptor(thin.acceptor().side()) != thin.acceptor()
                    || !(thin.cable().getLevel() instanceof ServerLevel level)) {
                return null;
            }
            int flow = thin.acceptor().averageFlow();
            int power = thin.acceptor().mode() == AcceptorMode.INPUT ? flow : -flow;
            return interfaceRow(new ItemStack(JasmItems.THIN_POWER_ACCEPTOR.get()), thin.acceptor().getDisplayName(),
                    thin.acceptor().served(), thin.cable().getBlockPos(), level, flow, power);
        }
        if (!(source instanceof BlockEntity entity) || entity.isRemoved() || !(entity.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        if (entity instanceof PowerAcceptorBlockEntity acceptor) {
            int flow = acceptor.averageFlow();
            return interfaceRow(new ItemStack(acceptor.getBlockState().getBlock()), acceptor.getDisplayName(), acceptor.served(),
                    acceptor.getBlockPos(), level, flow, acceptor.mode() == AcceptorMode.INPUT ? flow : -flow);
        }
        int power;
        boolean working;
        if (entity instanceof CombustionGeneratorBlockEntity generator) {
            power = generator.networkFlow();
            working = power > 0;
        } else if (entity instanceof CreativeBatteryBlockEntity battery) {
            power = battery.networkFlow();
            working = true;
        } else {
            return null;
        }
        return new NetworkViewPayloads.Node(Category.POWER, new ItemStack(entity.getBlockState().getBlock()), name(entity),
                Optional.empty(), entity.getBlockPos(), level.dimension(),
                working ? NetworkViewPayloads.Node.WORKING : NetworkViewPayloads.Node.STOPPED, power, 0, NetworkViewPayloads.Charge.NONE,
                Optional.empty());
    }

    /**
     * A Power Interface. One trading with another block shows that block, like a port shows its machine, with its own name
     * kept for the hover; one with nothing beside it shows itself.
     */
    private static NetworkViewPayloads.Node interfaceRow(ItemStack own, Component ownName, @Nullable Block served, BlockPos pos,
            ServerLevel level, int flow, int power) {
        ItemStack icon = own;
        Component name = ownName;
        Optional<NetworkViewPayloads.Serving> serving = Optional.empty();
        ItemStack servedIcon = served == null ? ItemStack.EMPTY : new ItemStack(served);
        if (!servedIcon.isEmpty()) {
            icon = servedIcon;
            name = served.getName();
            serving = Optional.of(new NetworkViewPayloads.Serving(Component.translatable("screen.jasm.network.serving", ownName, name),
                    NetworkViewPayloads.Badge.POWER_INTERFACE));
        }
        return new NetworkViewPayloads.Node(Category.POWER, icon, name, serving, pos, level.dimension(),
                flow > 0 ? NetworkViewPayloads.Node.WORKING : NetworkViewPayloads.Node.STOPPED, power, 0, NetworkViewPayloads.Charge.NONE,
                Optional.empty());
    }

    private static Category category(BlockEntity entity) {
        if (entity instanceof NetworkBrainBlockEntity || entity instanceof EncodingTerminalBlockEntity
                || entity instanceof RelayChatterboxBlockEntity || entity instanceof DockingStationBlockEntity) {
            return Category.NETWORK;
        }
        return entity instanceof ArchiveBlockEntity ? Category.STORAGE : Category.CRAFTING;
    }

    /** FE a tick the block takes from the network right now, its critter's or Deck's charging included; 0 for anything that never pays. */
    private static int power(BlockEntity entity) {
        if (entity instanceof MachineBlockEntity machine) {
            return machine.drawingNow();
        }
        return entity instanceof ArchiveBlockEntity archive ? archive.usingNow() : 0;
    }

    /**
     * Working, stopped or out of power, or idle. A Chip Workshop with nothing to make and its critter charged is idle;
     * every other block is working or stopped.
     */
    private static int status(BlockEntity entity) {
        if (entity instanceof ChipWorkshopBlockEntity workshop) {
            if (workshop.halted()) return NetworkViewPayloads.Node.STOPPED;
            return workshop.working() || workshop.drawingNow() > 0 ? NetworkViewPayloads.Node.WORKING : NetworkViewPayloads.Node.IDLE;
        }
        return running(entity) ? NetworkViewPayloads.Node.WORKING : NetworkViewPayloads.Node.STOPPED;
    }

    private static boolean running(BlockEntity entity) {
        if (entity instanceof NetworkBrainBlockEntity brain) {
            return brain.working();
        }
        // A Chatterbox costs nothing itself: it works while its Link Byteling pays for the link.
        if (entity instanceof RelayChatterboxBlockEntity relay) {
            return relay.powerUse() > 0;
        }
        if (entity instanceof MachineBlockEntity machine) {
            return machine.running() || machine.drawingNow() > 0;
        }
        return entity instanceof ArchiveBlockEntity archive && archive.energy().getAmountAsInt() > 0 && !archive.networkStopped();
    }

    private static Component name(BlockEntity entity) {
        if (entity instanceof MenuProvider provider) {
            return provider.getDisplayName();
        }
        return entity.getBlockState().getBlock().getName();
    }

    // --- what is found once per network ---

    /** A port mounted on a cable. */
    private record PortOnCable(DataCableBlockEntity cable, AccessPortBlockEntity port) {}

    /** A Power Interface mounted on a cable. */
    private record ThinOnCable(DataCableBlockEntity cable, ThinPowerAcceptor acceptor) {}

    /**
     * The network's machines and Archives (without the parts that only belong to another block), the ports on its cables,
     * and the power sources touching any of its blocks: generators, Creative Batteries and Power Interfaces. {@code
     * missing} says a block of the network wasn't loaded when this was worked out. A power source placed or broken beside
     * the network makes a new network, so this never goes stale.
     */
    private record Layout(List<BlockEntity> machines, List<PortOnCable> ports, List<Object> sourceBlocks, Set<NetworkNode> sources,
            boolean missing) {}

    private static Layout layout(CableNetwork network) {
        List<BlockEntity> machines = new ArrayList<>();
        List<PortOnCable> ports = new ArrayList<>();
        Set<Object> sources = new LinkedHashSet<>();
        Set<NetworkNode> places = new LinkedHashSet<>();
        boolean missing = false;
        for (CableNetwork.Part part : network.parts()) {
            ServerLevel level = part.level();
            for (BlockPos pos : part.machines()) {
                if (!level.isLoaded(pos)) {
                    missing = true;
                    continue;
                }
                BlockEntity entity = level.getBlockEntity(pos);
                if (entity == null || entity instanceof NetworkChamberBlockEntity || entity instanceof AdvancedCraftingServerTopBlockEntity
                        || entity instanceof RelayChatterboxTopBlockEntity) {
                    continue;
                }
                machines.add(entity);
                findSources(level, pos, sources, places);
            }
            for (BlockPos pos : part.cables()) {
                if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof DataCableBlockEntity cable)) {
                    missing |= !level.isLoaded(pos);
                    continue;
                }
                for (AccessPortBlockEntity port : cable.ports()) ports.add(new PortOnCable(cable, port));
                for (Direction side : Direction.values()) {
                    ThinPowerAcceptor acceptor = cable.acceptor(side);
                    if (acceptor != null) {
                        sources.add(new ThinOnCable(cable, acceptor));
                        places.add(NetworkNode.of(level, pos));
                    }
                }
                findSources(level, pos, sources, places);
            }
        }
        return new Layout(List.copyOf(machines), List.copyOf(ports), List.copyOf(sources), Set.copyOf(places), missing);
    }

    private static void findSources(ServerLevel level, BlockPos pos, Set<Object> sources, Set<NetworkNode> places) {
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (!level.isLoaded(next)) continue;
            BlockEntity entity = level.getBlockEntity(next);
            if (entity instanceof CombustionGeneratorBlockEntity || entity instanceof CreativeBatteryBlockEntity
                    || entity instanceof PowerAcceptorBlockEntity) {
                if (sources.add(entity)) places.add(NetworkNode.of(level, next));
            }
        }
    }
}
