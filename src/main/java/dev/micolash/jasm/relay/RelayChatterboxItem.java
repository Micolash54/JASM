package dev.micolash.jasm.relay;

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

/** The Relay Chatterbox as an item. A mined one says which frequency it is on and what it still holds. */
public class RelayChatterboxItem extends BlockItem {
    public RelayChatterboxItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, display, builder, flag);
        builder.accept(Component.translatable("tooltip.jasm.relay_chatterbox").withStyle(ChatFormatting.GRAY));
        RelayContents held = stack.get(JasmComponents.RELAY_CONTENTS.get());
        if (held == null) return;
        if (held.frequency() != RelayEnd.NO_FREQUENCY) {
            builder.accept(Component.translatable("tooltip.jasm.relay_chatterbox.frequency", held.frequency()).withStyle(ChatFormatting.GRAY));
        }
        if (!held.operator().isEmpty()) {
            builder.accept(Component.translatable("tooltip.jasm.relay_chatterbox.holds", held.operator().getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        if (!held.upgrade().isEmpty()) {
            builder.accept(Component.translatable("tooltip.jasm.relay_chatterbox.holds", held.upgrade().getHoverName()).withStyle(ChatFormatting.GRAY));
        }
    }
}
