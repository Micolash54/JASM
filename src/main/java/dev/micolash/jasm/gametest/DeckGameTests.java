package dev.micolash.jasm.gametest;

import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.deck.DeckWafers;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** The Deck item: battery, stacking, and the wafers it carries. */
final class DeckGameTests {
    private DeckGameTests() {}

    static void register() {
        JasmGameTests.add("deck_battery_charges_but_never_drains", DeckGameTests::batteryChargesButNeverDrains);
        JasmGameTests.add("deck_never_stacks", DeckGameTests::neverStacks);
        JasmGameTests.add("deck_wafers_are_copies", DeckGameTests::wafersAreCopies);
    }

    private static void batteryChargesButNeverDrains(GameTestHelper helper) {
        for (DeckTier tier : DeckTier.values()) {
            SimpleContainer container = new SimpleContainer(1);
            container.setItem(0, new ItemStack(JasmItems.deck(tier)));
            ItemAccess access = ItemAccess.forHandlerIndex(VanillaContainerWrapper.of(container), 0);
            EnergyHandler battery = access.getCapability(Capabilities.Energy.ITEM);
            helper.assertTrue(battery != null, tier + " has a battery");
            try (Transaction tx = Transaction.openRoot()) {
                helper.assertTrue(battery.insert(Integer.MAX_VALUE, tx) == tier.battery(), tier + " fills to capacity");
                tx.commit();
            }
            helper.assertTrue(DeckItem.energy(container.getItem(0)) == tier.battery(), tier + " charge stored on the Deck");
            try (Transaction tx = Transaction.openRoot()) {
                helper.assertTrue(access.getCapability(Capabilities.Energy.ITEM).extract(1_000, tx) == 0, tier + " cannot be drained");
                tx.commit();
            }
            helper.assertTrue(DeckItem.energy(container.getItem(0)) == tier.battery(), tier + " still full");
        }
        helper.succeed();
    }

    private static void neverStacks(GameTestHelper helper) {
        for (DeckTier tier : DeckTier.values()) {
            ItemStack deck = new ItemStack(JasmItems.deck(tier));
            helper.assertTrue(deck.getMaxStackSize() == 1, tier + " does not stack");
            helper.assertFalse(deck.getItem().canFitInsideContainerItems(), tier + " cannot go in containers");
        }
        helper.succeed();
    }

    /** Wafers read from a Deck are fresh copies: changing one never changes the Deck. */
    private static void wafersAreCopies(GameTestHelper helper) {
        WaferStore store = WaferStore.get(helper.getLevel().getServer());
        ItemStack wafer = new ItemStack(JasmItems.wafer(WaferTier.K1));
        WaferValidator.format(store, wafer, null);
        ItemStack deck = new ItemStack(JasmItems.deck(DeckTier.BASIC));
        deck.set(JasmComponents.DECK_WAFERS.get(), DeckItem.wafers(deck).with(2, wafer));

        ItemStack read = DeckItem.wafers(deck).get(2);
        helper.assertTrue(ItemStack.isSameItemSameComponents(read, wafer), "slot 2 holds the wafer");
        read.remove(JasmComponents.WAFER_IDENTITY.get());
        helper.assertTrue(DeckItem.wafers(deck).get(2).has(JasmComponents.WAFER_IDENTITY.get()), "changing a read copy leaves the Deck alone");
        helper.assertTrue(DeckItem.wafers(deck).get(0).isEmpty(), "other slots empty");

        List<ItemStack> seen = new ArrayList<>();
        ((DeckItem) deck.getItem()).forEachWafer(deck, seen::add);
        helper.assertTrue(seen.size() == 1, "one wafer carried");
        DeckWafers emptied = DeckItem.wafers(deck).with(2, ItemStack.EMPTY);
        helper.assertTrue(emptied.count() == 0 && DeckItem.wafers(deck).count() == 1, "removing builds a new value");
        helper.succeed();
    }
}
