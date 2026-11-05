package dev.micolash.jasm.relay;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * What a mined Relay Chatterbox keeps inside its item: its frequency, its operator and its Dimension Upgrade. Only
 * placing the block again gives them back: nothing reads the items out of the item.
 */
public record RelayContents(int frequency, ItemStack operator, ItemStack upgrade) {
    public static final Codec<RelayContents> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("frequency", RelayEnd.NO_FREQUENCY).forGetter(RelayContents::frequency),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("operator", ItemStack.EMPTY).forGetter(RelayContents::operator),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("upgrade", ItemStack.EMPTY).forGetter(RelayContents::upgrade))
            .apply(i, RelayContents::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, RelayContents> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RelayContents::frequency,
            ItemStack.OPTIONAL_STREAM_CODEC, RelayContents::operator,
            ItemStack.OPTIONAL_STREAM_CODEC, RelayContents::upgrade,
            RelayContents::new);

    /** Whether it holds an item, not just a frequency. */
    public boolean holdsItems() {
        return !operator.isEmpty() || !upgrade.isEmpty();
    }

    // Item stacks compare by identity: two records holding equal items are the same.
    @Override
    public boolean equals(Object other) {
        return other instanceof RelayContents that && frequency == that.frequency && ItemStack.matches(operator, that.operator)
                && ItemStack.matches(upgrade, that.upgrade);
    }

    @Override
    public int hashCode() {
        return (frequency * 31 + ItemStack.hashItemAndComponents(operator)) * 31 + ItemStack.hashItemAndComponents(upgrade);
    }
}
