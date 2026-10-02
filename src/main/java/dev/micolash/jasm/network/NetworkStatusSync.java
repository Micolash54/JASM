package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.client.ClientNetworkStatus;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Tells an open machine screen whether its network is full, and by how much, twice a second while it changes. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class NetworkStatusSync {
    private static final int EVERY = 10;
    private static final Map<AbstractContainerMenu, Full> SENT = new WeakHashMap<>();

    public record Full(int containerId, boolean full, int count, int limit) implements CustomPacketPayload {
        public static final Type<Full> TYPE = new Type<>(Jasm.id("network_full"));
        public static final StreamCodec<ByteBuf, Full> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Full::containerId,
                ByteBufCodecs.BOOL, Full::full,
                ByteBufCodecs.VAR_INT, Full::count,
                ByteBufCodecs.VAR_INT, Full::limit,
                Full::new);

        @Override
        public Type<Full> type() {
            return TYPE;
        }
    }

    private NetworkStatusSync() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Full.TYPE, Full.STREAM_CODEC, NetworkStatusSync::onFull);
    }

    private static void onFull(Full payload, IPayloadContext context) {
        ClientNetworkStatus.receive(payload);
    }

    /** What the player's open machine screen should say about its network right now. */
    public static Full statusFor(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        BlockPos pos = menu instanceof MachineView view ? view.machinePos() : null;
        if (pos == null || !(player.level() instanceof ServerLevel level)) {
            return new Full(menu.containerId, false, 0, 0);
        }
        CableNetwork network = Networks.at(level, pos);
        if (network == null) {
            return new Full(menu.containerId, false, 0, 0);
        }
        NetworkLimit.State state = network.limitState();
        return new Full(menu.containerId, state.stopped(), state.count(), state.limit());
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player.containerMenu instanceof MachineView)) continue;
            Full now = statusFor(player);
            if (!now.equals(SENT.get(player.containerMenu))) {
                SENT.put(player.containerMenu, now);
                if (player.connection.hasChannel(Full.TYPE)) {
                    PacketDistributor.sendToPlayer(player, now);
                }
            }
        }
    }
}
