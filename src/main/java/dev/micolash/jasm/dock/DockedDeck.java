package dev.micolash.jasm.dock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * The Deck a mined Docking Station still holds, with its lock and whose it is. Only placing the dock again gives it back:
 * nothing reads the Deck out of the item.
 */
public record DockedDeck(ItemStack deck, boolean locked, Optional<UUID> owner, String ownerName) {
    public static final Codec<DockedDeck> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.CODEC.fieldOf("deck").forGetter(DockedDeck::deck),
            Codec.BOOL.optionalFieldOf("locked", true).forGetter(DockedDeck::locked),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(DockedDeck::owner),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(DockedDeck::ownerName))
            .apply(i, DockedDeck::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, DockedDeck> STREAM_CODEC = StreamCodec.composite(
            ItemStack.STREAM_CODEC, DockedDeck::deck,
            ByteBufCodecs.BOOL, DockedDeck::locked,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), DockedDeck::owner,
            ByteBufCodecs.STRING_UTF8, DockedDeck::ownerName,
            DockedDeck::new);

    // Item stacks compare by identity: two records holding equal Decks are the same.
    @Override
    public boolean equals(Object other) {
        return other instanceof DockedDeck that && locked == that.locked && owner.equals(that.owner) && ownerName.equals(that.ownerName)
                && ItemStack.matches(deck, that.deck);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(deck) * 31 + Boolean.hashCode(locked);
    }
}
