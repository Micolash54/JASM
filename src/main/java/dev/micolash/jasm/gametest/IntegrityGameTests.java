package dev.micolash.jasm.gametest;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Ways a player might try to copy stored items, and the power limits that keep everything bounded. */
final class IntegrityGameTests {
    private IntegrityGameTests() {}

    static void register() {
        JasmGameTests.add("integrity_creative_copy_of_a_wafer_leaves_one_working", IntegrityGameTests::creativeCopyOfAWafer);
        JasmGameTests.add("integrity_copied_deck_loses_to_the_one_opened_first", IntegrityGameTests::copiedDeck);
        JasmGameTests.add("integrity_battery_push_is_limited_per_tick", IntegrityGameTests::batteryPushIsLimited);
    }

    private static ItemResource diamond() {
        return ItemResource.of(Items.DIAMOND);
    }

    /** Middle-clicking a wafer in creative makes a copy; whichever copy is used first keeps the items, the other is wiped. */
    private static void creativeCopyOfAWafer(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            WaferStore store = DeckStorageGameTests.store(helper);
            player.getAbilities().instabuild = true;
            ItemStack deck = DeckStorageGameTests.deck(DeckTier.BASIC, WaferTier.K1);
            player.getInventory().setItem(0, deck);
            DeckStorage.deposit(store, deck, new ItemStack(Items.DIAMOND, 10), player);

            DeckMenu menu = new DeckMenu(1, player.getInventory(), 0);
            player.containerMenu = menu;
            menu.clicked(0, 2, ContainerInput.CLONE, player);
            ItemStack copy = menu.getCarried().copy();
            helper.assertTrue(!copy.isEmpty(), "creative middle-click made a copy");
            menu.setCarried(ItemStack.EMPTY);
            menu.removed(player);

            helper.assertTrue(WaferValidator.validate(store, copy, WaferValidator.Mode.ACTIVATE, player) == Verdict.VALID, "the copy is used first");
            DeckStorage.checkAll(store, deck, player);
            helper.assertTrue(DeckStorage.count(store, deck, diamond()) == 0, "the Deck's older copy no longer reaches the diamonds");
            WaferRecord record = WaferValidator.record(store, copy).orElseThrow();
            helper.assertTrue(record.count(diamond()) == 10, "the diamonds exist exactly once");
            player.containerMenu = player.inventoryMenu;
        });
        helper.succeed();
    }

    /** A copied Deck (for example from /give with the same data): opening the original first wipes the copy's wafers. */
    private static void copiedDeck(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            WaferStore store = DeckStorageGameTests.store(helper);
            ItemStack original = DeckStorageGameTests.deck(DeckTier.ADVANCED, WaferTier.K1, WaferTier.K4);
            DeckStorage.deposit(store, original, new ItemStack(Items.DIAMOND, 64), player);
            DeckStorage.deposit(store, original, new ItemStack(Items.DIAMOND, 64), player);
            ItemStack copy = original.copy();

            DeckStorage.activate(store, original, player);
            DeckStorage.activate(store, copy, player);
            helper.assertTrue(DeckStorage.count(store, original, diamond()) == 128, "the Deck opened first keeps everything");
            helper.assertTrue(DeckStorage.count(store, copy, diamond()) == 0, "the copy reaches nothing");
            DeckStorage.activate(store, original, player);
            helper.assertTrue(DeckStorage.count(store, original, diamond()) == 128, "and the original keeps working afterwards");
        });
        helper.succeed();
    }

    private static void batteryPushIsLimited(GameTestHelper helper) {
        BlockPos above = ArchiveGameTests.AT.above();
        DeckStorageGameTests.withPlayer(helper, player -> ArchiveGameTests.place(helper, ArchiveGameTests.AT, ArchiveTier.ULTIMATE, player, null));
        ArchiveBlockEntity archive = helper.getBlockEntity(ArchiveGameTests.AT, ArchiveBlockEntity.class);
        archive.energy().set(0);
        helper.setBlock(above, JasmBlocks.CREATIVE_BATTERY.get());
        CreativeBatteryBlockEntity battery = helper.getBlockEntity(above, CreativeBatteryBlockEntity.class);
        battery.pushToNeighbours(helper.getLevel());
        int limit = JasmConfig.BATTERY_PUSH_PER_FACE_PER_TICK.getAsInt();
        helper.assertTrue(archive.energy().getAmountAsInt() == Math.min(limit, ArchiveTier.ULTIMATE.energyBuffer()),
                "one tick moves at most the per-side limit, got " + archive.energy().getAmountAsInt());
        helper.setBlock(above, Blocks.AIR);
        helper.succeed();
    }
}
