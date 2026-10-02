package dev.micolash.jasm.client;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.autocraft.Card;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Shows a card's saved output through the output item's own model. */
public final class EncodedCardModel implements ItemModel {
    private final ItemModel fallback;
    private boolean resolvingOutput;

    public EncodedCardModel(ItemModel fallback) {
        this.fallback = fallback;
    }

    @Override
    public void update(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        output.appendModelIdentityElement(this);
        // An output can itself be a card, or contain one in another item's model.
        Card card = resolvingOutput || stack.isEmpty() ? null : Card.of(stack);
        ItemStack result = card == null ? ItemStack.EMPTY : card.result();
        if (result.isEmpty() || !result.has(DataComponents.ITEM_MODEL)) {
            fallback.update(output, stack, resolver, displayContext, level, owner, seed);
            return;
        }
        resolvingOutput = true;
        try {
            resolver.appendItemLayers(output, result, displayContext, level, owner, seed);
        } finally {
            resolvingOutput = false;
        }
    }

    public record Unbaked(ItemModel.Unbaked fallback) implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = ItemModels.CODEC.fieldOf("fallback")
                .xmap(Unbaked::new, Unbaked::fallback);

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            return new EncodedCardModel(fallback.bake(context, transformation));
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            fallback.resolveDependencies(resolver);
        }
    }
}
