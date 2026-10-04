package dev.micolash.jasm.pool;

import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import dev.micolash.jasm.transfer.TransferPortMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class StoragePortMenu extends AbstractContainerMenu implements MachineView {
    public static final int WIDTH = PortUpgradeLayout.MAIN_WIDTH;
    public static final int FILTER_TOP = TransferPortMenu.FILTER_TOP;
    /** Below the filter list: the access button and the priority field. */
    public static final int CONTROLS_TOP = FILTER_TOP + TransferPortMenu.filterHeight(2) + 4;
    public static final int INVENTORY_Y = CONTROLS_TOP + 30;
    private final @Nullable StoragePortBlockEntity port;
    private StorageSettings settings;

    public StoragePortMenu(int id, Inventory inventory, StoragePortBlockEntity port) {
        this(id, inventory, port, port.settings());
    }
    public static StoragePortMenu client(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        return new StoragePortMenu(id, inventory, null, StorageSettings.STREAM_CODEC.decode(buf));
    }
    private StoragePortMenu(int id, Inventory inventory, @Nullable StoragePortBlockEntity port, StorageSettings settings) {
        super(JasmMenus.STORAGE_PORT.get(), id);
        this.port = port;
        this.settings = settings;
        int ix = (WIDTH - 162) / 2;
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, 9 + row * 9 + col, ix + col * 18, INVENTORY_Y + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, ix + col * 18, INVENTORY_Y + 58));
    }
    public StorageSettings settings() { return port == null ? settings : port.settings(); }
    public int height() { return INVENTORY_Y + 84; }
    public boolean configure(StorageSettings wanted) {
        WaferSettings previous = settings().filter();
        if (wanted.filter().rules().stream().anyMatch(rule -> !rule.valid()
                && previous.rules().stream().noneMatch(old -> old.mode() == rule.mode() && old.value().equals(rule.value()))))
            return false;
        settings = wanted;
        if (port != null) port.setSettings(wanted);
        return true;
    }
    @Override
    public boolean stillValid(Player player) {
        return port == null || port.installed() && MachineAccess.canUse(port, player)
                && player.distanceToSqr(Vec3.atCenterOf(port.getBlockPos())) <= 64;
    }
    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override
    public @Nullable BlockPos machinePos() { return port == null ? null : port.getBlockPos(); }
}
