package dev.micolash.jasm.deck;

import dev.micolash.jasm.Notices;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Filling and emptying the container on a player's cursor from and into a Deck's fluid wafers. Every step first
 * checks what both sides would take, then moves exactly that: fluid is never created or lost.
 */
public final class DeckFluids {
    /** Most steps one click may take, so a huge tank can't stall the server. */
    static final int MAX_STEPS = 256;
    private static final int BUCKET = 1000;

    private DeckFluids() {}

    /**
     * Fills the cursor container from the Deck: a bucket's worth, or as much as it takes when {@code whole}. With an
     * empty hand on a fluid that has a bucket, an empty bucket is taken from the Deck's item wafers first, and goes
     * back if nothing could be poured in. Returns the millibuckets moved.
     */
    public static long fill(ServerPlayer player, DeckMenu menu, DeckStorage.Checked storage, FluidResource key, boolean whole,
            boolean toInventory) {
        if (key.isEmpty() || storage.countFluid(key) <= 0) {
            return 0;
        }
        ItemStack deck = menu.deck();
        if (!DeckStorage.canAfford(deck, player, true)) {
            return 0;
        }
        boolean grabbedBucket = false;
        int fromSlot = -1;
        if (menu.getCarried().isEmpty() && key.getFluid().getBucket() != Items.AIR) {
            ItemResource bucket = ItemResource.of(Items.BUCKET);
            fromSlot = emptyBucketSlot(player);
            if (fromSlot >= 0) {
                menu.setCarried(player.getInventory().getItem(fromSlot).split(1));
            } else if (storage.count(bucket) >= 1) {
                var taken = storage.withdraw(bucket, 1);
                if (!taken.isEmpty()) {
                    menu.setCarried(taken.getFirst());
                    grabbedBucket = true;
                }
            } else {
                Notices.bad(player, Component.translatable("message.jasm.deck.fluid.no_bucket"));
                return 0;
            }
        }
        net.minecraft.world.item.Item carriedBefore = menu.getCarried().getItem();
        ResourceHandler<FluidResource> container = ItemAccess.forPlayerCursor(player, menu).getCapability(Capabilities.Fluid.ITEM);
        long moved = 0;
        if (container != null) {
            int steps = whole ? MAX_STEPS : 1;
            while (steps-- > 0) {
                long wanted = Math.min(storage.countFluid(key), whole ? Integer.MAX_VALUE : BUCKET);
                if (wanted <= 0) {
                    break;
                }
                int accepted;
                try (Transaction tx = Transaction.openRoot()) {
                    accepted = container.insert(key, (int) wanted, tx);
                }
                if (accepted <= 0) {
                    break;
                }
                long taken = storage.withdrawFluid(key, accepted, true);
                if (taken <= 0) {
                    break;
                }
                int inserted;
                try (Transaction tx = Transaction.openRoot()) {
                    inserted = container.insert(key, (int) taken, tx);
                    tx.commit();
                }
                moved += inserted;
                if (inserted < taken) {
                    giveBack(player, menu, key, taken - inserted);
                    break;
                }
            }
        }
        if (moved > 0) {
            play(player, key.getFluidType().getSound(SoundActions.BUCKET_FILL));
        } else {
            Notices.bad(player, Component.translatable("message.jasm.deck.fluid.no_fill"));
        }
        if (fromSlot >= 0 && menu.getCarried().is(Items.BUCKET)) {
            putBack(player, fromSlot, menu.getCarried());
            menu.setCarried(ItemStack.EMPTY);
        }
        if (grabbedBucket && menu.getCarried().is(Items.BUCKET)) {
            ItemStack back = menu.getCarried();
            DeckStorage.depositQuietly(WaferStore.get(player.level().getServer()), deck, back, player);
            menu.setCarried(back);
        }
        if (toInventory && moved > 0 && !menu.getCarried().isEmpty() && !menu.getCarried().is(carriedBefore)) {
            if (player.getInventory().add(menu.getCarried())) {
                menu.setCarried(ItemStack.EMPTY);
            }
        }
        return moved;
    }

    /**
     * Pours the cursor container into the Deck's fluid wafers: a bucket's worth, or everything the Deck has room and
     * charge for when {@code whole}. Returns the millibuckets moved.
     */
    public static long empty(ServerPlayer player, DeckMenu menu, DeckStorage.Checked storage, boolean whole) {
        ResourceHandler<FluidResource> container = ItemAccess.forPlayerCursor(player, menu).getCapability(Capabilities.Fluid.ITEM);
        return container == null ? 0 : pour(player, menu, storage, container, whole);
    }

    /** Pours {@code container} into the Deck's fluid wafers, as {@link #empty} does. Returns the millibuckets moved. */
    public static long pour(ServerPlayer player, DeckMenu menu, DeckStorage.Checked storage, ResourceHandler<FluidResource> container,
            boolean whole) {
        FluidResource key;
        try (Transaction tx = Transaction.openRoot()) {
            var first = ResourceHandlerUtil.extractFirst(container, resource -> true, Integer.MAX_VALUE, tx);
            key = first == null ? FluidResource.EMPTY : first.resource();
        }
        if (key.isEmpty()) {
            return 0;
        }
        ItemStack deck = menu.deck();
        if (!DeckStorage.canAfford(deck, player, true)) {
            return 0;
        }
        long moved = 0;
        int steps = whole ? MAX_STEPS : 1;
        boolean noRoom = false;
        while (steps-- > 0) {
            int offered;
            try (Transaction tx = Transaction.openRoot()) {
                offered = container.extract(key, whole ? Integer.MAX_VALUE : BUCKET, tx);
            }
            if (offered <= 0) {
                break;
            }
            long allowed = Math.min(storage.roomFluid(key, offered), DeckStorage.affordableFluid(deck));
            if (allowed <= 0) {
                noRoom = moved == 0;
                break;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = container.extract(key, (int) allowed, tx);
                if (extracted <= 0) {
                    break;
                }
                long stored = storage.depositFluid(key, extracted);
                if (stored != extracted) {
                    takeBack(player, menu, key, stored);
                    break;
                }
                tx.commit();
                moved += stored;
            }
        }
        if (moved > 0) {
            play(player, key.getFluidType().getSound(SoundActions.BUCKET_EMPTY));
        } else if (noRoom) {
            Notices.bad(player, Component.translatable("message.jasm.deck.fluid.no_room"));
        }
        return moved;
    }

    /** The first fluid the stack holds (a bucket of water, a filled tank), or null if it holds none or isn't a container. */
    public static @Nullable FluidResource contained(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        ResourceHandler<FluidResource> container = ItemAccess.forStack(stack.copyWithCount(1)).getCapability(Capabilities.Fluid.ITEM);
        if (container == null) {
            return null;
        }
        // Read, not a test pour: a loose bucket can't turn into an empty one, so a test pour would always fail.
        for (int index = 0; index < container.size(); index++) {
            FluidResource resource = container.getResource(index);
            if (!resource.isEmpty() && container.getAmountAsLong(index) > 0) {
                return resource;
            }
        }
        return null;
    }

    /** The first slot of the player's main inventory holding an empty bucket, or -1. */
    private static int emptyBucketSlot(ServerPlayer player) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (player.getInventory().getItem(slot).is(Items.BUCKET)) {
                return slot;
            }
        }
        return -1;
    }

    /** Returns an unused empty bucket to the slot it came from, or anywhere else in the inventory if that slot is taken. */
    private static void putBack(ServerPlayer player, int slot, ItemStack bucket) {
        ItemStack there = player.getInventory().getItem(slot);
        if (there.isEmpty()) {
            player.getInventory().setItem(slot, bucket);
        } else if (ItemStack.isSameItemSameComponents(there, bucket) && there.getCount() < there.getMaxStackSize()) {
            there.grow(bucket.getCount());
        } else if (!player.getInventory().add(bucket)) {
            player.drop(bucket, false);
        }
    }

    /** Puts fluid that a container refused back on the wafers, without charge. */
    private static void giveBack(ServerPlayer player, DeckMenu menu, FluidResource key, long amount) {
        WaferStore store = WaferStore.get(player.level().getServer());
        long left = amount;
        for (WaferRecord record : DeckStorage.records(store, menu.deck())) {
            if (record != null && left > 0) {
                left -= store.insertFluid(record, key, left, false, player);
            }
        }
    }

    /** Takes fluid that was just stored back off the wafers, without charge. */
    private static void takeBack(ServerPlayer player, DeckMenu menu, FluidResource key, long amount) {
        WaferStore store = WaferStore.get(player.level().getServer());
        long left = amount;
        for (WaferRecord record : DeckStorage.records(store, menu.deck())) {
            if (record != null && left > 0) {
                left -= store.extractFluid(record, key, left, false, player);
            }
        }
    }

    private static void play(ServerPlayer player, @Nullable SoundEvent sound) {
        if (sound != null) {
            player.level().playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }
}
