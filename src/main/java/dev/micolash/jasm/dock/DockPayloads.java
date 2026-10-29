package dev.micolash.jasm.dock;

import dev.micolash.jasm.Jasm;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;

/** Messages between the Docking Station's panel and the server, and the rule that keeps a dock from being broken by the wrong player. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class DockPayloads {
    private DockPayloads() {}

    public enum Action { TAKE_OUT, LOCK, UNLOCK }

    /**
     * A key in the panel. {@code action} travels as a number and anything unknown is ignored; {@code deck} is the Deck the
     * panel showed, so a request about a Deck that has gone is turned down.
     */
    public record Act(int containerId, int action, Optional<UUID> deck) implements CustomPacketPayload {
        public static final Type<Act> TYPE = new Type<>(Jasm.id("dock_act"));
        public static final StreamCodec<ByteBuf, Act> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Act::containerId,
                ByteBufCodecs.VAR_INT, Act::action,
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Act::deck,
                Act::new);

        public static Act of(int containerId, Action action, @Nullable UUID deck) {
            return new Act(containerId, action.ordinal(), Optional.ofNullable(deck));
        }

        @Override
        public Type<Act> type() {
            return TYPE;
        }
    }

    /** Whose Deck sits in the dock, for the panel. */
    public record Owner(int containerId, String name) implements CustomPacketPayload {
        public static final Type<Owner> TYPE = new Type<>(Jasm.id("dock_owner"));
        public static final StreamCodec<ByteBuf, Owner> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Owner::containerId,
                ByteBufCodecs.stringUtf8(64), Owner::name,
                Owner::new);

        @Override
        public Type<Owner> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Act.TYPE, Act.STREAM_CODEC, (payload, context) -> act((ServerPlayer) context.player(), payload))
                .playToClient(Owner.TYPE, Owner.STREAM_CODEC, DockPayloads::onOwner);
    }

    /** Only counts if that exact dock panel is open and the player may still use it. */
    public static void act(ServerPlayer player, Act payload) {
        if (!(player.containerMenu instanceof DockingStationMenu menu) || menu.containerId != payload.containerId() || !menu.stillValid(player)) return;
        Action[] actions = Action.values();
        // A key always names the Deck it was pressed about; one that doesn't is not from the panel.
        if (payload.action() < 0 || payload.action() >= actions.length || payload.deck().isEmpty()) return;
        menu.act(player, actions[payload.action()], payload.deck().get());
    }

    private static void onOwner(Owner payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof DockingStationMenu menu && menu.containerId == payload.containerId()) {
            menu.setDeckOwnerName(payload.name());
        }
    }

    /** A dock with a Deck in it breaks only for those who may take the Deck out; for anyone else it stays, tool unworn. */
    @SubscribeEvent
    static void onBreak(BreakBlockEvent event) {
        if (!event.getLevel().isClientSide() && event.getLevel().getBlockEntity(event.getPos()) instanceof DockingStationBlockEntity dock
                && !dock.mayBreak(event.getPlayer())) {
            event.setCanceled(true);
            event.setNotifyClient(true);
        }
    }
}
