package dev.micolash.jasm.tether;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/** The operator a mined Network Tether keeps inside its item. Only placing the block again gives it back. */
public record TetherContents(ItemStack operator) {
    public static final Codec<TetherContents> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("operator", ItemStack.EMPTY).forGetter(TetherContents::operator))
            .apply(i, TetherContents::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, TetherContents> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, TetherContents::operator,
            TetherContents::new);

    // Item stacks compare by identity: two records holding equal items are the same.
    @Override
    public boolean equals(Object other) {
        return other instanceof TetherContents that && ItemStack.matches(operator, that.operator);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(operator);
    }
}
