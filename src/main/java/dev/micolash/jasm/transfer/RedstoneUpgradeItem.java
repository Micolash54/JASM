package dev.micolash.jasm.transfer;

import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class RedstoneUpgradeItem extends Item {
    public RedstoneUpgradeItem(Properties properties) { super(properties); }

    /** Whether the upgrade slots from {@code from} up to {@code to} hold a Redstone Upgrade anywhere but {@code slot}. */
    public static boolean elsewhere(Container container, int from, int to, int slot) {
        for (int i = from; i < to; i++) if (i != slot && container.getItem(i).is(JasmItems.REDSTONE_UPGRADE.get())) return true;
        return false;
    }

    /** Whether {@code stack} may go in {@code slot} as far as this goes: one Redstone Upgrade a machine, the rest isn't limited here. */
    public static boolean fits(Container container, int from, int to, int slot, ItemStack stack) {
        return !stack.is(JasmItems.REDSTONE_UPGRADE.get()) || slot < from || slot >= to || !elsewhere(container, from, to, slot);
    }
}
