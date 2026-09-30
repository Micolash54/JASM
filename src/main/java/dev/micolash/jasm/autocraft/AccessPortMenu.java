package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The Access Port's menu: no slots, only its charge, whether a job is using it, and its name. */
public class AccessPortMenu extends AbstractContainerMenu {
    public static final int MAX_NAME = 32;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_RUNNING = 2;
    static final int DATA_LOCKED = 3;
    static final int DATA_COUNT = 4;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable AccessPortBlockEntity port;
    /** Client side: the name when the screen opened, and the machine's. */
    private final String label;
    private final Component machine;

    /** Server side. */
    public AccessPortMenu(int containerId, Inventory inventory, AccessPortBlockEntity port, ContainerLevelAccess access) {
        this(containerId, port.data(), access, port, port.label(), Component.empty());
    }

    /** Client side. */
    public static AccessPortMenu client(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        buf.readBlockPos();
        String label = buf.readUtf(MAX_NAME);
        Component machine = ComponentSerialization.STREAM_CODEC.decode(buf);
        return new AccessPortMenu(containerId, new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null, label, machine);
    }

    private AccessPortMenu(int containerId, ContainerData data, ContainerLevelAccess access, @Nullable AccessPortBlockEntity port,
            String label, Component machine) {
        super(JasmMenus.ACCESS_PORT.get(), containerId);
        this.data = data;
        this.access = access;
        this.port = port;
        this.label = label;
        this.machine = machine;
        addDataSlots(data);
    }

    public @Nullable AccessPortBlockEntity port() {
        return port;
    }

    public String label() {
        return label;
    }

    public Component machine() {
        return machine;
    }

    public int energy() {
        return (data.get(DATA_ENERGY_HIGH) & 0xFFFF) << 16 | (data.get(DATA_ENERGY_LOW) & 0xFFFF);
    }

    public int capacity() {
        return AccessPortBlockEntity.CAPACITY;
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    /** Whether a job has sets in the machine right now. */
    public boolean locked() {
        return data.get(DATA_LOCKED) != 0;
    }

    @Override
    public boolean stillValid(Player player) {
        return port == null || port.installed() && player.distanceToSqr(Vec3.atCenterOf(port.getBlockPos())) <= 64 && MachineAccess.canUse(port, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** Server side: whether the port this menu shows stands at {@code pos}. */
    boolean shows(BlockPos pos) {
        return port != null && port.getBlockPos().equals(pos);
    }
}
