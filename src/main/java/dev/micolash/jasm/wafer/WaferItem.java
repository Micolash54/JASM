package dev.micolash.jasm.wafer;

import dev.micolash.jasm.registry.JasmComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

public class WaferItem extends Item {
    private final WaferTier tier;

    public WaferItem(Item.Properties properties, WaferTier tier) {
        super(properties.stacksTo(1));
        this.tier = tier;
    }

    public WaferTier tier() {
        return tier;
    }

    /** Wafers must never nest inside bundles, shulker boxes, or other container items. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        builder.accept(Component.translatable("tooltip.jasm.wafer.capacity", String.format("%,d", tier.capacity()))
                .withStyle(ChatFormatting.GRAY));
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        if (identity == null) {
            builder.accept(Component.translatable("tooltip.jasm.wafer.blank").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            String id = flag.isAdvanced() ? identity.id().toString() : identity.id().toString().substring(0, 8);
            builder.accept(Component.translatable("tooltip.jasm.wafer.id", id).withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
