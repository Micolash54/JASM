package dev.micolash.jasm.gametest;

import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.ledger.JasmLedger;
import dev.micolash.jasm.ledger.WaferRecord;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import dev.micolash.jasm.wafer.WaferValidator.Mode;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Physical-wafer validation against a real ledger. */
final class ValidatorGameTests {
    private ValidatorGameTests() {}

    static void register() {
        JasmGameTests.add("validator_format_and_activate_restamps", ValidatorGameTests::formatAndActivateRestamps);
        JasmGameTests.add("validator_duplicate_becomes_blank", ValidatorGameTests::duplicateBecomesBlank);
        JasmGameTests.add("validator_recovered_original_dissolves", ValidatorGameTests::recoveredOriginalDissolves);
        JasmGameTests.add("validator_item_ahead_fast_forwards", ValidatorGameTests::itemAheadFastForwards);
        JasmGameTests.add("validator_orphan_and_tampered_are_blanked", ValidatorGameTests::orphanAndTamperedAreBlanked);
    }

    private static JasmLedger ledger(GameTestHelper helper) {
        return JasmLedger.get(helper.getLevel().getServer());
    }

    private static ItemStack blank(WaferTier tier) {
        return new ItemStack(JasmItems.wafer(tier));
    }

    private static void formatAndActivateRestamps(GameTestHelper helper) {
        JasmLedger ledger = ledger(helper);
        ItemStack wafer = blank(WaferTier.K1);
        helper.assertTrue(WaferValidator.validate(ledger, wafer, Mode.ACTIVATE, null) == Verdict.UNFORMATTED, "blank is unformatted");
        WaferRecord record = WaferValidator.format(ledger, wafer);
        Stamp before = wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp();
        helper.assertTrue(WaferValidator.validate(ledger, wafer, Mode.ACTIVATE, null) == Verdict.VALID, "formatted wafer is valid");
        Stamp after = wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp();
        helper.assertTrue(after.isAfter(before), "activation restamps");
        helper.assertTrue(after.equals(record.current()), "record follows the item");
        helper.assertTrue(WaferValidator.validate(ledger, wafer, Mode.CHECK, null) == Verdict.VALID, "check keeps it valid");
        helper.assertTrue(wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp().equals(after), "check does not restamp");
        helper.succeed();
    }

    private static void duplicateBecomesBlank(GameTestHelper helper) {
        JasmLedger ledger = ledger(helper);
        ItemStack original = blank(WaferTier.K4);
        WaferRecord record = WaferValidator.format(ledger, original);
        ledger.insert(record, ItemResource.of(Items.DIAMOND), 10, false);
        ItemStack copy = original.copy();
        helper.assertTrue(WaferValidator.validate(ledger, original, Mode.ACTIVATE, null) == Verdict.VALID, "original activates first");
        helper.assertTrue(WaferValidator.validate(ledger, copy, Mode.PASSIVE, null) == Verdict.DUPLICATE, "copy is a duplicate");
        helper.assertFalse(copy.has(JasmComponents.WAFER_IDENTITY.get()), "copy is blanked");
        helper.assertTrue(copy.getCount() == 1, "blanked copy remains as a blank wafer");
        helper.assertTrue(record.count(ItemResource.of(Items.DIAMOND)) == 10, "contents untouched");
        helper.assertTrue(WaferValidator.validate(ledger, original, Mode.CHECK, null) == Verdict.VALID, "original still valid");
        helper.succeed();
    }

    private static void recoveredOriginalDissolves(GameTestHelper helper) {
        JasmLedger ledger = ledger(helper);
        ItemStack original = blank(WaferTier.BASIC);
        WaferRecord record = WaferValidator.format(ledger, original);
        ledger.insert(record, ItemResource.of(Items.COBBLESTONE), 64, false);
        ItemStack stolenCopy = original.copy();
        Stamp replacementStamp = ledger.reissue(record, WaferTier.K1.capacity());
        ItemStack replacement = blank(WaferTier.K1);
        replacement.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(record.id(), replacementStamp));

        helper.assertTrue(WaferValidator.validate(ledger, replacement, Mode.ACTIVATE, null) == Verdict.VALID, "replacement valid");
        helper.assertTrue(WaferValidator.validate(ledger, original, Mode.PASSIVE, null) == Verdict.RECOVERED_ORIGINAL,
                "smaller-tier original dissolves after recovery onto a larger wafer");
        helper.assertTrue(original.isEmpty(), "recovered original is removed");
        helper.assertTrue(WaferValidator.validate(ledger, stolenCopy, Mode.PASSIVE, null) == Verdict.RECOVERED_ORIGINAL,
                "copies of the original dissolve too");
        helper.assertTrue(record.count(ItemResource.of(Items.COBBLESTONE)) == 64, "current contents carried over");
        helper.succeed();
    }

    private static void itemAheadFastForwards(GameTestHelper helper) {
        JasmLedger ledger = ledger(helper);
        ItemStack wafer = blank(WaferTier.K1);
        WaferRecord record = WaferValidator.format(ledger, wafer);
        Stamp ahead = new Stamp(record.current().epoch(), record.current().counter() + 1_000);
        wafer.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(record.id(), ahead));
        helper.assertTrue(WaferValidator.validate(ledger, wafer, Mode.PASSIVE, null) == Verdict.VALID_AHEAD, "ahead is accepted");
        helper.assertTrue(record.current().equals(ahead), "ledger fast-forwarded");
        helper.assertTrue(ledger.rotate(record).isAfter(ahead), "later mints stay unique");
        helper.succeed();
    }

    private static void orphanAndTamperedAreBlanked(GameTestHelper helper) {
        JasmLedger ledger = ledger(helper);
        ItemStack orphan = blank(WaferTier.K1);
        orphan.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(UUID.randomUUID(), new Stamp(1, 1)));
        helper.assertTrue(WaferValidator.validate(ledger, orphan, Mode.PASSIVE, null) == Verdict.ORPHAN, "unknown id is orphan");
        helper.assertFalse(orphan.has(JasmComponents.WAFER_IDENTITY.get()), "orphan blanked");

        ItemStack small = blank(WaferTier.BASIC);
        WaferRecord record = WaferValidator.format(ledger, small);
        ItemStack forged = blank(WaferTier.K64);
        forged.set(JasmComponents.WAFER_IDENTITY.get(), small.get(JasmComponents.WAFER_IDENTITY.get()));
        helper.assertTrue(WaferValidator.validate(ledger, forged, Mode.PASSIVE, null) == Verdict.TAMPERED, "capacity mismatch");
        helper.assertFalse(forged.has(JasmComponents.WAFER_IDENTITY.get()), "tampered item blanked");
        helper.assertTrue(ledger.wafer(record.id()).isPresent(), "record kept for admin restore");
        helper.succeed();
    }
}
