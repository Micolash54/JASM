package dev.micolash.jasm.tether;

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

/** The Network Tether as an item. A mined one says which operator it still holds. */
public class NetworkTetherItem extends BlockItem {
    public NetworkTetherItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, display, builder, flag);
        builder.accept(Component.translatable("tooltip.jasm.network_tether").withStyle(ChatFormatting.GRAY));
        TetherContents held = stack.get(JasmComponents.TETHER_CONTENTS.get());
        if (held != null && !held.operator().isEmpty()) {
            builder.accept(Component.translatable("tooltip.jasm.network_tether.holds", held.operator().getHoverName()).withStyle(ChatFormatting.GRAY));
        }
    }
}
