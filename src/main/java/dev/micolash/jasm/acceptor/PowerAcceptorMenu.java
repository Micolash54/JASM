package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.registry.JasmMenus;
import java.util.function.Predicate;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** The Power Acceptor's menu: no slots, just the mode and the power going through. Button ids pick a mode. */
public class PowerAcceptorMenu extends AbstractContainerMenu {
    static final int DATA_COUNT = 3;

    private final ContainerData data;
    private final Predicate<Player> valid;
    private final @Nullable ModeHolder acceptor;

    /** Server side. {@code valid} says whether a player may still use it: the acceptor is still there and close enough. */
    public PowerAcceptorMenu(int containerId, ModeHolder acceptor, ContainerData data, Predicate<Player> valid) {
        super(JasmMenus.POWER_ACCEPTOR.get(), containerId);
        this.acceptor = acceptor;
        this.data = data;
        this.valid = valid;
        addDataSlots(data);
    }

    /** Client side. */
    public PowerAcceptorMenu(int containerId, Inventory inventory) {
        super(JasmMenus.POWER_ACCEPTOR.get(), containerId);
        this.acceptor = null;
        this.data = new SimpleContainerData(DATA_COUNT);
        this.valid = player -> true;
        addDataSlots(data);
    }

    public AcceptorMode mode() {
        return AcceptorMode.byId(data.get(0));
    }

    /** FE a tick going through, in or out as the mode says, averaged over the last second. */
    public int flow() {
        return data.get(2) << 16 | data.get(1) & 0xFFFF;
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
        return valid.test(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
