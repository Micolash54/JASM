package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.DataCableBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Shows each Data Cable's transfer rate. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class CableTooltips {
    private CableTooltips() {}

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof DataCableBlock cable) {
            event.getToolTip().add(Component.translatable("tooltip.jasm.cable.rate", String.format("%,d", cable.tier().rate()))
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
