package dev.micolash.jasm.wafer;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.core.StampPolicy;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
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
 * What a wafer refuses to store. Anything holding items is refused so storage can never nest; an empty wafer is the one
 * exception, since it holds nothing. Oversized or unsaveable items are refused so a single item can never stop wafer
 * records from saving. Every insertion path checks this first.
 */
public final class WaferEligibility {
    /** Items in this tag are never stored, for pack makers to exclude anything else that holds items. */
    public static final TagKey<Item> REJECTED = TagKey.create(Registries.ITEM, Jasm.id("rejected_by_wafers"));

    public enum Result {
        OK,
        EMPTY,
        /** A Deck, or anything that holds other items. */
        CONTAINER,
        /** A wafer that holds items or fluid, is still combining, or can't be checked right now. */
        FULL_WAFER,
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

    public static Result check(WaferStore store, ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return Result.EMPTY;
        }
        if (stack.getItem() instanceof WaferItem) {
            if (!isEmptyWafer(store, stack)) {
                return Result.FULL_WAFER;
            }
        } else if (holdsItems(stack)) {
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
        if (encoded.get().sizeInBytes() > Tuning.WAFER_MAX_ITEM_DATA_BYTES) {
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
        if (encoded.get().sizeInBytes() > Tuning.WAFER_MAX_ITEM_DATA_BYTES) {
            return Result.TOO_LARGE;
        }
        return Result.OK;
    }

    /**
     * Whether a wafer holds nothing at all: a blank one, or the newest copy of a wafer whose record has no items and no
     * fluid (settings and a name don't count). A wafer still combining holds what its sources hold. A copy, a lost
     * original, or one whose record can't be read is never called empty, so storing it can't keep a bad copy around.
     */
    public static boolean isEmptyWafer(WaferStore store, ItemStack stack) {
        if (!(stack.getItem() instanceof WaferItem wafer) || WaferMerge.isPending(stack)) {
            return false;
        }
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        if (identity == null) {
            return true;
        }
        WaferRecord record = store.find(identity).record();
        if (record == null || record.kind() != wafer.tier().kind() || record.types() != wafer.tier().types()) {
            return false;
        }
        return StampPolicy.judge(identity.stamp(), wafer.tier().capacity(), record.view()).grantsAccess() && record.isEmpty();
    }

    /**
     * Decks, and anything vanilla keeps out of containers (shulker boxes), are always refused. Other containers (chests,
     * bundles, backpacks from other mods) are refused only while they hold something. Wafers are checked on their own.
     */
    private static boolean holdsItems(ItemStack stack) {
        if (stack.getItem() instanceof DeckItem || !stack.getItem().canFitInsideContainerItems()) {
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
