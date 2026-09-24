package dev.micolash.jasm.gametest;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckNetwork;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.deck.DeckPayloads.ExtractMode;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.deck.DeckView;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** The server side of the Deck screen's messages. Nothing the client sends is trusted. */
final class DeckNetworkGameTests {
    private static final int CONTAINER = 7;

    private DeckNetworkGameTests() {}

    private static ItemResource stone() {
        return ItemResource.of(Items.STONE);
    }

    static void register() {
        JasmGameTests.add("deck_net_take_to_cursor", DeckNetworkGameTests::takeToCursor);
        JasmGameTests.add("deck_net_take_to_inventory", DeckNetworkGameTests::takeToInventory);
        JasmGameTests.add("deck_net_store_from_cursor", DeckNetworkGameTests::storeFromCursor);
        JasmGameTests.add("deck_net_wrong_or_closed_menu_is_ignored", DeckNetworkGameTests::wrongOrClosedMenuIsIgnored);
        JasmGameTests.add("deck_net_too_many_requests_are_dropped", DeckNetworkGameTests::tooManyRequestsAreDropped);
        JasmGameTests.add("deck_net_view_applies_updates", DeckNetworkGameTests::viewAppliesUpdates);
    }

    /** A player with a Deck (one 4K wafer holding {@code stone} stone) open in hotbar slot 0. */
    private static void withOpenDeck(GameTestHelper helper, int stone, BiConsumer<ServerPlayer, DeckMenu> test) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            WaferStore store = DeckStorageGameTests.store(helper);
            ItemStack deck = DeckStorageGameTests.deck(DeckTier.STARTER, WaferTier.K4);
            player.getInventory().setItem(0, deck);
            DeckStorage.activate(store, deck, player);
            while (stone > 0 && DeckStorage.count(store, deck, stone()) < stone) {
                long left = stone - DeckStorage.count(store, deck, stone());
                DeckStorage.deposit(store, deck, new ItemStack(Items.STONE, (int) Math.min(64, left)), player);
            }
            DeckMenu menu = new DeckMenu(CONTAINER, player.getInventory(), 0);
            player.containerMenu = menu;
            test.accept(player, menu);
        });
    }

    private static long stored(GameTestHelper helper, DeckMenu menu) {
        return DeckStorage.count(DeckStorageGameTests.store(helper), menu.deck(), stone());
    }

    private static void takeToCursor(GameTestHelper helper) {
        withOpenDeck(helper, 100, (player, menu) -> {
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER, stone(), ExtractMode.STACK)) == 64, "a stack");
            helper.assertTrue(menu.getCarried().is(Items.STONE) && menu.getCarried().getCount() == 64, "onto the cursor");
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER, stone(), ExtractMode.STACK)) == 0,
                    "a full cursor takes no more");
            menu.setCarried(ItemStack.EMPTY);
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER, stone(), ExtractMode.HALF)) == 18,
                    "right-click takes half of the 36 left");
            helper.assertTrue(stored(helper, menu) == 18, "stored count follows");
            menu.setCarried(new ItemStack(Items.DIRT));
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER, stone(), ExtractMode.STACK)) == 0,
                    "a cursor holding something else takes nothing");
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER, ItemResource.of(Items.DIAMOND), ExtractMode.TO_INVENTORY)) == 0,
                    "asking for something not stored moves nothing");
        });
        helper.succeed();
    }

    private static void takeToInventory(GameTestHelper helper) {
        withOpenDeck(helper, 100, (player, menu) -> {
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER, stone(), ExtractMode.TO_INVENTORY)) == 64, "a stack");
            int inInventory = player.getInventory().countItem(Items.STONE);
            helper.assertTrue(inInventory == 64, "into the inventory");
            helper.assertTrue(stored(helper, menu) + inInventory == 100, "nothing created or lost");
        });
        helper.succeed();
    }

    private static void storeFromCursor(GameTestHelper helper) {
        withOpenDeck(helper, 0, (player, menu) -> {
            menu.setCarried(new ItemStack(Items.STONE, 30));
            helper.assertTrue(DeckNetwork.insert(player, new DeckPayloads.Insert(CONTAINER, true)) == 1, "right-click stores one");
            helper.assertTrue(menu.getCarried().getCount() == 29, "cursor keeps the rest");
            helper.assertTrue(DeckNetwork.insert(player, new DeckPayloads.Insert(CONTAINER, false)) == 29, "left-click stores all");
            helper.assertTrue(menu.getCarried().isEmpty() && stored(helper, menu) == 30, "all 30 stored");
            menu.setCarried(new ItemStack(Items.SHULKER_BOX));
            helper.assertTrue(DeckNetwork.insert(player, new DeckPayloads.Insert(CONTAINER, false)) == 0, "refused items stay on the cursor");
            helper.assertTrue(menu.getCarried().is(Items.SHULKER_BOX), "unchanged");
        });
        helper.succeed();
    }

    private static void wrongOrClosedMenuIsIgnored(GameTestHelper helper) {
        withOpenDeck(helper, 50, (player, menu) -> {
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER + 1, stone(), ExtractMode.STACK)) == 0, "wrong menu id");
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER, stone(), ExtractMode.STACK)) == 0, "menu closed");
            player.containerMenu = menu;
            player.getInventory().setItem(0, ItemStack.EMPTY);
            helper.assertTrue(DeckNetwork.extract(player, new DeckPayloads.Extract(CONTAINER, stone(), ExtractMode.STACK)) == 0, "Deck gone");
            helper.assertTrue(stored(helper, menu) == 50, "nothing moved");
        });
        helper.succeed();
    }

    private static void tooManyRequestsAreDropped(GameTestHelper helper) {
        withOpenDeck(helper, 0, (player, menu) -> {
            int limit = JasmConfig.DECK_MAX_OPS_PER_TICK.getAsInt();
            menu.setCarried(new ItemStack(Items.STONE, 64));
            long stored = 0;
            for (int i = 0; i < limit + 5; i++) {
                stored += DeckNetwork.insert(player, new DeckPayloads.Insert(CONTAINER, true));
            }
            helper.assertTrue(stored == limit, "only " + limit + " requests handled in one tick, got " + stored);
            helper.assertTrue(menu.getCarried().getCount() == 64 - limit, "the rest stays on the cursor");
        });
        helper.succeed();
    }

    private static void viewAppliesUpdates(GameTestHelper helper) {
        DeckView view = new DeckView();
        ItemResource dirt = ItemResource.of(Items.DIRT);
        view.applySnapshotPage(0, List.of(new DeckPayloads.Entry(stone(), 10), new DeckPayloads.Entry(dirt, 5)));
        view.apply(List.of(new DeckPayloads.Entry(stone(), 12), new DeckPayloads.Entry(dirt, 0)));
        helper.assertTrue(view.contents().get(stone()) == 12 && !view.contents().containsKey(dirt), "deltas update and remove");
        view.applySnapshotPage(0, List.of(new DeckPayloads.Entry(dirt, 1)));
        helper.assertTrue(view.contents().size() == 1 && view.contents().get(dirt) == 1, "a new snapshot starts over");
        helper.succeed();
    }
}
