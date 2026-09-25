package dev.micolash.jasm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.gametest.JasmGameTests;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import dev.micolash.jasm.wafer.WaferValidator.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Wafer records, their codecs, and the order in which they are saved, against a real server. */
public final class StorageGameTests {
    private StorageGameTests() {}

    public static void register() {
        JasmGameTests.add("storage_variants_stay_distinct", StorageGameTests::variantsStayDistinct);
        JasmGameTests.add("storage_keeps_items_from_missing_mods", StorageGameTests::keepsItemsFromMissingMods);
        JasmGameTests.add("storage_unsaveable_entry_becomes_placeholder", StorageGameTests::unsaveableEntryBecomesPlaceholder);
        JasmGameTests.add("storage_record_survives_reload", StorageGameTests::recordSurvivesReload);
        JasmGameTests.add("storage_serial_skips_used_slots", StorageGameTests::serialSkipsUsedSlots);
        JasmGameTests.add("storage_unreadable_record_locks_wafer", StorageGameTests::unreadableRecordLocksWafer);
        JasmGameTests.add("storage_logout_saves_only_co_users", StorageGameTests::logoutSavesOnlyCoUsers);
        JasmGameTests.add("storage_stamp_saved_only_while_held", StorageGameTests::stampSavedOnlyWhileHeld);
        JasmGameTests.add("storage_stale_copy_locked_until_newest_seen", StorageGameTests::staleCopyLockedUntilNewestSeen);
        JasmGameTests.add("storage_full_64k_wafers_of_different_items_survive_reload", StorageGameTests::fullWafersSurviveReload);
    }

    private static WaferStore store(GameTestHelper helper) {
        return WaferStore.get(helper.getLevel().getServer());
    }

    private static RegistryOps<Tag> ops(GameTestHelper helper) {
        return helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    private static ItemStack blank(WaferTier tier) {
        return new ItemStack(JasmItems.wafer(tier));
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
    }

    /** Runs a test with mock players whose player files are deleted afterwards. */
    private static void withPlayers(GameTestHelper helper, int count, BiConsumer<MinecraftServer, List<ServerPlayer>> test) {
        MinecraftServer server = helper.getLevel().getServer();
        List<ServerPlayer> players = IntStream.range(0, count).mapToObj(i -> player(helper)).toList();
        try {
            test.accept(server, players);
        } finally {
            players.forEach(p -> deletePlayerFiles(server, p));
        }
    }

    /** As if {@code player}'s file had just been written on its own (logging out), with {@code online} on the server. */
    private static void logout(MinecraftServer server, ServerPlayer player, List<ServerPlayer> online) {
        StorageEvents.afterPlayerSaved(server, player, online);
    }

    private static void variantsStayDistinct(GameTestHelper helper) {
        WaferStore store = store(helper);
        WaferRecord record = store.create(WaferTier.K1.capacity(), null);
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        ItemStack damaged = new ItemStack(Items.DIAMOND_SWORD);
        damaged.setDamageValue(100);
        ItemResource plainKey = ItemResource.of(Items.DIAMOND_SWORD);
        ItemResource namedKey = ItemResource.of(named);
        ItemResource damagedKey = ItemResource.of(damaged);
        store.insert(record, plainKey, 1, false, null);
        store.insert(record, namedKey, 1, false, null);
        store.insert(record, damagedKey, 1, false, null);
        helper.assertTrue(record.contents().size() == 3, "three distinct variants");
        helper.assertTrue(store.extract(record, namedKey, 5, false, null) == 1, "extract only the named one");
        helper.assertTrue(record.count(plainKey) == 1 && record.count(damagedKey) == 1, "others untouched");
        helper.assertTrue(damagedKey.toStack().getDamageValue() == 100, "damage preserved");
        helper.succeed();
    }

    /**
     * Items whose mod is missing (unknown item, unknown component, unknown enchantment) are kept verbatim, still use
     * capacity, and are written back unchanged so they return once the mod does.
     */
    private static void keepsItemsFromMissingMods(GameTestHelper helper) {
        RegistryOps<Tag> ops = ops(helper);
        WaferRecord source = store(helper).create(WaferTier.K1.capacity(), null);
        store(helper).insert(source, ItemResource.of(Items.DIAMOND_SWORD), 1, false, null);
        CompoundTag encoded = (CompoundTag) WaferRecord.CODEC.encodeStart(ops, source).getOrThrow();
        ListTag contents = encoded.getListOrEmpty("contents");

        CompoundTag unknownItem = contents.getCompoundOrEmpty(0).copy();
        unknownItem.getCompoundOrEmpty("item").putString("id", "notamod:vanished_thing");
        unknownItem.putLong("count", 7);
        contents.add(unknownItem);

        CompoundTag unknownComponent = contents.getCompoundOrEmpty(0).copy();
        CompoundTag components = new CompoundTag();
        components.putInt("notamod:charge", 3);
        unknownComponent.getCompoundOrEmpty("item").put("components", components);
        unknownComponent.putLong("count", 2);
        contents.add(unknownComponent);

        CompoundTag unknownEnchantment = contents.getCompoundOrEmpty(0).copy();
        CompoundTag enchantments = new CompoundTag();
        enchantments.putInt("notamod:zap", 2);
        CompoundTag enchanted = new CompoundTag();
        enchanted.put("minecraft:enchantments", enchantments);
        unknownEnchantment.getCompoundOrEmpty("item").put("components", enchanted);
        unknownEnchantment.putLong("count", 4);
        contents.add(unknownEnchantment);

        WaferRecord loaded = WaferRecord.CODEC.parse(ops, encoded).getOrThrow();
        helper.assertTrue(loaded.count(ItemResource.of(Items.DIAMOND_SWORD)) == 1, "known item decoded");
        helper.assertTrue(loaded.contents().size() == 1, "nothing half-decoded into a plain item");
        helper.assertTrue(loaded.quarantinedCount() == 13, "missing-mod entries kept aside with their counts");
        helper.assertTrue(loaded.used() == 14, "they still use capacity");
        helper.assertTrue(loaded.free() == WaferTier.K1.capacity() - 14, "capacity respected");
        String reencoded = WaferRecord.CODEC.encodeStart(ops, loaded).getOrThrow().toString();
        helper.assertTrue(reencoded.contains("notamod:vanished_thing") && reencoded.contains("notamod:charge")
                && reencoded.contains("notamod:zap"), "written back verbatim");
        helper.succeed();
    }

    /** One entry that fails to save must never stop the rest from saving. */
    private static void unsaveableEntryBecomesPlaceholder(GameTestHelper helper) {
        Codec<String> picky = Codec.STRING.flatXmap(DataResult::success,
                s -> s.equals("broken") ? DataResult.error(() -> "cannot save " + s) : DataResult.success(s));
        LenientListCodec<String> codec = new LenientListCodec<>(picky, "test value");
        Tag saved = codec.encodeStart(NbtOps.INSTANCE, LenientListCodec.Lenient.of(List.of("fine", "broken", "also fine"))).getOrThrow();
        LenientListCodec.Lenient<String> reloaded = codec.parse(NbtOps.INSTANCE, saved).getOrThrow();
        helper.assertTrue(reloaded.values().equals(List.of("fine", "also fine")), "good values saved");
        helper.assertTrue(reloaded.raw().size() == 1, "broken value kept as a placeholder");

        // The shape a wafer entry placeholder is saved in: kept aside, and still counted against capacity.
        RegistryOps<Tag> ops = ops(helper);
        CompoundTag encoded = (CompoundTag) WaferRecord.CODEC.encodeStart(ops, store(helper).create(WaferTier.K1.capacity(), null)).getOrThrow();
        CompoundTag placeholder = new CompoundTag();
        placeholder.putString("unsaveable_item", "1 minecraft:stone");
        placeholder.putLong("count", 30);
        placeholder.putString("error", "test");
        encoded.getListOrEmpty("contents").add(placeholder);
        WaferRecord loaded = WaferRecord.CODEC.parse(ops, encoded).getOrThrow();
        helper.assertTrue(loaded.quarantinedCount() == 30 && loaded.contents().isEmpty(), "placeholder kept aside with its count");
        helper.succeed();
    }

    private static void recordSurvivesReload(GameTestHelper helper) {
        WaferStore store = store(helper);
        WaferRecord record = store.create(WaferTier.K4.capacity(), null);
        store.insert(record, ItemResource.of(Items.EMERALD), 40, false, null);
        store.writeAllDirty();
        store.flush();
        helper.assertTrue(store.unload(record.serial()), "saved record can be dropped from memory");
        WaferRecord reloaded = store.bySerial(record.serial()).orElseThrow();
        helper.assertTrue(reloaded != record, "read back from disk");
        helper.assertTrue(reloaded.id().equals(record.id()) && reloaded.count(ItemResource.of(Items.EMERALD)) == 40, "same wafer, same contents");
        helper.assertFalse(reloaded.newestSeen(), "a reloaded record has not seen its newest copy yet");
        helper.succeed();
    }

    /** How many completely full 64K wafers the size test writes. */
    static int fullWaferCount = 1;

    /**
     * The biggest records there can be: full 64K wafers where every item is different. They are far larger than a
     * region file slot holds, so this checks they save and load whole, and logs how long saving takes.
     */
    private static void fullWafersSurviveReload(GameTestHelper helper) {
        WaferStore store = store(helper);
        int capacity = WaferTier.K64.capacity();
        List<WaferRecord> records = new java.util.ArrayList<>();
        for (int w = 0; w < fullWaferCount; w++) {
            WaferRecord record = store.create(capacity, null);
            for (int i = 0; i < capacity; i++) {
                store.insert(record, page(w, i), 1, false, null);
            }
            helper.assertTrue(record.free() == 0, "wafer " + w + " is full");
            records.add(record);
        }
        long start = System.nanoTime();
        store.writeAllDirty();
        long encoded = System.nanoTime();
        store.flush();
        long written = System.nanoTime();
        dev.micolash.jasm.Jasm.LOGGER.info("Saved {} full 64K wafers: {} ms on the server thread, {} ms until on disk",
                records.size(), (encoded - start) / 1_000_000, (written - start) / 1_000_000);

        for (int w = 0; w < records.size(); w++) {
            WaferRecord record = records.get(w);
            helper.assertTrue(store.unload(record.serial()), "wafer " + w + " was saved");
            WaferRecord reloaded = store.bySerial(record.serial()).orElseThrow();
            helper.assertTrue(reloaded.contents().size() == capacity && reloaded.used() == capacity, "wafer " + w + " came back whole");
            helper.assertTrue(reloaded.count(page(w, capacity - 1)) == 1 && reloaded.count(page(w, 0)) == 1, "with its own items");
        }
        helper.succeed();
    }

    private static ItemResource page(int wafer, int index) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Page " + wafer + "-" + index));
        return ItemResource.of(stack);
    }

    /** After a crash the serial counter can be behind; used slots must be skipped, never overwritten. */
    private static void serialSkipsUsedSlots(GameTestHelper helper) {
        WaferStore store = store(helper);
        WaferRecord first = store.create(WaferTier.BASIC.capacity(), null);
        store.insert(first, ItemResource.of(Items.COBBLESTONE), 5, false, null);
        store.writeAllDirty();
        store.unload(first.serial());
        store.state().rewindSerial(first.serial());
        WaferRecord second = store.create(WaferTier.BASIC.capacity(), null);
        helper.assertTrue(second.serial() > first.serial(), "used slot skipped");
        helper.assertTrue(store.bySerial(first.serial()).orElseThrow().count(ItemResource.of(Items.COBBLESTONE)) == 5, "first record intact");
        helper.succeed();
    }

    /** A record that cannot be read (for example written by a newer or broken version) locks its wafer and is kept. */
    private static void unreadableRecordLocksWafer(GameTestHelper helper) {
        WaferStore store = store(helper);
        ItemStack wafer = blank(WaferTier.K1);
        WaferRecord record = WaferValidator.format(store, wafer, null);
        WaferIdentity identity = wafer.get(JasmComponents.WAFER_IDENTITY.get());
        CompoundTag broken = new CompoundTag();
        CompoundTag body = (CompoundTag) WaferRecord.CODEC.encodeStart(ops(helper), record).getOrThrow();
        body.putString("capacity", "not a number");
        broken.put("record", body);
        store.writeRaw(record.serial(), broken).join();

        for (Mode mode : Mode.values()) {
            helper.assertTrue(WaferValidator.validate(store, wafer, mode, null) == Verdict.UNREADABLE, "locked in " + mode);
            helper.assertTrue(identity.equals(wafer.get(JasmComponents.WAFER_IDENTITY.get())), "identity untouched in " + mode);
        }
        helper.assertTrue(store.isUnreadable(record.serial()), "reported as unreadable");
        store.state().rewindSerial(record.serial());
        WaferRecord next = store.create(WaferTier.K1.capacity(), null);
        helper.assertTrue(next.serial() != record.serial(), "an unreadable slot is never reused");
        helper.succeed();
    }

    /**
     * Alice logs out: only records she changed are written, and only after every other online player who also
     * changed them has been saved. Players who changed nothing she touched are left alone.
     */
    private static void logoutSavesOnlyCoUsers(GameTestHelper helper) {
        withPlayers(helper, 3, (server, players) -> {
            ServerPlayer alice = players.get(0);
            ServerPlayer bob = players.get(1);
            ServerPlayer carol = players.get(2);
            WaferStore store = store(helper);
            WaferRecord shared = store.create(WaferTier.K1.capacity(), null);
            WaferRecord carols = store.create(WaferTier.K1.capacity(), null);
            store.writeAllDirty();

            store.insert(shared, ItemResource.of(Items.IRON_INGOT), 3, false, alice);
            store.insert(carols, ItemResource.of(Items.GOLD_INGOT), 3, false, carol);
            logout(server, alice, players);
            helper.assertFalse(shared.isDirty(), "Alice's wafer written behind her file");
            helper.assertTrue(carols.isDirty(), "Carol's wafer waits for Carol's own save");
            helper.assertFalse(Files.exists(playerFile(server, bob)), "Bob changed nothing and is not saved");
            helper.assertFalse(Files.exists(playerFile(server, carol)), "Carol's change is not on a wafer Alice used");

            store.insert(shared, ItemResource.of(Items.IRON_INGOT), 2, false, bob);
            store.insert(shared, ItemResource.of(Items.IRON_INGOT), 1, false, alice);
            logout(server, alice, players);
            helper.assertTrue(Files.exists(playerFile(server, bob)), "Bob also changed Alice's wafer, so his file is saved first");
            helper.assertFalse(shared.isDirty(), "then the wafer is written");
            helper.assertFalse(Files.exists(playerFile(server, carol)), "Carol still untouched");
        });
        helper.succeed();
    }

    /** A wafer's saved stamp only moves forward while it sits in a player file that was just saved. */
    private static void stampSavedOnlyWhileHeld(GameTestHelper helper) {
        withPlayers(helper, 1, (server, players) -> {
            ServerPlayer alice = players.get(0);
            WaferStore store = store(helper);
            ItemStack wafer = blank(WaferTier.K1);
            WaferRecord record = WaferValidator.format(store, wafer, alice);
            alice.getInventory().setItem(0, wafer);
            WaferValidator.validate(store, wafer, Mode.ACTIVATE, alice);
            Stamp heldStamp = wafer.get(JasmComponents.WAFER_IDENTITY.get()).stamp();
            logout(server, alice, players);
            helper.assertTrue(record.confirmed().equals(heldStamp), "stamp saved while the wafer is in her saved file");

            WaferValidator.validate(store, wafer, Mode.ACTIVATE, alice);
            alice.getInventory().clearContent(); // put in a chest, say
            store.insert(record, ItemResource.of(Items.COAL), 4, false, alice);
            logout(server, alice, players);
            helper.assertFalse(record.isDirty(), "her contents change is written");
            helper.assertTrue(record.confirmed().equals(heldStamp), "but the newer stamp is not, since no saved file holds it");
            helper.assertTrue(store.unload(record.serial()), "record saved");
            WaferRecord reloaded = store.bySerial(record.serial()).orElseThrow();
            helper.assertTrue(reloaded.current().equals(heldStamp) && reloaded.count(ItemResource.of(Items.COAL)) == 4,
                    "disk has the held stamp and the latest contents");
        });
        helper.succeed();
    }

    /**
     * After a restart an older copy may be the only survivor, so it is locked, not wiped. Once the newest copy
     * shows up, the older one is a proven copy and is blanked.
     */
    private static void staleCopyLockedUntilNewestSeen(GameTestHelper helper) {
        withPlayers(helper, 1, (server, players) -> {
            ServerPlayer alice = players.get(0);
            WaferStore store = store(helper);
            ItemStack wafer = blank(WaferTier.K4);
            WaferRecord record = WaferValidator.format(store, wafer, alice);
            alice.getInventory().setItem(0, wafer);
            WaferValidator.validate(store, wafer, Mode.ACTIVATE, alice);
            ItemStack olderCopy = wafer.copy();
            WaferValidator.validate(store, wafer, Mode.ACTIVATE, alice);
            logout(server, alice, players);
            helper.assertTrue(store.unload(record.serial()), "saved, then forgotten as after a restart");

            WaferIdentity copyIdentity = olderCopy.get(JasmComponents.WAFER_IDENTITY.get());
            helper.assertTrue(WaferValidator.validate(store, olderCopy, Mode.ACTIVATE, null) == Verdict.STALE, "older copy locked");
            helper.assertTrue(copyIdentity.equals(olderCopy.get(JasmComponents.WAFER_IDENTITY.get())), "not wiped");
            helper.assertTrue(WaferValidator.validate(store, wafer, Mode.CHECK, alice) == Verdict.VALID, "newest copy still valid");
            helper.assertTrue(WaferValidator.validate(store, olderCopy, Mode.PASSIVE, null) == Verdict.DUPLICATE, "now a proven copy");
            helper.assertFalse(olderCopy.has(JasmComponents.WAFER_IDENTITY.get()), "and wiped blank");
        });
        helper.succeed();
    }

    private static Path playerFile(MinecraftServer server, ServerPlayer player) {
        return server.getPlayerList().getPlayerIo().getPlayerDir().toPath().resolve(player.getStringUUID() + ".dat");
    }

    private static void deletePlayerFiles(MinecraftServer server, ServerPlayer player) {
        Path file = playerFile(server, player);
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(player.getStringUUID() + ".dat_old"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
