package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.transfer.UpgradeTargets;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** An upgrade's tooltip lists the machines it can be installed in, one per line, while Shift is held. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class UpgradeTooltips {
    private UpgradeTooltips() {}

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        List<Component> names = UpgradeTargets.worksIn(event.getItemStack());
        if (names.isEmpty()) return;
        if (!Minecraft.getInstance().hasShiftDown()) {
            Component key = Component.literal("[Shift]").withStyle(ChatFormatting.GRAY);
            event.getToolTip().add(Component.translatable("tooltip.jasm.upgrade.shift", key).withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        for (Component name : names) event.getToolTip().add(Component.empty().append(name).withStyle(ChatFormatting.GRAY));
    }
}
