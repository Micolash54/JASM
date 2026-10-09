package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The Power Acceptor's menu: no slots, just the mode. Button ids 0 to 2 pick a mode. */
public class PowerAcceptorMenu extends AbstractContainerMenu {
    static final int DATA_COUNT = 1;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable PowerAcceptorBlockEntity acceptor;

    /** Server side. */
    public PowerAcceptorMenu(int containerId, PowerAcceptorBlockEntity acceptor, ContainerData data, ContainerLevelAccess access) {
        super(JasmMenus.POWER_ACCEPTOR.get(), containerId);
        this.acceptor = acceptor;
        this.data = data;
        this.access = access;
        addDataSlots(data);
    }

    /** Client side. */
    public PowerAcceptorMenu(int containerId, Inventory inventory) {
        super(JasmMenus.POWER_ACCEPTOR.get(), containerId);
        this.acceptor = null;
        this.data = new SimpleContainerData(DATA_COUNT);
        this.access = ContainerLevelAccess.NULL;
        addDataSlots(data);
    }

    public AcceptorMode mode() {
        return AcceptorMode.byId(data.get(0));
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (acceptor == null || id < 0 || id >= AcceptorMode.values().length || !stillValid(player)) {
            return false;
        }
        acceptor.setMode(AcceptorMode.byId(id));
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> level.getBlockEntity(pos) == acceptor
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64, true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
