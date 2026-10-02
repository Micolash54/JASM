package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.NetworkViewPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/** Outlines the block picked on the Deck's Network tab for ten seconds, seen through walls. A new pick replaces the old. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class NetworkGlow {
    private static final int TICKS = 200;
    private static final float PADDING = 0.02F;
    private static final float WIDTH = 3F;

    private static @Nullable BlockPos pos;
    private static @Nullable ResourceKey<Level> dimension;
    private static long until;

    private NetworkGlow() {}

    /** The Network tab's answers from the server are handled here, on the client only. */
    @SubscribeEvent
    static void registerHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(NetworkViewPayloads.View.TYPE, (payload, context) -> NetworkPanel.receive(payload));
        event.register(NetworkViewPayloads.Glow.TYPE, (payload, context) -> receive(payload));
    }

    public static void receive(NetworkViewPayloads.Glow glow) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(glow.dimension())) {
            return;
        }
        pos = glow.pos();
        dimension = glow.dimension();
        until = minecraft.level.getGameTime() + TICKS;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (pos == null) {
            return;
        }
        if (minecraft.level == null || !minecraft.level.dimension().equals(dimension) || minecraft.level.getGameTime() >= until) {
            pos = null;
            return;
        }
        try (var ignored = minecraft.collectPerTickGizmos()) {
            Gizmos.cuboid(pos, PADDING, GizmoStyle.stroke(0xFF000000 | JasmGui.ACCENT, WIDTH)).setAlwaysOnTop();
        }
    }
}
