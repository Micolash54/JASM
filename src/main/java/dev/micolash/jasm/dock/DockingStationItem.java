package dev.micolash.jasm.dock;

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

/** The Docking Station as an item. A mined dock that still holds a Deck says whose it is and whether it is locked. */
public class DockingStationItem extends BlockItem {
    public DockingStationItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, display, builder, flag);
        DockedDeck held = stack.get(JasmComponents.DOCKED_DECK.get());
        if (held == null || held.deck().isEmpty()) return;
        String name = held.ownerName().isEmpty() ? "?" : held.ownerName();
        builder.accept(Component.translatable(held.locked() ? "tooltip.jasm.docking_station.holds_locked" : "tooltip.jasm.docking_station.holds_unlocked",
                name).withStyle(ChatFormatting.GRAY));
    }
}
