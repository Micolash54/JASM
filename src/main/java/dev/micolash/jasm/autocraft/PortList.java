package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Notices;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.NetworkNode;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.transfer.TransferPortBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The list of Access Ports a machine's screen shows beside it (the Advanced Encoder's, the Rack Controller's): every
 * port on the machine's network the player may use. A port opened from the list goes back to the machine when shut.
 * One per open screen; on the server it holds what was last sent, on the client what arrived.
 */
public final class PortList {
    /** Most ports the list shows. */
    public static final int MAX = 256;
    /** Ticks between looks at the network's ports. */
    private static final int EVERY = 20;

    /** A menu whose screen shows the port list. */
    public interface Menu {
        PortList portList();

        /** Whether the machine has power; without it the list is empty. */
        boolean running();

        /** Server side: opens the port's own screen from the list; shutting it comes back here. */
        boolean openPort(ServerPlayer player, NetworkNode at, int face);

        /** The ports the list shows; on the server, what was last sent. */
        default List<Row> ports() {
            return portList().rows();
        }

        /** Client side: what the server sent. */
        default void setPorts(List<Row> rows) {
            portList().set(rows);
        }

        /** Server side: how many times the port list changed and went to the player. */
        default int portSends() {
            return portList().sends();
        }
    }

    /**
     * One Access Port as the list shows it: where it stands, its mounting face for a thin port (-1 for a block), its
     * name (the port's own name when unnamed), the machine it serves as an icon (the port itself while it has none),
     * whether it stands in the viewer's dimension, and whether another port keeps its machine.
     */
    public record Row(NetworkNode at, int face, Component name, ItemStack icon, boolean here, boolean taken) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
                NetworkNode.STREAM_CODEC, Row::at,
                ByteBufCodecs.VAR_INT, Row::face,
                ComponentSerialization.STREAM_CODEC, Row::name,
                ItemStack.OPTIONAL_STREAM_CODEC, Row::icon,
                ByteBufCodecs.BOOL, Row::here,
                ByteBufCodecs.BOOL, Row::taken,
                Row::new);

        /** Ports in the viewer's dimension first, then by dimension and position: the server's order, which names don't change. */
        static final Comparator<Row> PLACE = Comparator
                .comparing((Row row) -> !row.here())
                .thenComparing(row -> row.at().dimension().identifier().toString())
                .thenComparing(row -> row.at().pos())
                .thenComparingInt(Row::face);

        /** As the list shows them: ports in the viewer's dimension first, then by name. Names only read right on the client. */
        static final Comparator<Row> SHOWN = Comparator
                .comparing((Row row) -> !row.here())
                .thenComparing(row -> row.name().getString().toLowerCase(Locale.ROOT))
                .thenComparing(PLACE);

        static Row of(AccessPortBlockEntity port, Player viewer) {
            Block type = port.machineType();
            ItemStack icon = type == null ? ItemStack.EMPTY : new ItemStack(type);
            Direction face = port.face();
            if (icon.isEmpty()) {
                icon = face == null ? new ItemStack(port.getBlockState().getBlock()) : new ItemStack(JasmItems.THIN_ACCESS_PORT.get());
            }
            return new Row(NetworkNode.of(port.getLevel(), port.getBlockPos()), face == null ? -1 : face.get3DDataValue(),
                    port.getDisplayName(), icon, port.getLevel().dimension().equals(viewer.level().dimension()), port.anyTaken());
        }

        /** Whether the port at {@code at} with {@code face} is this row's. */
        public boolean is(NetworkNode at, int face) {
            return this.at.equals(at) && this.face == face;
        }

        boolean same(Row other) {
            return is(other.at, other.face) && name.equals(other.name) && ItemStack.matches(icon, other.icon) && here == other.here
                    && taken == other.taken;
        }
    }

    private List<Row> rows = List.of();
    private int version;
    /** Server side: whether the list went out once yet, and how many times it did. */
    private boolean synced;
    private int sends;

    /**
     * The loaded Access Ports on {@code machine}'s network that the player may use, in every dimension it stands in;
     * none while the machine has no power. Ports in unloaded chunks are never loaded to be shown.
     */
    public static List<AccessPortBlockEntity> usable(@Nullable MachineBlockEntity machine, Player player) {
        if (machine == null || !machine.running() || !(machine.getLevel() instanceof ServerLevel level)) {
            return List.of();
        }
        CableNetwork network = Networks.at(level, machine.getBlockPos());
        if (network == null) {
            return List.of();
        }
        List<AccessPortBlockEntity> found = new ArrayList<>();
        for (AccessPortBlockEntity port : network.machines(AccessPortBlockEntity.class)) {
            if (!(port instanceof TransferPortBlockEntity) && port.installed() && MachineAccess.canUse(port, player)) {
                found.add(port);
            }
        }
        return found;
    }

    /** Server side: whether it is time for another look, once a second, spread out over open screens. */
    public boolean due(ServerPlayer player, int containerId) {
        return !synced || (player.level().getGameTime() + containerId) % EVERY == 0;
    }

    /** Server side: looks at the ports and sends the list when it changed since last time. */
    public void look(ServerPlayer player, int containerId, List<AccessPortBlockEntity> usable) {
        List<Row> now = new ArrayList<>();
        for (AccessPortBlockEntity port : usable) {
            now.add(Row.of(port, player));
        }
        now.sort(Row.PLACE);
        if (now.size() > MAX) {
            now = new ArrayList<>(now.subList(0, MAX));
        }
        boolean same = synced && now.size() == rows.size();
        for (int i = 0; same && i < now.size(); i++) {
            same = now.get(i).same(rows.get(i));
        }
        synced = true;
        if (same) {
            return;
        }
        rows = List.copyOf(now);
        sends++;
        if (player.connection.hasChannel(CraftPayloads.PortRows.TYPE)) {
            PacketDistributor.sendToPlayer(player, new CraftPayloads.PortRows(containerId, rows));
        }
    }

    /**
     * Server side: the port at {@code at} with {@code face} among {@code usable}, or null with a line on the screen
     * saying why not: it is gone, or it stands in another dimension than the player.
     */
    public static @Nullable AccessPortBlockEntity find(ServerPlayer player, List<AccessPortBlockEntity> usable, NetworkNode at, int face) {
        for (AccessPortBlockEntity port : usable) {
            Direction side = port.face();
            if (port.getBlockPos().equals(at.pos()) && port.getLevel().dimension().equals(at.dimension())
                    && (side == null ? -1 : side.get3DDataValue()) == face) {
                if (!at.dimension().equals(player.level().dimension())) {
                    Notices.bad(player, Component.translatable("message.jasm.terminal.port_elsewhere"));
                    return null;
                }
                return port;
            }
        }
        Notices.bad(player, Component.translatable("message.jasm.terminal.port_gone"));
        return null;
    }

    /** Server side: opens the port's own screen, which goes back to {@code from} when shut. */
    public static void open(ServerPlayer player, AccessPortBlockEntity port, MachineBlockEntity from) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> AccessPortMenu.remote(id, inventory, port, from),
                port.getDisplayName()), port::writeRemoteOpening);
    }

    /** The ports the list shows; on the server, what was last sent. */
    public List<Row> rows() {
        return rows;
    }

    /** Client side: what the server sent, sorted by name. */
    public void set(List<Row> sent) {
        List<Row> sorted = new ArrayList<>(sent);
        sorted.sort(Row.SHOWN);
        rows = List.copyOf(sorted);
        version++;
    }

    /** Goes up with every new list. */
    public int version() {
        return version;
    }

    /** Server side: how many times the list changed and went to the player. */
    public int sends() {
        return sends;
    }
}
