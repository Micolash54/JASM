package dev.micolash.jasm.client;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.deck.DeckItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * How full a Deck's battery is, in steps for the gauge on its screen: 0 when empty, otherwise 1 to {@link #STEPS}
 * (any charge at all shows at least one step).
 */
public record DeckCharge() implements RangeSelectItemModelProperty {
    public static final int STEPS = 6;
    public static final MapCodec<DeckCharge> MAP_CODEC = MapCodec.unit(new DeckCharge());

    @Override
    public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        if (!(stack.getItem() instanceof DeckItem deck)) {
            return 0;
        }
        int energy = DeckItem.energy(stack);
        return energy <= 0 ? 0 : Math.clamp((int) Math.ceil(energy * (double) STEPS / deck.tier().battery()), 1, STEPS);
    }

    @Override
    public MapCodec<DeckCharge> type() {
        return MAP_CODEC;
    }
}
