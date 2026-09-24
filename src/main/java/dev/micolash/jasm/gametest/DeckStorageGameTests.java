package dev.micolash.jasm.gametest;

import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Storing and taking items through a Deck. */
final class DeckStorageGameTests {
    private DeckStorageGameTests() {}

    static void register() {
        JasmGameTests.add("deck_deposit_fills_in_order", DeckStorageGameTests::depositFillsInOrder);
        JasmGameTests.add("deck_withdraw_takes_from_the_last_wafer_first", DeckStorageGameTests::withdrawTakesFromLastWaferFirst);
        JasmGameTests.add("deck_items_are_never_created_or_lost", DeckStorageGameTests::itemsAreNeverCreatedOrLost);
        JasmGameTests.add("deck_no_charge_moves_nothing", DeckStorageGameTests::noChargeMovesNothing);
        JasmGameTests.add("deck_refuses_ineligible_items", DeckStorageGameTests::refusesIneligibleItems);
        JasmGameTests.add("deck_copy_in_the_same_deck_is_wiped", DeckStorageGameTests::copyInTheSameDeckIsWiped);
        JasmGameTests.add("deck_changes_record_the_player", DeckStorageGameTests::changesRecordThePlayer);
    }

    /** A real test player (with a connection, so messages work), removed again afterwards. */
    static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> test) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MinecraftServer server = helper.getLevel().getServer();
        try {
            test.accept(player);
        } finally {
            server.getPlayerList().remove(player);
            Path file = server.getPlayerList().getPlayerIo().getPlayerDir().toPath().resolve(player.getStringUUID() + ".dat");
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(file.resolveSibling(player.getStringUUID() + ".dat_old"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static WaferStore store(GameTestHelper helper) {
        return WaferStore.get(helper.getLevel().getServer());
    }

    /** A charged Deck with blank wafers of the given tiers in its first slots. */
    static ItemStack deck(DeckTier tier, WaferTier... wafers) {
        ItemStack deck = new ItemStack(JasmItems.deck(tier));
        deck.set(JasmComponents.ENERGY.get(), tier.battery());
        for (int i = 0; i < wafers.length; i++) {
            deck.set(JasmComponents.DECK_WAFERS.get(), DeckItem.wafers(deck).with(i, new ItemStack(JasmItems.wafer(wafers[i]))));
        }
        return deck;
    }

    private static long used(WaferStore store, ItemStack deck, int slot) {
        WaferRecord record = DeckStorage.records(store, deck).get(slot);
        return record == null ? 0 : record.used();
    }

    private static void depositFillsInOrder(GameTestHelper helper) {
        withPlayer(helper, player -> {
            WaferStore store = store(helper);
            ItemStack deck = deck(DeckTier.BASIC, WaferTier.BASIC, WaferTier.BASIC, WaferTier.BASIC);
            DeckStorage.activate(store, deck, player);
            helper.assertTrue(DeckStorage.deposit(store, deck, new ItemStack(Items.STONE, 64), player) == 64, "stone stored");
            helper.assertTrue(DeckStorage.deposit(store, deck, new ItemStack(Items.DIRT, 64), player) == 64, "dirt stored");
            helper.assertTrue(used(store, deck, 0) == 128 && used(store, deck, 1) == 0, "both land in the first wafer");

            ItemStack moreStone = new ItemStack(Items.STONE, 64);
            DeckStorage.deposit(store, deck, moreStone, player);
            DeckStorage.deposit(store, deck, new ItemStack(Items.STONE, 64), player);
            helper.assertTrue(used(store, deck, 0) == 256 && used(store, deck, 1) == 0, "first wafer exactly full at 256");
            DeckStorage.deposit(store, deck, new ItemStack(Items.STONE, 64), player);
            helper.assertTrue(used(store, deck, 1) == 64, "the rest spills into the next wafer");
            helper.assertTrue(moreStone.isEmpty(), "the source stack shrinks by what was stored");
        });
        helper.succeed();
    }

    private static void withdrawTakesFromLastWaferFirst(GameTestHelper helper) {
        withPlayer(helper, player -> {
            WaferStore store = store(helper);
            ItemStack deck = deck(DeckTier.BASIC, WaferTier.BASIC, WaferTier.BASIC);
            DeckStorage.activate(store, deck, player);
            for (int i = 0; i < 5; i++) {
                DeckStorage.deposit(store, deck, new ItemStack(Items.IRON_INGOT, 64), player);
            }
            helper.assertTrue(used(store, deck, 0) == 256 && used(store, deck, 1) == 64, "320 ingots over two wafers");
            List<ItemStack> out = DeckStorage.withdraw(store, deck, ItemResource.of(Items.IRON_INGOT), 100, player);
            helper.assertTrue(out.stream().mapToInt(ItemStack::getCount).sum() == 100, "100 taken");
            helper.assertTrue(out.stream().allMatch(s -> s.getCount() <= 64), "in stacks no larger than 64");
            helper.assertTrue(used(store, deck, 1) == 0 && used(store, deck, 0) == 220, "last wafer emptied first");
        });
        helper.succeed();
    }

    private static void itemsAreNeverCreatedOrLost(GameTestHelper helper) {
        withPlayer(helper, player -> {
            WaferStore store = store(helper);
            ItemStack deck = deck(DeckTier.STARTER, WaferTier.BASIC);
            DeckStorage.activate(store, deck, player);
            ItemStack source = new ItemStack(Items.COBBLESTONE, 64);
            long total = 0;
            for (int i = 0; i < 5; i++) {
                source.setCount(64);
                long stored = DeckStorage.deposit(store, deck, source, player);
                helper.assertTrue(stored + source.getCount() == 64, "stored plus left over is what was offered");
                total += stored;
            }
            helper.assertTrue(total == 256, "exactly capacity stored");
            ItemResource key = ItemResource.of(Items.COBBLESTONE);
            List<ItemStack> out = DeckStorage.withdraw(store, deck, key, 10_000, player);
            helper.assertTrue(out.stream().mapToInt(ItemStack::getCount).sum() == 256, "asking for more returns exactly what is stored");
            helper.assertTrue(DeckStorage.count(store, deck, key) == 0, "wafer empty");
        });
        helper.succeed();
    }

    private static void noChargeMovesNothing(GameTestHelper helper) {
        withPlayer(helper, player -> {
            WaferStore store = store(helper);
            ItemStack deck = deck(DeckTier.STARTER, WaferTier.K1);
            DeckStorage.activate(store, deck, player);
            DeckStorage.deposit(store, deck, new ItemStack(Items.SAND, 32), player);
            int before = DeckItem.energy(deck);
            helper.assertTrue(before < DeckTier.STARTER.battery(), "moving items costs charge");

            deck.set(JasmComponents.ENERGY.get(), 0);
            ItemStack sand = new ItemStack(Items.SAND, 32);
            helper.assertTrue(DeckStorage.deposit(store, deck, sand, player) == 0, "no deposit without charge");
            helper.assertTrue(sand.getCount() == 32, "source untouched");
            helper.assertTrue(DeckStorage.withdraw(store, deck, ItemResource.of(Items.SAND), 5, player).isEmpty(), "no withdrawal without charge");
            helper.assertTrue(DeckStorage.count(store, deck, ItemResource.of(Items.SAND)) == 32, "contents untouched");
        });
        helper.succeed();
    }

    private static void refusesIneligibleItems(GameTestHelper helper) {
        withPlayer(helper, player -> {
            WaferStore store = store(helper);
            ItemStack deck = deck(DeckTier.STARTER, WaferTier.K1);
            DeckStorage.activate(store, deck, player);
            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            helper.assertTrue(DeckStorage.deposit(store, deck, shulker, player) == 0, "shulker refused");
            helper.assertTrue(shulker.getCount() == 1, "and left where it was");
            ItemStack otherDeck = new ItemStack(JasmItems.deck(DeckTier.STARTER));
            helper.assertTrue(DeckStorage.deposit(store, deck, otherDeck, player) == 0, "Decks refused");
        });
        helper.succeed();
    }

    /** Two copies of one wafer in the same Deck: opening it keeps one and wipes the other; nothing is counted twice. */
    private static void copyInTheSameDeckIsWiped(GameTestHelper helper) {
        withPlayer(helper, player -> {
            WaferStore store = store(helper);
            ItemStack wafer = new ItemStack(JasmItems.wafer(WaferTier.K1));
            WaferRecord record = WaferValidator.format(store, wafer, player);
            store.insert(record, ItemResource.of(Items.GOLD_INGOT), 10, false, player);
            ItemStack deck = deck(DeckTier.BASIC);
            deck.set(JasmComponents.DECK_WAFERS.get(), DeckItem.wafers(deck).with(0, wafer).with(1, wafer.copy()));

            helper.assertTrue(DeckStorage.count(store, deck, ItemResource.of(Items.GOLD_INGOT)) == 10, "never counted twice");
            List<Verdict> verdicts = DeckStorage.activate(store, deck, player);
            helper.assertTrue(verdicts.get(0) == Verdict.VALID && verdicts.get(1) == Verdict.DUPLICATE, "second copy is a duplicate");
            helper.assertFalse(DeckItem.wafers(deck).get(1).has(JasmComponents.WAFER_IDENTITY.get()), "and wiped blank in the Deck");
            helper.assertTrue(DeckStorage.count(store, deck, ItemResource.of(Items.GOLD_INGOT)) == 10, "contents untouched");
        });
        helper.succeed();
    }

    private static void changesRecordThePlayer(GameTestHelper helper) {
        withPlayer(helper, player -> {
            WaferStore store = store(helper);
            ItemStack deck = deck(DeckTier.STARTER, WaferTier.K1);
            DeckStorage.activate(store, deck, player);
            DeckStorage.deposit(store, deck, new ItemStack(Items.APPLE, 5), player);
            WaferRecord record = DeckStorage.records(store, deck).get(0);
            helper.assertTrue(record.changedBy().contains(player.getUUID()), "the depositing player is recorded for save order");
            helper.assertTrue(record.history().createdBy().equals(player.getPlainTextName()), "and as creator of the new wafer");
        });
        helper.succeed();
    }
}
