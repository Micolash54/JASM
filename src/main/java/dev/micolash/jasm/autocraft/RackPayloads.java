package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.NetworkNode;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Messages of the Rack Controller's panel, and the Recipe Rack's rename. Nothing the screen sends is trusted. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class RackPayloads {
    private RackPayloads() {}

    /** Most racks gone in one message: as many as a network can ever list. */
    private static final int MAX_GONE = 4_096;

    /**
     * Server → client: racks of the open controller's network that are new or changed, and those no longer listed.
     * {@code reset} starts the list afresh; {@code complete} is false while part of the network isn't loaded.
     */
    public record RackRows(int containerId, boolean reset, boolean complete, List<RackControllerMenu.RackView> racks,
            List<NetworkNode> gone) implements CustomPacketPayload {
        public static final Type<RackRows> TYPE = new Type<>(Jasm.id("rack_rows"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RackRows> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RackRows::containerId,
                ByteBufCodecs.BOOL, RackRows::reset,
                ByteBufCodecs.BOOL, RackRows::complete,
                RackControllerMenu.RackView.STREAM_CODEC.apply(ByteBufCodecs.list(RackControllerMenu.PER_MESSAGE)), RackRows::racks,
                NetworkNode.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_GONE)), RackRows::gone,
                RackRows::new);

        @Override
        public Type<RackRows> type() {
            return TYPE;
        }
    }

    /** Client → server: a click on a rack's slot in the controller's panel, and what the panel showed there. */
    public record RackClick(int containerId, NetworkNode rack, int slot, ItemStack expected, boolean quick) implements CustomPacketPayload {
        public static final Type<RackClick> TYPE = new Type<>(Jasm.id("rack_click"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RackClick> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RackClick::containerId,
                NetworkNode.STREAM_CODEC, RackClick::rack,
                ByteBufCodecs.VAR_INT, RackClick::slot,
                ItemStack.OPTIONAL_STREAM_CODEC, RackClick::expected,
                ByteBufCodecs.BOOL, RackClick::quick,
                RackClick::new);

        @Override
        public Type<RackClick> type() {
            return TYPE;
        }
    }

    /** Client → server: rename the open Recipe Rack; empty goes back to the block's own name. */
    public record RackName(int containerId, String name) implements CustomPacketPayload {
        public static final Type<RackName> TYPE = new Type<>(Jasm.id("rack_name"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RackName> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RackName::containerId,
                ByteBufCodecs.stringUtf8(AccessPortMenu.MAX_NAME), RackName::name,
                RackName::new);

        @Override
        public Type<RackName> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(RackClick.TYPE, RackClick.STREAM_CODEC, (payload, context) -> click((ServerPlayer) context.player(), payload))
                .playToServer(RackName.TYPE, RackName.STREAM_CODEC, (payload, context) -> name((ServerPlayer) context.player(), payload))
                .playToClient(RackRows.TYPE, RackRows.STREAM_CODEC, RackPayloads::onRows);
    }

    /** A click in the open controller's panel; see {@link RackControllerMenu#click}. */
    public static boolean click(ServerPlayer player, RackClick payload) {
        return player.containerMenu instanceof RackControllerMenu menu && menu.containerId == payload.containerId()
                && menu.click(player, payload.rack(), payload.slot(), payload.expected(), payload.quick());
    }

    /** Names the open Recipe Rack. Refused for a name the rename panel wouldn't take. */
    public static boolean name(ServerPlayer player, RackName payload) {
        return player.containerMenu instanceof RecipeRackMenu menu && menu.containerId == payload.containerId()
                && menu.rename(player, payload.name());
    }

    private static void onRows(RackRows payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof RackControllerMenu menu && menu.containerId == payload.containerId()) {
            menu.apply(payload);
        }
    }
}
