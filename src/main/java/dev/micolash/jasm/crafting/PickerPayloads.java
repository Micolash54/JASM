package dev.micolash.jasm.crafting;

import dev.micolash.jasm.Jasm;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The recipe picker's messages: the list of results for the open screen, and the player's pick. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class PickerPayloads {
    private PickerPayloads() {}

    public static final StreamCodec<RegistryFriendlyByteBuf, ResourceKey<Recipe<?>>> RECIPE_KEY =
            ResourceKey.streamCodec(Registries.RECIPE).cast();

    /** One result the grid can make, and the recipe that makes it. */
    public record Entry(ResourceKey<Recipe<?>> id, ItemStack result) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                RECIPE_KEY, Entry::id,
                ItemStack.STREAM_CODEC, Entry::result,
                Entry::new);
    }

    /** Server → client: what the open screen's grid can make, and which one is in use (-1 for none). */
    public record Shown(int containerId, List<Entry> entries, int current) implements CustomPacketPayload {
        public static final Type<Shown> TYPE = new Type<>(Jasm.id("picker_shown"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Shown> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Shown::containerId,
                Entry.STREAM_CODEC.apply(ByteBufCodecs.list(RecipeChoices.MAX)), Shown::entries,
                ByteBufCodecs.VAR_INT, Shown::current,
                Shown::new);

        @Override
        public Type<Shown> type() {
            return TYPE;
        }
    }

    /** Client → server: the player picked the result of recipe {@code id}. */
    public record Pick(int containerId, ResourceKey<Recipe<?>> id) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = new Type<>(Jasm.id("picker_pick"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pick> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Pick::containerId,
                RECIPE_KEY, Pick::id,
                Pick::new);

        @Override
        public Type<Pick> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Pick.TYPE, Pick.STREAM_CODEC, (payload, context) -> pick((ServerPlayer) context.player(), payload))
                .playToClient(Shown.TYPE, Shown.STREAM_CODEC, PickerPayloads::onShown);
    }

    /** The server checks the screen is really open; the menu checks the recipe still fits. */
    public static boolean pick(ServerPlayer player, Pick payload) {
        if (player.containerMenu.containerId == payload.containerId() && player.containerMenu.stillValid(player)
                && player.containerMenu instanceof RecipePicking picking) {
            picking.pickRecipe(player, payload.id());
            return true;
        }
        return false;
    }

    private static void onShown(Shown payload, IPayloadContext context) {
        if (context.player().containerMenu.containerId == payload.containerId()
                && context.player().containerMenu instanceof RecipePicking picking) {
            picking.pickerView().set(payload.entries(), payload.current());
        }
    }
}
