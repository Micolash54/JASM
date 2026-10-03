package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckViewTracker;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** Handing a job's results back to the requester, and clearing a job away. */
final class JobReturns {
    private JobReturns() {}

    /** Requested products can leave during a job; ingredients and other products stay until it ends. */
    static boolean deliverTarget(ServerLevel level, CraftingServerBlockEntity server, CraftingJob job, WaferRecord record, WaferStore store) {
        if (job.target == null) return false;
        ItemResource target = ItemResource.of(job.target.create());
        long available = record.count(target) - targetNeeded(level, job, target);
        if (available <= 0) return false;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.requester);
        ItemStack deck = player == null ? ItemStack.EMPTY : findDeck(player, job.deck);
        if (deck.isEmpty() || !DeckItem.worksIn(deck, player.level()) || !DeckItem.worksIn(deck, level)
                || !JobPlanning.onDeckNetwork(deck, level, server.getBlockPos()))
            return false;
        Map<ItemResource, Long> products = Map.of(target, available);
        return (job.toPlayer ? moveToInventory(player, record, store, products) : moveToDeck(player, deck, record, store, products)) > 0;
    }

    /** Keep requested items that a running or remaining craft may still need as ingredients. */
    static long targetNeeded(ServerLevel level, CraftingJob job, ItemResource target) {
        long needed = JobRunner.reserved(job).getOrDefault(target, 0L);
        if (job.phase != CraftingJob.Phase.CRAFTING) return needed;
        for (int stepIndex = 0; stepIndex < job.steps.size(); stepIndex++) {
            CraftingJob.Step step = job.steps.get(stepIndex);
            if (step.left <= 0) continue;
            long perCraft = 0;
            if (step.card instanceof ProcessingCard card) {
                perCraft = card.usedInputs().stream().filter(input -> input.item().equals(target)).mapToLong(ProcessingCard.Amount::count).sum();
            } else {
                CardRecipes.Resolved card = job.resolved(level, stepIndex);
                if (card == null) return Long.MAX_VALUE;
                for (int slot = 0; slot < 9; slot++) {
                    if (!card.encoded(slot).isEmpty() && job.accepts(level, stepIndex, card, slot, target)) perCraft++;
                }
            }
            if (perCraft > 0) {
                if (step.left > (Long.MAX_VALUE - needed) / perCraft) return Long.MAX_VALUE;
                needed += step.left * perCraft;
            }
        }
        return needed;
    }

    static long moveToDeck(ServerPlayer player, ItemStack deck, WaferRecord record, WaferStore store, Map<ItemResource, Long> items) {
        var storage = DeckStorage.checked(store, deck, player);
        long moved = 0;
        for (var held : storage.depositAmounts(items).entrySet()) {
            moved += store.extract(record, held.getKey(), held.getValue(), false, player);
        }
        refreshOpenDeck(player, deck);
        return moved;
    }

    /** Puts what the job holds onto the requester's Crafting Deck, if they are online with it. */
    static void deliver(ServerLevel level, CraftingServerBlockEntity server, CraftingJob job, WaferRecord record, WaferStore store) {
        if (record.contents().isEmpty()) {
            finish(level, server, job);
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.requester);
        ItemStack deck = player == null ? ItemStack.EMPTY : findDeck(player, job.deck);
        if (deck.isEmpty()) {
            job.pause = PauseReason.WAITING_PLAYER;
            return;
        }
        if (!DeckItem.worksIn(deck, player.level()) || !DeckItem.worksIn(deck, level)) {
            job.pause = PauseReason.DIMENSION_UPGRADE;
            return;
        }
        if (!JobPlanning.onDeckNetwork(deck, level, server.getBlockPos())) {
            // Results travel over the network: a server cut off from the Deck's network keeps them until it's back.
            job.pause = PauseReason.NO_NETWORK;
            return;
        }
        if (job.toPlayer) {
            moveToInventory(player, record, store);
            if (record.contents().isEmpty()) {
                finish(level, server, job);
            } else {
                job.pause = PauseReason.WAITING_SPACE;
            }
            return;
        }
        if (!DeckStorage.hasPower(deck)) {
            job.pause = PauseReason.DECK_CHARGE;
            return;
        }
        moveToDeck(player, deck, record, store, new LinkedHashMap<>(record.contents()));
        if (record.contents().isEmpty()) {
            finish(level, server, job);
        } else {
            // The Deck's screen shows it: a banner, and the job in its list.
            job.pause = DeckStorage.hasPower(deck) ? PauseReason.WAITING_SPACE : PauseReason.DECK_CHARGE;
        }
    }

    /** The Deck with this identity in the player's inventory. */
    public static ItemStack findDeck(ServerPlayer player, @Nullable UUID deckId) {
        if (deckId == null) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (DeckItem.isDeck(stack) && deckId.equals(stack.get(JasmComponents.DECK_ID.get()))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The job is over: the server is free, and its entry goes once its record is safely written. */
    static void finish(ServerLevel level, CraftingServerBlockEntity server, CraftingJob job) {
        JobRunner.release(level, job);
        server.setJob(null);
        AutocraftState.get(level.getServer()).finishJob(job.id);
    }

    /**
     * Takes a finished job's results into the player's inventory at the server, for when the Deck is lost. The
     * requester may collect. Returns how many items came out.
     */
    public static long collect(ServerPlayer player, CraftingServerBlockEntity server) {
        CraftingJob job = server.job();
        WaferStore store = WaferStore.get(player.level().getServer());
        if (job == null || job.phase != CraftingJob.Phase.RETURNING || !job.requester.equals(player.getUUID())) {
            return 0;
        }
        WaferRecord record = JobRunner.record(store, job);
        if (record == null) {
            return 0;
        }
        return moveToInventory(player, record, store);
    }

    /** Moves what the job holds into the player's inventory, as far as it fits. Returns how many items went in. */
    static long moveToInventory(ServerPlayer player, WaferRecord record, WaferStore store) {
        return moveToInventory(player, record, store, new LinkedHashMap<>(record.contents()));
    }

    static long moveToInventory(ServerPlayer player, WaferRecord record, WaferStore store, Map<ItemResource, Long> items) {
        long moved = 0;
        Inventory inventory = player.getInventory();
        for (Map.Entry<ItemResource, Long> held : items.entrySet()) {
            ItemResource key = held.getKey();
            long left = held.getValue();
            while (left > 0) {
                int size = (int) Math.min(left, key.getMaxStackSize());
                ItemStack stack = key.toStack(size);
                if (!inventory.add(stack)) {
                    // The inventory is full; whatever did go in is taken off the job.
                    int in = size - stack.getCount();
                    if (in > 0) {
                        store.extract(record, key, in, false, player);
                        moved += in;
                    }
                    return moved;
                }
                store.extract(record, key, size, false, player);
                moved += size;
                left -= size;
            }
        }
        return moved;
    }

    /** The server is being broken: its job ends and everything it held spills on the ground. */
    static void spill(ServerLevel level, CraftingServerBlockEntity server) {
        CraftingJob job = server.job();
        WaferStore store = WaferStore.ifOpen(level.getServer());
        if (job == null || store == null) {
            return;
        }
        WaferRecord record = JobRunner.record(store, job);
        if (record != null) {
            BlockPos pos = server.getBlockPos();
            for (Map.Entry<ItemResource, Long> held : List.copyOf(record.contents().entrySet())) {
                long left = held.getValue();
                while (left > 0) {
                    int size = (int) Math.min(left, held.getKey().getMaxStackSize());
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), held.getKey().toStack(size));
                    left -= size;
                }
                store.extract(record, held.getKey(), held.getValue(), false, null);
            }
        }
        finish(level, server, job);
    }

    /** An open Deck screen shows its wafers' contents; tell it they changed. */
    public static void refreshOpenDeck(ServerPlayer player, ItemStack deck) {
        if (player.containerMenu instanceof DeckMenu menu && menu.deck() == deck) {
            DeckViewTracker.markDirty(menu);
        }
    }
}
