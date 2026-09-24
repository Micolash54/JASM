package dev.micolash.jasm.gametest;

import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** The Deck menu against a real test player. Slot indexes: wafer slots first, then inventory rows, then hotbar. */
final class DeckMenuGameTests {
    private DeckMenuGameTests() {}

    static void register() {
        JasmGameTests.add("deck_menu_deck_slot_is_locked", DeckMenuGameTests::deckSlotIsLocked);
        JasmGameTests.add("deck_menu_offhand_deck_cannot_be_swapped", DeckMenuGameTests::offhandDeckCannotBeSwapped);
        JasmGameTests.add("deck_menu_closes_when_the_deck_leaves", DeckMenuGameTests::closesWhenTheDeckLeaves);
        JasmGameTests.add("deck_menu_shift_click_stores_items", DeckMenuGameTests::shiftClickStoresItems);
        JasmGameTests.add("deck_menu_inserted_wafer_is_activated", DeckMenuGameTests::insertedWaferIsActivated);
        JasmGameTests.add("deck_menu_taking_a_wafer_out", DeckMenuGameTests::takingAWaferOut);
    }

    /** Menu slot index of hotbar slot {@code hotbar} in a menu with {@code waferSlots} wafer slots. */
    private static int hotbarIndex(DeckMenu menu, int hotbar) {
        return menu.waferSlots() + 27 + hotbar;
    }

    private static DeckMenu open(ServerPlayer player, int deckSlot) {
        DeckMenu menu = new DeckMenu(1, player.getInventory(), deckSlot);
        player.containerMenu = menu;
        return menu;
    }

    private static void deckSlotIsLocked(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ItemStack deck = DeckStorageGameTests.deck(DeckTier.BASIC, WaferTier.K1);
            player.getInventory().setItem(0, deck);
            DeckMenu menu = open(player, 0);
            int deckIndex = hotbarIndex(menu, 0);
            for (ContainerInput input : new ContainerInput[] {ContainerInput.PICKUP, ContainerInput.QUICK_MOVE, ContainerInput.THROW,
                    ContainerInput.PICKUP_ALL}) {
                menu.clicked(deckIndex, 0, input, player);
                helper.assertTrue(player.getInventory().getItem(0) == deck, "Deck stays after " + input);
                helper.assertTrue(menu.getCarried().isEmpty(), "nothing picked up after " + input);
            }
            menu.clicked(hotbarIndex(menu, 5), 0, ContainerInput.SWAP, player);
            helper.assertTrue(player.getInventory().getItem(0) == deck, "number key on another slot does not pull the Deck");
            player.getInventory().setItem(5, new ItemStack(Items.STONE));
            menu.clicked(hotbarIndex(menu, 5), 0, ContainerInput.SWAP, player);
            helper.assertTrue(player.getInventory().getItem(0) == deck, "swapping another slot with the Deck's key is refused");
            helper.assertTrue(menu.stillValid(player), "menu still open");
        });
        helper.succeed();
    }

    private static void offhandDeckCannotBeSwapped(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ItemStack deck = DeckStorageGameTests.deck(DeckTier.STARTER);
            player.getInventory().setItem(Inventory.SLOT_OFFHAND, deck);
            player.getInventory().setItem(3, new ItemStack(Items.DIRT));
            DeckMenu menu = open(player, Inventory.SLOT_OFFHAND);
            menu.clicked(hotbarIndex(menu, 3), Inventory.SLOT_OFFHAND, ContainerInput.SWAP, player);
            helper.assertTrue(player.getInventory().getItem(Inventory.SLOT_OFFHAND) == deck, "off-hand key cannot swap the Deck out");
            helper.assertTrue(player.getInventory().getItem(3).is(Items.DIRT), "the other item stays too");
        });
        helper.succeed();
    }

    private static void closesWhenTheDeckLeaves(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ItemStack deck = DeckStorageGameTests.deck(DeckTier.STARTER);
            player.getInventory().setItem(2, deck);
            DeckMenu menu = open(player, 2);
            helper.assertTrue(menu.stillValid(player), "open while the Deck is there");
            player.getInventory().setItem(2, deck.copy());
            helper.assertFalse(menu.stillValid(player), "a copy in the slot is not the same Deck");
            player.getInventory().setItem(2, ItemStack.EMPTY);
            helper.assertFalse(menu.stillValid(player), "closes when the Deck is gone");
        });
        helper.succeed();
    }

    private static void shiftClickStoresItems(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            WaferStore store = DeckStorageGameTests.store(helper);
            ItemStack deck = DeckStorageGameTests.deck(DeckTier.STARTER, WaferTier.K1);
            player.getInventory().setItem(0, deck);
            player.getInventory().setItem(10, new ItemStack(Items.OAK_LOG, 40));
            DeckMenu menu = open(player, 0);
            DeckStorage.activate(store, deck, player);
            menu.wafers().reload();
            int logIndex = menu.waferSlots() + 1; // inventory slot 10 is the second slot of the first inventory row
            menu.clicked(logIndex, 0, ContainerInput.QUICK_MOVE, player);
            helper.assertTrue(player.getInventory().getItem(10).isEmpty(), "logs left the inventory");
            helper.assertTrue(DeckStorage.count(store, deck, ItemResource.of(Items.OAK_LOG)) == 40, "and are on the wafer");
        });
        helper.succeed();
    }

    private static void insertedWaferIsActivated(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            WaferStore store = DeckStorageGameTests.store(helper);
            ItemStack deck = DeckStorageGameTests.deck(DeckTier.BASIC);
            ItemStack wafer = new ItemStack(JasmItems.wafer(WaferTier.K4));
            WaferValidator.format(store, wafer, player);
            ItemStack oldCopy = wafer.copy();
            Stamp before = wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp();
            player.getInventory().setItem(0, deck);
            player.getInventory().setItem(9, wafer);
            DeckMenu menu = open(player, 0);
            menu.clicked(menu.waferSlots(), 0, ContainerInput.QUICK_MOVE, player);
            ItemStack inDeck = DeckItem.wafers(deck).get(0);
            helper.assertTrue(player.getInventory().getItem(9).isEmpty(), "wafer left the inventory");
            helper.assertTrue(inDeck.get(JasmComponents.WAFER_IDENTITY.get()).stamp().isAfter(before), "and got a fresh stamp in the Deck");
            helper.assertTrue(WaferValidator.validate(store, oldCopy, WaferValidator.Mode.PASSIVE, null)
                    == Verdict.DUPLICATE, "so an older copy stops working");
        });
        helper.succeed();
    }

    private static void takingAWaferOut(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ItemStack deck = DeckStorageGameTests.deck(DeckTier.BASIC, WaferTier.K1, WaferTier.K16);
            player.getInventory().setItem(0, deck);
            DeckMenu menu = open(player, 0);
            menu.clicked(1, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(menu.getCarried().is(JasmItems.wafer(WaferTier.K16)), "picked up the second wafer");
            menu.broadcastChanges();
            helper.assertTrue(DeckItem.wafers(deck).get(1).isEmpty(), "the Deck no longer holds it");
            helper.assertTrue(DeckItem.wafers(deck).count() == 1, "the first wafer stays");
            menu.clicked(1, 0, ContainerInput.PICKUP, player);
            menu.broadcastChanges();
            helper.assertTrue(DeckItem.wafers(deck).get(1).is(JasmItems.wafer(WaferTier.K16)) && menu.getCarried().isEmpty(), "and back in");
            ItemStack stone = new ItemStack(Items.STONE);
            menu.setCarried(stone);
            menu.clicked(2, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(DeckItem.wafers(deck).get(2).isEmpty() && menu.getCarried() == stone, "wafer slots refuse other items");
        });
        helper.succeed();
    }
}
