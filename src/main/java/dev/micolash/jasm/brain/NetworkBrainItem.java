package dev.micolash.jasm.brain;

import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.core.BrainLevels;
import dev.micolash.jasm.core.BrainProgress;
import dev.micolash.jasm.core.BrainSize;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/** A Network Brain as an item: says how far it has grown. */
public class NetworkBrainItem extends BlockItem {
    public NetworkBrainItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        BrainProgress progress = stack.getOrDefault(JasmComponents.BRAIN.get(), BrainProgress.START);
        int percent = BrainLevels.percent(progress, BrainSize.CUBE_3, BrainBalance.fromConfig());
        builder.accept(Component.translatable("tooltip.jasm.network_brain.level", progress.level(), percent).withStyle(ChatFormatting.GRAY));
    }
}
