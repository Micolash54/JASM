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
 * What a Filled Recipe Card remembers: the recipe, the 3×3 grid as it was encoded, what one craft makes, whether the
 * recipe is shapeless, and which grid slots are pinned. The grid is only an example: crafting may use any item the
 * recipe accepts in a slot, except in a pinned slot, which takes only the item shown there.
 */
public record RecipeCard(ResourceKey<Recipe<?>> recipe, ItemContainerContents grid, ItemStackTemplate output, boolean shapeless, int pinned)
        implements Card {
    public static final Codec<RecipeCard> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceKey.codec(Registries.RECIPE).fieldOf("recipe").forGetter(RecipeCard::recipe),
            ItemContainerContents.CODEC.fieldOf("grid").forGetter(RecipeCard::grid),
            ItemStackTemplate.CODEC.fieldOf("output").forGetter(RecipeCard::output),
            Codec.BOOL.optionalFieldOf("shapeless", false).forGetter(RecipeCard::shapeless),
            // Left out while nothing is pinned, so such cards save exactly as before.
            Pins.CODEC.optionalFieldOf("pinned", Pins.NONE).forGetter(RecipeCard::pinned))
            .apply(i, RecipeCard::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeCard> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.RECIPE), RecipeCard::recipe,
            ItemContainerContents.STREAM_CODEC, RecipeCard::grid,
            ItemStackTemplate.STREAM_CODEC, RecipeCard::output,
            ByteBufCodecs.BOOL, RecipeCard::shapeless,
            Pins.STREAM_CODEC, RecipeCard::pinned,
            RecipeCard::new);

    /** A card with nothing pinned. */
    public RecipeCard(ResourceKey<Recipe<?>> recipe, ItemContainerContents grid, ItemStackTemplate output, boolean shapeless) {
        this(recipe, grid, output, shapeless, Pins.NONE);
    }

    public RecipeCard {
        // only a slot that holds something can be pinned
        if (pinned != Pins.NONE) {
            NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
            grid.copyInto(items);
            int kept = Pins.NONE;
            for (int slot = 0; slot < 9; slot++) {
                kept = Pins.with(kept, slot, Pins.has(pinned, slot) && !items.get(slot).isEmpty());
            }
            pinned = kept;
        }
    }

    /** The encoded grid, 9 stacks, empty where nothing was placed. Fresh copies. */
    public List<ItemStack> inputs() {
        NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
        grid.copyInto(items);
        return items;
    }

    /** Whether grid slot {@code slot} takes only its own item. */
    public boolean pinned(int slot) {
        return Pins.has(pinned, slot);
    }

    /** What one craft makes. A fresh copy. */
    @Override
    public ItemStack result() {
        return output.create();
    }
}
