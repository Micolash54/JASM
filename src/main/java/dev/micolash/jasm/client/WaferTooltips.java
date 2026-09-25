package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferItem;
import dev.micolash.jasm.wafer.WaferNumbers;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Adds the wafer's number (and, with advanced tooltips, its id) for admins only. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class WaferTooltips {
    private WaferTooltips() {}

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        WaferIdentity identity = event.getItemStack().get(JasmComponents.WAFER_IDENTITY.get());
        if (identity == null || !(event.getItemStack().getItem() instanceof WaferItem) || !WaferNumbers.visibleTo(event.getEntity())) {
            return;
        }
        event.getToolTip().add(Component.translatable("tooltip.jasm.wafer.serial", String.format("%,d", identity.serial()))
                .withStyle(ChatFormatting.DARK_GRAY));
        if (event.getFlags().isAdvanced()) {
            event.getToolTip().add(Component.literal(identity.id().toString()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
