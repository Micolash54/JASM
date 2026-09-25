package dev.micolash.jasm.gametest;

import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.battery.CreativeBatteryMenu;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** The Creative Battery: charging, what its slot takes, the power it gives, and what happens when it's broken. */
final class BatteryGameTests {
    private static final BlockPos AT = BlockPos.ZERO;

    private BatteryGameTests() {}

    static void register() {
        JasmGameTests.add("battery_fills_each_deck_in_expected_ticks", BatteryGameTests::fillsEachDeck);
        JasmGameTests.add("battery_slot_only_takes_items_that_store_power", BatteryGameTests::slotOnlyTakesChargeableItems);
        JasmGameTests.add("battery_gives_unlimited_power_and_takes_none", BatteryGameTests::givesUnlimitedPower);
        JasmGameTests.add("battery_drops_itself_and_its_item_when_broken", BatteryGameTests::dropsWhenBroken);
        JasmGameTests.add("battery_keeps_its_item_when_saved", BatteryGameTests::keepsItemWhenSaved);
        JasmGameTests.add("battery_hoppers_put_in_only_items_that_store_power", BatteryGameTests::hoppersPutInOnlyChargeableItems);
        JasmGameTests.add("battery_hoppers_take_out_only_full_items", BatteryGameTests::hoppersTakeOutOnlyFullItems);
        JasmGameTests.add("battery_hopper_line_charges_decks", BatteryGameTests::hopperLineChargesDecks);
    }

    private static CreativeBatteryBlockEntity place(GameTestHelper helper) {
        helper.setBlock(AT, JasmBlocks.CREATIVE_BATTERY.get());
        return helper.getBlockEntity(AT, CreativeBatteryBlockEntity.class);
    }

    /** Each call to charge is one tick's worth; an empty Deck is full after ceil(battery / rate) of them. */
    private static void fillsEachDeck(GameTestHelper helper) {
        CreativeBatteryBlockEntity battery = place(helper);
        int rate = JasmConfig.BATTERY_CHARGE_PER_TICK.getAsInt();
        for (DeckTier tier : DeckTier.values()) {
            battery.chargingSlot().setItem(0, new ItemStack(JasmItems.deck(tier)));
            int ticks = 0;
            while (battery.charge(rate) > 0) {
                ticks++;
            }
            int expected = (tier.battery() + rate - 1) / rate;
            helper.assertTrue(ticks == expected, tier + " took " + ticks + " ticks, expected " + expected);
            helper.assertTrue(DeckItem.energy(battery.chargingSlot().getItem(0)) == tier.battery(), tier + " is full");
        }
        battery.chargingSlot().setItem(0, ItemStack.EMPTY);
        helper.assertTrue(battery.charge(rate) == 0, "an empty slot charges nothing");
        helper.succeed();
    }

    private static void slotOnlyTakesChargeableItems(GameTestHelper helper) {
        CreativeBatteryBlockEntity battery = place(helper);
        DeckStorageGameTests.withPlayer(helper, player -> {
            CreativeBatteryMenu menu = new CreativeBatteryMenu(1, player.getInventory(), battery.chargingSlot(),
                    ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(AT)));
            ItemStack deck = new ItemStack(JasmItems.deck(DeckTier.BASIC));
            helper.assertFalse(menu.getSlot(0).mayPlace(new ItemStack(Items.STONE)), "stone is refused");
            helper.assertFalse(menu.getSlot(0).mayPlace(new ItemStack(JasmItems.wafer(WaferTier.K1))), "a wafer is refused");
            helper.assertTrue(menu.getSlot(0).mayPlace(deck), "a Deck is accepted");
            helper.assertFalse(battery.chargingSlot().canPlaceItem(0, new ItemStack(Items.STONE)), "the slot itself refuses stone too");

            // Menu slots: 0 is the charging slot, 1-27 the inventory, 28-36 the hotbar.
            player.getInventory().setItem(0, new ItemStack(Items.STONE, 64));
            menu.quickMoveStack(player, 28);
            helper.assertTrue(battery.chargingSlot().getItem(0).isEmpty(), "shift-clicking stone moves nothing");
            helper.assertTrue(player.getInventory().getItem(0).getCount() == 64, "the stone stays put");

            player.getInventory().setItem(1, deck);
            menu.quickMoveStack(player, 29);
            helper.assertTrue(battery.chargingSlot().getItem(0).is(JasmItems.deck(DeckTier.BASIC)), "shift-clicking a Deck puts it in");
            helper.assertTrue(player.getInventory().getItem(1).isEmpty(), "and takes it from the inventory");

            menu.quickMoveStack(player, 0);
            helper.assertTrue(battery.chargingSlot().getItem(0).isEmpty(), "shift-clicking the slot empties it");
            helper.assertTrue(player.getInventory().getItem(1).is(JasmItems.deck(DeckTier.BASIC)), "the Deck goes to the leftmost free hotbar slot");

            for (int i = 0; i < 9; i++) {
                player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            }
            battery.chargingSlot().setItem(0, new ItemStack(JasmItems.deck(DeckTier.BASIC)));
            menu.quickMoveStack(player, 0);
            helper.assertTrue(player.getInventory().getItem(9).is(JasmItems.deck(DeckTier.BASIC)), "with the hotbar full, it goes top-left in the inventory");
            player.getInventory().clearContent();
        });
        helper.succeed();
    }

    private static void givesUnlimitedPower(GameTestHelper helper) {
        place(helper);
        BlockPos pos = helper.absolutePos(AT);
        for (Direction side : new Direction[] {Direction.UP, Direction.NORTH, null}) {
            EnergyHandler power = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, pos, side);
            helper.assertTrue(power != null, "power from side " + side);
            try (Transaction tx = Transaction.openRoot()) {
                helper.assertTrue(power.extract(Integer.MAX_VALUE, tx) == Integer.MAX_VALUE, "any amount can be pulled");
                helper.assertTrue(power.insert(1_000, tx) == 0, "nothing can be pushed in");
                tx.commit();
            }
        }
        helper.succeed();
    }

    private static void dropsWhenBroken(GameTestHelper helper) {
        CreativeBatteryBlockEntity battery = place(helper);
        ItemStack deck = new ItemStack(JasmItems.deck(DeckTier.ADVANCED));
        deck.set(JasmComponents.ENERGY.get(), 1_234);
        battery.chargingSlot().setItem(0, deck.copy());
        helper.getLevel().destroyBlock(helper.absolutePos(AT), true);

        List<ItemEntity> drops = helper.getEntities(EntityTypes.ITEM, AT, 3.0);
        boolean droppedDeck = drops.stream().anyMatch(e -> ItemStack.isSameItemSameComponents(e.getItem(), deck));
        boolean droppedBattery = drops.stream().anyMatch(e -> e.getItem().is(JasmItems.CREATIVE_BATTERY.get()));
        drops.forEach(ItemEntity::discard);
        helper.assertTrue(droppedDeck, "the Deck being charged drops, unchanged");
        helper.assertTrue(droppedBattery, "the battery drops itself");
        helper.assertTrue(drops.size() == 2, "nothing else drops, got " + drops.size());
        helper.succeed();
    }

    private static ResourceHandler<ItemResource> hopperView(GameTestHelper helper, Direction side) {
        return helper.getLevel().getCapability(Capabilities.Item.BLOCK, helper.absolutePos(AT), side);
    }

    private static void hoppersPutInOnlyChargeableItems(GameTestHelper helper) {
        CreativeBatteryBlockEntity battery = place(helper);
        for (Direction side : Direction.values()) {
            helper.assertTrue(hopperView(helper, side) != null, "reachable from " + side);
        }
        ResourceHandler<ItemResource> slot = hopperView(helper, Direction.UP);
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertTrue(slot.insert(ItemResource.of(Items.STONE), 64, tx) == 0, "stone is refused");
            helper.assertTrue(slot.insert(ItemResource.of(JasmItems.wafer(WaferTier.K1)), 1, tx) == 0, "a wafer is refused");
            helper.assertTrue(slot.insert(ItemResource.of(JasmItems.deck(DeckTier.BASIC)), 5, tx) == 1, "one Deck goes in");
            helper.assertTrue(slot.insert(ItemResource.of(JasmItems.deck(DeckTier.STARTER)), 1, tx) == 0, "a second item waits its turn");
            tx.commit();
        }
        helper.assertTrue(battery.chargingSlot().getItem(0).is(JasmItems.deck(DeckTier.BASIC)), "the Deck is in the slot");
        battery.chargingSlot().setItem(0, ItemStack.EMPTY);
        helper.succeed();
    }

    private static void hoppersTakeOutOnlyFullItems(GameTestHelper helper) {
        CreativeBatteryBlockEntity battery = place(helper);
        DeckTier tier = DeckTier.ULTIMATE;
        ResourceHandler<ItemResource> slot = hopperView(helper, Direction.DOWN);

        for (int charge : new int[] {0, 100_000, tier.battery() - 1}) {
            ItemStack partial = new ItemStack(JasmItems.deck(tier));
            partial.set(JasmComponents.ENERGY.get(), charge);
            battery.chargingSlot().setItem(0, partial);
            try (Transaction tx = Transaction.openRoot()) {
                helper.assertTrue(slot.extract(ItemResource.of(partial), 1, tx) == 0, "a Deck at " + charge + " FE stays in");
                helper.assertTrue(slot.extract(0, ItemResource.of(partial), 1, tx) == 0, "also when asked for the slot directly");
                tx.commit();
            }
        }
        battery.chargingSlot().setItem(0, new ItemStack(JasmItems.deck(tier)));
        while (battery.charge(JasmConfig.BATTERY_CHARGE_PER_TICK.getAsInt()) > 0) {
            // charge to full
        }
        ItemResource full = ItemResource.of(battery.chargingSlot().getItem(0));
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertTrue(slot.extract(full, 1, tx) == 1, "a full Deck comes out");
            tx.commit();
        }
        helper.assertTrue(battery.chargingSlot().getItem(0).isEmpty(), "the slot is empty again");
        helper.succeed();
    }

    /** Empty Decks in a hopper above come out full into a hopper below. */
    private static void hopperLineChargesDecks(GameTestHelper helper) {
        BlockPos above = AT.above();
        BlockPos below = AT.below();
        BlockState underneath = helper.getBlockState(below);
        CreativeBatteryBlockEntity battery = place(helper);
        helper.setBlock(above, Blocks.HOPPER);
        helper.setBlock(below, Blocks.HOPPER);
        HopperBlockEntity feeder = helper.getBlockEntity(above, HopperBlockEntity.class);
        HopperBlockEntity collector = helper.getBlockEntity(below, HopperBlockEntity.class);
        feeder.setItem(0, new ItemStack(JasmItems.deck(DeckTier.ULTIMATE)));
        feeder.setItem(1, new ItemStack(JasmItems.deck(DeckTier.STARTER)));

        helper.succeedWhen(() -> {
            List<ItemStack> collected = new ArrayList<>();
            for (int i = 0; i < collector.getContainerSize(); i++) {
                if (!collector.getItem(i).isEmpty()) {
                    collected.add(collector.getItem(i));
                }
            }
            helper.assertTrue(collected.size() == 2, "both Decks reach the lower hopper");
            for (ItemStack deck : collected) {
                DeckTier tier = ((DeckItem) deck.getItem()).tier();
                helper.assertTrue(DeckItem.energy(deck) == tier.battery(), tier + " arrives full");
            }
            helper.assertTrue(battery.chargingSlot().getItem(0).isEmpty(), "nothing left in the battery");
            collector.clearContent();
            helper.setBlock(above, Blocks.AIR);
            helper.setBlock(below, underneath);
        });
    }

    private static void keepsItemWhenSaved(GameTestHelper helper) {
        CreativeBatteryBlockEntity battery = place(helper);
        ItemStack deck = new ItemStack(JasmItems.deck(DeckTier.STARTER));
        deck.set(JasmComponents.ENERGY.get(), 777);
        battery.chargingSlot().setItem(0, deck.copy());

        CompoundTag saved = battery.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(battery.getBlockPos(), battery.getBlockState(), saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof CreativeBatteryBlockEntity reloaded
                && ItemStack.isSameItemSameComponents(reloaded.chargingSlot().getItem(0), deck), "the Deck and its charge survive a save");
        battery.chargingSlot().setItem(0, ItemStack.EMPTY);
        helper.succeed();
    }
}
