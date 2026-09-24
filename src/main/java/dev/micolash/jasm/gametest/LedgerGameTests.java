package dev.micolash.jasm.gametest;

import dev.micolash.jasm.ledger.JasmLedger;
import dev.micolash.jasm.ledger.WaferRecord;
import dev.micolash.jasm.wafer.WaferTier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Ledger storage and codec behaviour against the real registries. */
final class LedgerGameTests {
    private LedgerGameTests() {}

    static void register() {
        JasmGameTests.add("ledger_variants_stay_distinct", LedgerGameTests::variantsStayDistinct);
        JasmGameTests.add("ledger_codec_keeps_undecodable_entries", LedgerGameTests::codecKeepsUndecodableEntries);
        JasmGameTests.add("ledger_duplicate_record_ids_are_retained", LedgerGameTests::duplicateRecordIdsAreRetained);
    }

    private static void variantsStayDistinct(GameTestHelper helper) {
        JasmLedger ledger = JasmLedger.get(helper.getLevel().getServer());
        WaferRecord record = ledger.createWafer(WaferTier.K1.capacity());
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        ItemStack damaged = new ItemStack(Items.DIAMOND_SWORD);
        damaged.setDamageValue(100);
        ItemResource plainKey = ItemResource.of(Items.DIAMOND_SWORD);
        ItemResource namedKey = ItemResource.of(named);
        ItemResource damagedKey = ItemResource.of(damaged);
        ledger.insert(record, plainKey, 1, false);
        ledger.insert(record, namedKey, 1, false);
        ledger.insert(record, damagedKey, 1, false);
        helper.assertTrue(record.contents().size() == 3, "three distinct variants");
        helper.assertTrue(ledger.extract(record, namedKey, 5, false) == 1, "extract only the named one");
        helper.assertTrue(record.count(plainKey) == 1 && record.count(damagedKey) == 1, "others untouched");
        helper.assertTrue(damagedKey.toStack().getDamageValue() == 100, "damage preserved");
        helper.succeed();
    }

    private static void codecKeepsUndecodableEntries(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        JasmLedger source = new JasmLedger();
        WaferRecord record = source.createWafer(WaferTier.K1.capacity());
        source.insert(record, ItemResource.of(Items.DIAMOND), 5, false);

        CompoundTag encoded = (CompoundTag) JasmLedger.CODEC.encodeStart(ops, source).getOrThrow();
        ListTag contents = encoded.getListOrEmpty("wafers").getCompoundOrEmpty(0).getListOrEmpty("contents");
        CompoundTag bogus = contents.getCompoundOrEmpty(0).copy();
        bogus.getCompoundOrEmpty("item").putString("id", "notamod:vanished_thing");
        bogus.putLong("count", 7);
        contents.add(bogus);

        JasmLedger decoded = JasmLedger.CODEC.parse(ops, encoded).getOrThrow();
        WaferRecord loaded = decoded.wafer(record.id()).orElseThrow();
        helper.assertTrue(loaded.count(ItemResource.of(Items.DIAMOND)) == 5, "known entry decoded");
        helper.assertTrue(loaded.quarantinedCount() == 7, "unknown entry quarantined");
        helper.assertTrue(loaded.used() == 12, "quarantined items still use capacity");
        helper.assertTrue(decoded.insert(loaded, ItemResource.of(Items.STONE), 2_000, false) == 1_024 - 12, "capacity respected");

        Tag reencoded = JasmLedger.CODEC.encodeStart(ops, decoded).getOrThrow();
        helper.assertTrue(reencoded.toString().contains("notamod:vanished_thing"), "unknown entry written back verbatim");
        helper.succeed();
    }

    private static void duplicateRecordIdsAreRetained(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        JasmLedger source = new JasmLedger();
        WaferRecord record = source.createWafer(WaferTier.K1.capacity());
        source.insert(record, ItemResource.of(Items.DIAMOND), 3, false);
        CompoundTag encoded = (CompoundTag) JasmLedger.CODEC.encodeStart(ops, source).getOrThrow();
        ListTag wafers = encoded.getListOrEmpty("wafers");
        CompoundTag duplicate = wafers.getCompoundOrEmpty(0).copy();
        duplicate.getListOrEmpty("contents").getCompoundOrEmpty(0).putLong("count", 9);
        wafers.add(duplicate);

        JasmLedger decoded = JasmLedger.CODEC.parse(ops, encoded).getOrThrow();
        helper.assertTrue(decoded.wafer(record.id()).orElseThrow().count(ItemResource.of(Items.DIAMOND)) == 3, "first record kept");
        helper.assertTrue(decoded.undecodableRecordCount() == 1, "duplicate retained, not dropped");
        Tag reencoded = JasmLedger.CODEC.encodeStart(ops, decoded).getOrThrow();
        helper.assertTrue(((CompoundTag) reencoded).getListOrEmpty("wafers").size() == 2, "duplicate written back");
        helper.succeed();
    }
}
