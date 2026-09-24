package dev.micolash.jasm.gametest;

import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import dev.micolash.jasm.wafer.WaferValidator.Mode;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Physical-wafer validation against a real store. */
final class ValidatorGameTests {
    private ValidatorGameTests() {}

    static void register() {
        JasmGameTests.add("validator_format_and_activate_restamps", ValidatorGameTests::formatAndActivateRestamps);
        JasmGameTests.add("validator_duplicate_becomes_blank", ValidatorGameTests::duplicateBecomesBlank);
        JasmGameTests.add("validator_recovered_original_dissolves", ValidatorGameTests::recoveredOriginalDissolves);
        JasmGameTests.add("validator_item_ahead_fast_forwards", ValidatorGameTests::itemAheadFastForwards);
        JasmGameTests.add("validator_activating_a_wafer_from_a_later_session", ValidatorGameTests::activatingWaferFromLaterSession);
        JasmGameTests.add("validator_unknown_and_tampered_are_locked", ValidatorGameTests::unknownAndTamperedAreLocked);
        JasmGameTests.add("validator_empty_stack_is_ignored", ValidatorGameTests::emptyStackIsIgnored);
    }

    private static WaferStore store(GameTestHelper helper) {
        return WaferStore.get(helper.getLevel().getServer());
    }

    private static ItemStack blank(WaferTier tier) {
        return new ItemStack(JasmItems.wafer(tier));
    }

    private static Stamp stampOf(ItemStack wafer) {
        return wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp();
    }

    private static void formatAndActivateRestamps(GameTestHelper helper) {
        WaferStore store = store(helper);
        ItemStack wafer = blank(WaferTier.K1);
        helper.assertTrue(WaferValidator.validate(store, wafer, Mode.ACTIVATE, null) == Verdict.UNFORMATTED, "blank is unformatted");
        WaferRecord record = WaferValidator.format(store, wafer, null);
        helper.assertTrue(record.serial() >= 1, "formatted wafer has a serial");
        helper.assertTrue(wafer.get(JasmComponents.WAFER_IDENTITY.get()).serial() == record.serial(), "item carries the serial");
        Stamp before = stampOf(wafer);
        helper.assertTrue(WaferValidator.validate(store, wafer, Mode.ACTIVATE, null) == Verdict.VALID, "formatted wafer is valid");
        Stamp after = stampOf(wafer);
        helper.assertTrue(after.isAfter(before), "activation restamps");
        helper.assertTrue(after.equals(record.current()), "record follows the item");
        helper.assertTrue(WaferValidator.validate(store, wafer, Mode.CHECK, null) == Verdict.VALID, "check keeps it valid");
        helper.assertTrue(stampOf(wafer).equals(after), "check does not restamp");
        helper.succeed();
    }

    private static void duplicateBecomesBlank(GameTestHelper helper) {
        WaferStore store = store(helper);
        ItemStack original = blank(WaferTier.K4);
        WaferRecord record = WaferValidator.format(store, original, null);
        store.insert(record, ItemResource.of(Items.DIAMOND), 10, false, null);
        ItemStack copy = original.copy();
        helper.assertTrue(WaferValidator.validate(store, original, Mode.ACTIVATE, null) == Verdict.VALID, "original activates first");
        helper.assertTrue(WaferValidator.validate(store, copy, Mode.PASSIVE, null) == Verdict.DUPLICATE, "copy is a duplicate");
        helper.assertFalse(copy.has(JasmComponents.WAFER_IDENTITY.get()), "copy is blanked");
        helper.assertTrue(copy.getCount() == 1, "blanked copy remains as a blank wafer");
        helper.assertTrue(record.count(ItemResource.of(Items.DIAMOND)) == 10, "contents untouched");
        helper.assertTrue(WaferValidator.validate(store, original, Mode.CHECK, null) == Verdict.VALID, "original still valid");
        helper.succeed();
    }

    private static void recoveredOriginalDissolves(GameTestHelper helper) {
        WaferStore store = store(helper);
        ItemStack original = blank(WaferTier.BASIC);
        WaferRecord record = WaferValidator.format(store, original, null);
        store.insert(record, ItemResource.of(Items.COBBLESTONE), 64, false, null);
        ItemStack stolenCopy = original.copy();
        Stamp replacementStamp = store.reissue(record, WaferTier.K1.capacity(), null);
        ItemStack replacement = blank(WaferTier.K1);
        replacement.set(JasmComponents.WAFER_IDENTITY.get(), new WaferIdentity(record.id(), record.serial(), replacementStamp));

        helper.assertTrue(WaferValidator.validate(store, replacement, Mode.ACTIVATE, null) == Verdict.VALID, "replacement valid");
        helper.assertTrue(WaferValidator.validate(store, original, Mode.PASSIVE, null) == Verdict.RECOVERED_ORIGINAL,
                "smaller-tier original dissolves after recovery onto a larger wafer");
        helper.assertTrue(original.isEmpty(), "recovered original is removed");
        helper.assertTrue(WaferValidator.validate(store, stolenCopy, Mode.PASSIVE, null) == Verdict.RECOVERED_ORIGINAL,
                "copies of the original dissolve too");
        helper.assertTrue(record.count(ItemResource.of(Items.COBBLESTONE)) == 64, "current contents carried over");
        helper.succeed();
    }

    private static void itemAheadFastForwards(GameTestHelper helper) {
        WaferStore store = store(helper);
        ItemStack wafer = blank(WaferTier.K1);
        WaferRecord record = WaferValidator.format(store, wafer, null);
        Stamp ahead = new Stamp(record.current().epoch(), record.current().counter() + 1_000);
        wafer.set(JasmComponents.WAFER_IDENTITY.get(), wafer.get(JasmComponents.WAFER_IDENTITY.get()).withStamp(ahead));
        helper.assertTrue(WaferValidator.validate(store, wafer, Mode.PASSIVE, null) == Verdict.VALID_AHEAD, "ahead is accepted");
        helper.assertTrue(record.current().equals(ahead), "record fast-forwarded");
        helper.assertTrue(store.rotate(record, null).isAfter(ahead), "later mints stay unique");
        helper.succeed();
    }

    /** The saved data is behind the item (for example restored from an older copy): the next stamp must still be newer. */
    private static void activatingWaferFromLaterSession(GameTestHelper helper) {
        WaferStore store = store(helper);
        ItemStack wafer = blank(WaferTier.K1);
        WaferRecord record = WaferValidator.format(store, wafer, null);
        Stamp later = new Stamp(record.current().epoch() + 5, 3);
        wafer.set(JasmComponents.WAFER_IDENTITY.get(), wafer.get(JasmComponents.WAFER_IDENTITY.get()).withStamp(later));
        helper.assertTrue(WaferValidator.validate(store, wafer, Mode.ACTIVATE, null) == Verdict.VALID_AHEAD, "ahead is accepted");
        Stamp fresh = stampOf(wafer);
        helper.assertTrue(fresh.isAfter(new Stamp(later.epoch(), Long.MAX_VALUE)), "new stamp is past the whole later session");
        helper.assertTrue(record.current().equals(fresh), "record follows the item");
        helper.succeed();
    }

    private static void unknownAndTamperedAreLocked(GameTestHelper helper) {
        WaferStore store = store(helper);
        ItemStack small = blank(WaferTier.BASIC);
        WaferRecord record = WaferValidator.format(store, small, null);
        WaferIdentity real = small.get(JasmComponents.WAFER_IDENTITY.get());

        WaferIdentity[] unknowns = {
                new WaferIdentity(UUID.randomUUID(), real.serial(), real.stamp()), // right slot, different wafer
                new WaferIdentity(real.id(), 0, real.stamp()), // no serial
                new WaferIdentity(UUID.randomUUID(), 900_000_000L, new Stamp(1, 1)), // slot never used
        };
        for (WaferIdentity unknown : unknowns) {
            ItemStack orphan = blank(WaferTier.BASIC);
            orphan.set(JasmComponents.WAFER_IDENTITY.get(), unknown);
            helper.assertTrue(WaferValidator.validate(store, orphan, Mode.ACTIVATE, null) == Verdict.ORPHAN, "unknown: " + unknown);
            helper.assertTrue(unknown.equals(orphan.get(JasmComponents.WAFER_IDENTITY.get())), "identity kept: " + unknown);
        }

        ItemStack forged = blank(WaferTier.K64);
        forged.set(JasmComponents.WAFER_IDENTITY.get(), real);
        helper.assertTrue(WaferValidator.validate(store, forged, Mode.ACTIVATE, null) == Verdict.TAMPERED, "capacity mismatch");
        helper.assertTrue(real.equals(forged.get(JasmComponents.WAFER_IDENTITY.get())), "tampered item keeps its identity");
        helper.assertTrue(store.bySerial(record.serial()).isPresent(), "record kept");
        helper.succeed();
    }

    private static void emptyStackIsIgnored(GameTestHelper helper) {
        WaferStore store = store(helper);
        ItemStack wafer = blank(WaferTier.BASIC);
        WaferValidator.format(store, wafer, null);
        wafer.setCount(0);
        helper.assertTrue(WaferValidator.validate(store, wafer, Mode.PASSIVE, null) == Verdict.UNFORMATTED, "empty stack is ignored");
        helper.succeed();
    }
}
