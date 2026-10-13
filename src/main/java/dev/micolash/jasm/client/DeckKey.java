package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckPayloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** The open Deck key (B): opens the worn Deck, else the first one carried. The server picks which. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class DeckKey {
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Jasm.id("jasm"));
    public static final KeyMapping OPEN = new KeyMapping("key.jasm.open_deck", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B, CATEGORY);

    private DeckKey() {}

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) {
                ClientPacketDistributor.sendToServer(DeckPayloads.OpenKey.INSTANCE);
            }
        }
    }
}
