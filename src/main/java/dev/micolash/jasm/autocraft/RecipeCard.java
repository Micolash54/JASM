package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.Recipe;

/**
 * What a Filled Recipe Card remembers: the recipe, the 3×3 grid as it was encoded, what one craft makes, and whether
 * the recipe is shapeless. The grid is only an example: crafting may use any item the recipe accepts in a slot.
 */
public record RecipeCard(ResourceKey<Recipe<?>> recipe, ItemContainerContents grid, ItemStackTemplate output, boolean shapeless) implements Card {
    public static final Codec<RecipeCard> CODEC = RecordCodecBuilder.create(i -> i.group(
                    ResourceKey.codec(Registries.RECIPE).fieldOf("recipe").forGetter(RecipeCard::recipe),
                    ItemContainerContents.CODEC.fieldOf("grid").forGetter(RecipeCard::grid),
                    ItemStackTemplate.CODEC.fieldOf("output").forGetter(RecipeCard::output),
                    Codec.BOOL.optionalFieldOf("shapeless", false).forGetter(RecipeCard::shapeless))
            .apply(i, RecipeCard::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeCard> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.RECIPE), RecipeCard::recipe,
            ItemContainerContents.STREAM_CODEC, RecipeCard::grid,
            ItemStackTemplate.STREAM_CODEC, RecipeCard::output,
            ByteBufCodecs.BOOL, RecipeCard::shapeless,
            RecipeCard::new);

    /** The encoded grid, 9 stacks, empty where nothing was placed. Fresh copies. */
    public List<ItemStack> inputs() {
        NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
        grid.copyInto(items);
        return items;
    }

    /** What one craft makes. A fresh copy. */
    @Override
    public ItemStack result() {
        return output.create();
    }
}
