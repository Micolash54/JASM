package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/** Hints on JASM screens (buttons, bars, readings) get a Mocha tooltip; item tooltips keep the game's own look. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
final class MochaTooltips {
    private static final Identifier STYLE = Jasm.id("mocha");

    private MochaTooltips() {}

    @SubscribeEvent
    static void onTexture(RenderTooltipEvent.Texture event) {
        if (event.getItemStack().isEmpty() && event.getOriginalTexture() == null && Minecraft.getInstance().screen instanceof JasmScreen<?>) {
            event.setTexture(STYLE);
        }
    }
}
