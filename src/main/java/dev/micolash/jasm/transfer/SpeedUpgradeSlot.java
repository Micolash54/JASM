package dev.micolash.jasm.transfer;

import dev.micolash.jasm.Jasm;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A machine's upgrade slot: it holds one item, whatever its container lets in, and shows the empty-upgrade picture. */
public final class SpeedUpgradeSlot extends Slot {
    public SpeedUpgradeSlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return container.canPlaceItem(getContainerSlot(), stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public Identifier getNoItemIcon() {
        return Jasm.id("container/empty_upgrade");
    }
}
