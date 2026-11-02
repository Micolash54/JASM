package dev.micolash.jasm.chunkload;

import dev.micolash.jasm.config.Feature;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmItems;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** Keeps the chunk of the machine it sits in loaded. One a machine. */
public final class ChunkloadUpgradeItem extends Item {
    public ChunkloadUpgradeItem(Properties properties) { super(properties); }

    /** Whether {@code stack} may go in {@code slot} as far as this goes: one Chunkload Upgrade a machine, the rest isn't limited here. */
    public static boolean fits(Container container, int from, int to, int slot, ItemStack stack) {
        if (!stack.is(JasmItems.CHUNKLOAD_UPGRADE.get()) || slot < from || slot >= to) return true;
        for (int i = from; i < to; i++) if (i != slot && container.getItem(i).is(JasmItems.CHUNKLOAD_UPGRADE.get())) return false;
        return true;
    }

    /** Whether one of the machine's upgrade slots holds a Chunkload Upgrade. */
    public static boolean installedIn(MachineBlockEntity machine) {
        for (int i = 0; i < machine.getContainerSize(); i++) {
            if (machine.isUpgradeSlot(i) && machine.getItem(i).is(JasmItems.CHUNKLOAD_UPGRADE.get())) return true;
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, display, builder, flag);
        if (!Feature.CHUNKLOAD_UPGRADE.on()) {
            builder.accept(Component.translatable("tooltip.jasm.chunkload_upgrade.disabled").withStyle(ChatFormatting.RED));
        }
    }
}
