package dev.micolash.jasm.wafer;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.deck.DeckItem;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * What a wafer refuses to store. Anything holding items is refused so storage can never nest; oversized or unsaveable items
 * are refused so a single item can never stop wafer records from saving. Every insertion path checks this first.
 */
public final class WaferEligibility {
    /** Items in this tag are never stored, for pack makers to exclude anything else that holds items. */
    public static final TagKey<Item> REJECTED = TagKey.create(Registries.ITEM, Jasm.id("rejected_by_wafers"));

    public enum Result {
        OK,
        EMPTY,
        /** A wafer, a Deck, or anything that holds other items. */
        CONTAINER,
        /** In the {@link #REJECTED} tag. */
        REJECTED,
        /** Its data cannot be saved. */
        UNSAVEABLE,
        /** Its data is larger than the configured limit. */
        TOO_LARGE;

        public boolean accepted() {
            return this == OK;
        }

        public String messageKey() {
            return "message.jasm.wafer.refused." + name().toLowerCase(Locale.ROOT);
        }
    }

    private WaferEligibility() {}

    public static Result check(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return Result.EMPTY;
        }
        if (holdsItems(stack)) {
            return Result.CONTAINER;
        }
        if (stack.is(REJECTED)) {
            return Result.REJECTED;
        }
        Optional<Tag> encoded = ItemResource.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), ItemResource.of(stack))
                .result();
        if (encoded.isEmpty()) {
            return Result.UNSAVEABLE;
        }
        if (encoded.get().sizeInBytes() > JasmConfig.WAFER_MAX_ITEM_DATA_BYTES.getAsInt()) {
            return Result.TOO_LARGE;
        }
        return Result.OK;
    }

    /** What a fluid wafer refuses: nothing, or a fluid whose data can't be saved or is too large. */
    public static Result checkFluid(FluidResource fluid, HolderLookup.Provider registries) {
        if (fluid.isEmpty()) {
            return Result.EMPTY;
        }
        Optional<Tag> encoded = FluidResource.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), fluid).result();
        if (encoded.isEmpty()) {
            return Result.UNSAVEABLE;
        }
        if (encoded.get().sizeInBytes() > JasmConfig.WAFER_MAX_ITEM_DATA_BYTES.getAsInt()) {
            return Result.TOO_LARGE;
        }
        return Result.OK;
    }

    /**
     * Wafers, Decks, and anything vanilla keeps out of containers (shulker boxes) are always refused. Other
     * containers (chests, bundles, backpacks from other mods) are refused only while they hold something.
     */
    private static boolean holdsItems(ItemStack stack) {
        if (stack.getItem() instanceof WaferItem || stack.getItem() instanceof DeckItem || !stack.getItem().canFitInsideContainerItems()) {
            return true;
        }
        if (stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems().iterator().hasNext()
                || !stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).isEmpty()
                || stack.has(DataComponents.CONTAINER_LOOT)) {
            return true;
        }
        ResourceHandler<ItemResource> handler = ItemAccess.forStack(stack.copyWithCount(1)).getCapability(Capabilities.Item.ITEM);
        if (handler != null) {
            for (int i = 0; i < handler.size(); i++) {
                if (!handler.getResource(i).isEmpty() && handler.getAmountAsLong(i) > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
