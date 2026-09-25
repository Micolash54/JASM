package dev.micolash.jasm.gametest;

import static dev.micolash.jasm.gametest.ArchiveGameTests.AT;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.archive.ArchiveService;
import dev.micolash.jasm.archive.ArchiveService.Result;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.Stamp;
import dev.micolash.jasm.core.StampPolicy.Verdict;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferTier;
import dev.micolash.jasm.wafer.WaferValidator;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Linking, unlinking, recovering and trust, including every way each can be refused. */
final class ArchiveServiceGameTests {

    private ArchiveServiceGameTests() {}

    static void register() {
        JasmGameTests.add("archive_link_formats_a_blank_and_costs_charge", ArchiveServiceGameTests::linkFormatsBlank);
        JasmGameTests.add("archive_refused_links_change_nothing", ArchiveServiceGameTests::refusedLinks);
        JasmGameTests.add("archive_recovery_rebuilds_a_lost_wafer", ArchiveServiceGameTests::recoveryRebuilds);
        JasmGameTests.add("archive_refused_recoveries_change_nothing", ArchiveServiceGameTests::refusedRecoveries);
        JasmGameTests.add("archive_relinked_wafer_leaves_the_old_archive", ArchiveServiceGameTests::relinkLeavesOldArchive);
        JasmGameTests.add("archive_second_recovery_dissolves_the_first", ArchiveServiceGameTests::secondRecovery);
        JasmGameTests.add("archive_unlink_and_trust", ArchiveServiceGameTests::unlinkAndTrust);
        JasmGameTests.add("archive_is_charged_by_a_creative_battery", ArchiveServiceGameTests::chargedByBattery);
        JasmGameTests.add("archive_uses_charge_every_tick_and_keeps_links_when_empty", ArchiveServiceGameTests::runningCost);
    }

    private static ItemResource diamond() {
        return ItemResource.of(Items.DIAMOND);
    }

    private static WaferStore store(GameTestHelper helper) {
        return WaferStore.get(helper.getLevel().getServer());
    }

    private static ArchiveBlockEntity charged(GameTestHelper helper, BlockPos pos, ArchiveTier tier, ServerPlayer owner) {
        ArchiveBlockEntity archive = ArchiveGameTests.place(helper, pos, tier, owner, null);
        archive.energy().set(tier.energyBuffer());
        return archive;
    }

    /** A formatted wafer holding {@code diamonds} diamonds. */
    private static ItemStack filled(GameTestHelper helper, WaferTier tier, int diamonds, ServerPlayer player) {
        ItemStack wafer = new ItemStack(JasmItems.wafer(tier));
        WaferRecord record = WaferValidator.format(store(helper), wafer, player);
        store(helper).insert(record, diamond(), diamonds, false, player);
        return wafer;
    }

    private static WaferRecord record(GameTestHelper helper, ItemStack wafer) {
        return WaferValidator.record(store(helper), wafer).orElseThrow();
    }

    /** The wafer of that tier in the player's inventory (recovery puts its replacement there). */
    private static ItemStack held(ServerPlayer player, WaferTier tier) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(JasmItems.wafer(tier))) {
                return player.getInventory().getItem(i);
            }
        }
        return ItemStack.EMPTY;
    }

    private static long serial(ItemStack wafer) {
        return Objects.requireNonNull(wafer.get(JasmComponents.WAFER_IDENTITY.get())).serial();
    }

    private static void linkFormatsBlank(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = charged(helper, AT, ArchiveTier.BASIC, player);
            ItemStack blank = new ItemStack(JasmItems.wafer(WaferTier.K1));
            helper.assertTrue(ArchiveService.link(store(helper), archive, player, blank) == Result.OK, "linking works");
            WaferRecord record = record(helper, blank);
            helper.assertTrue(archive.archiveId().equals(record.archiveId()), "the blank was formatted and linked");
            helper.assertTrue(ArchiveService.entries(store(helper), archive.record()).size() == 1, "the Archive lists it");
            helper.assertTrue(archive.energy().getAmountAsInt() == ArchiveTier.BASIC.energyBuffer() - JasmConfig.ARCHIVE_LINK_COST.getAsInt(),
                    "the link cost charge");
            helper.assertFalse(store(helper).state().isDirty(), "the Archive's list was written to disk straight away");
            helper.assertTrue(ArchiveService.link(store(helper), archive, player, blank) == Result.ALREADY_LINKED, "linking twice is refused");
            ArchiveGameTests.unlinkAll(helper, archive, player);
        });
        helper.succeed();
    }

    private static void refusedLinks(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = ArchiveGameTests.place(helper, AT, ArchiveTier.BASIC, player, null);
            ItemStack blank = new ItemStack(JasmItems.wafer(WaferTier.K1));
            helper.assertTrue(ArchiveService.link(store(helper), archive, player, blank) == Result.NO_POWER, "no charge, no link");
            helper.assertFalse(blank.has(JasmComponents.WAFER_IDENTITY.get()), "the blank was not even formatted");
            helper.assertTrue(ArchiveService.link(store(helper), archive, player, new ItemStack(Items.STONE)) == Result.NO_WAFER, "stone is no wafer");

            archive.energy().set(ArchiveTier.BASIC.energyBuffer());
            for (int i = 0; i < ArchiveTier.BASIC.registrations(); i++) {
                helper.assertTrue(ArchiveService.link(store(helper), archive, player, new ItemStack(JasmItems.wafer(WaferTier.K1))) == Result.OK,
                        "registration " + (i + 1));
            }
            int energy = archive.energy().getAmountAsInt();
            ItemStack extra = filled(helper, WaferTier.K1, 5, player);
            helper.assertTrue(ArchiveService.link(store(helper), archive, player, extra) == Result.FULL, "no free registrations");
            helper.assertTrue(record(helper, extra).archiveId() == null && archive.energy().getAmountAsInt() == energy, "nothing changed");

            ItemStack copy = extra.copy();
            WaferValidator.validate(store(helper), extra, WaferValidator.Mode.ACTIVATE, player);
            helper.assertTrue(ArchiveService.link(store(helper), archive, player, copy) == Result.WAFER_LOCKED, "an outdated copy can't be linked");
            ArchiveGameTests.unlinkAll(helper, archive, player);
        });
        helper.succeed();
    }

    private static void recoveryRebuilds(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = charged(helper, AT, ArchiveTier.BASIC, player);
            ItemStack original = filled(helper, WaferTier.K1, 100, player);
            helper.assertTrue(ArchiveService.link(store(helper), archive, player, original) == Result.OK, "linked");
            WaferRecord record = record(helper, original);
            store(helper).extract(record, diamond(), 40, false, player);
            int energy = archive.energy().getAmountAsInt();

            // The original is lost; a blank 4K wafer takes its place.
            ItemStack blank = new ItemStack(JasmItems.wafer(WaferTier.K4));
            player.getInventory().clearContent();
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, record.serial(), blank) == Result.OK, "recovered");
            helper.assertTrue(blank.isEmpty(), "the blank was used up");
            ItemStack replacement = held(player, WaferTier.K4);
            WaferIdentity identity = replacement.get(JasmComponents.WAFER_IDENTITY.get());
            helper.assertTrue(identity != null && identity.id().equals(record.id()) && identity.serial() == record.serial(),
                    "the replacement is the same wafer");
            helper.assertTrue(record.count(diamond()) == 60, "with what was left after the withdrawal");
            helper.assertTrue(record.capacity() == WaferTier.K4.capacity(), "now with the blank's capacity");
            helper.assertTrue(archive.energy().getAmountAsInt() == energy - JasmConfig.ARCHIVE_RECOVERY_COST.getAsInt(), "recovery cost charge");
            helper.assertTrue(archive.archiveId().equals(record.archiveId()), "and it stays linked");

            helper.assertTrue(WaferValidator.validate(store(helper), replacement, WaferValidator.Mode.CHECK, player) == Verdict.VALID,
                    "the replacement works");
            helper.assertTrue(WaferValidator.validate(store(helper), original, WaferValidator.Mode.CHECK, player) == Verdict.RECOVERED_ORIGINAL,
                    "the lost original dissolves if it turns up");
            helper.assertTrue(original.isEmpty(), "gone");
            player.getInventory().clearContent();
            ArchiveGameTests.unlinkAll(helper, archive, player);
        });
        helper.succeed();
    }

    private static void refusedRecoveries(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = charged(helper, AT, ArchiveTier.BASIC, player);
            ItemStack wafer = filled(helper, WaferTier.K1, 300, player);
            ArchiveService.link(store(helper), archive, player, wafer);
            WaferRecord record = record(helper, wafer);
            Stamp current = record.current();
            int energy = archive.energy().getAmountAsInt();
            ItemStack blank = new ItemStack(JasmItems.wafer(WaferTier.K1));

            ItemStack unlinked = filled(helper, WaferTier.K1, 1, player);
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, serial(unlinked), blank) == Result.NOT_LINKED_HERE,
                    "a wafer not linked here");
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, record.serial(), unlinked) == Result.NOT_BLANK,
                    "a wafer holding items is no blank");
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, record.serial(), wafer) == Result.NOT_BLANK,
                    "nor is the wafer itself");
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, record.serial(), new ItemStack(JasmItems.wafer(WaferTier.BASIC)))
                    == Result.TOO_SMALL, "256 slots can't hold 300 diamonds");
            for (int i = 0; i < player.getInventory().getNonEquipmentItems().size(); i++) {
                player.getInventory().setItem(i, new ItemStack(Items.STONE));
            }
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, record.serial(), blank) == Result.NO_ROOM, "full inventory");
            player.getInventory().clearContent();
            archive.energy().set(JasmConfig.ARCHIVE_RECOVERY_COST.getAsInt() - 1);
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, record.serial(), blank) == Result.NO_POWER, "not enough charge");

            helper.assertTrue(record.current().equals(current) && record.recoveryFloor() == null, "the wafer's record is untouched");
            helper.assertTrue(blank.getCount() == 1 && !blank.has(JasmComponents.WAFER_IDENTITY.get()), "the blank is untouched");
            helper.assertTrue(player.getInventory().isEmpty(), "nothing was handed out");
            helper.assertTrue(WaferValidator.validate(store(helper), wafer, WaferValidator.Mode.CHECK, player) == Verdict.VALID, "the wafer still works");
            archive.energy().set(energy);
            ArchiveGameTests.unlinkAll(helper, archive, player);
        });
        helper.succeed();
    }

    private static void relinkLeavesOldArchive(GameTestHelper helper) {
        BlockPos other = new BlockPos(1, 0, 0);
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity first = charged(helper, AT, ArchiveTier.BASIC, player);
            ArchiveBlockEntity second = charged(helper, other, ArchiveTier.BASIC, player);
            ItemStack wafer = filled(helper, WaferTier.K1, 10, player);
            ArchiveService.link(store(helper), first, player, wafer);
            helper.assertTrue(ArchiveService.link(store(helper), second, player, wafer) == Result.OK, "linking elsewhere replaces the link");
            long serial = serial(wafer);
            helper.assertTrue(ArchiveService.entries(store(helper), first.record()).isEmpty(), "the old Archive no longer lists it");
            helper.assertFalse(first.record().linked().contains(serial), "and dropped it from its list");
            helper.assertTrue(ArchiveService.recover(store(helper), first, player, serial, new ItemStack(JasmItems.wafer(WaferTier.K1)))
                    == Result.NOT_LINKED_HERE, "the old Archive can't recover it");
            helper.assertTrue(ArchiveService.entries(store(helper), second.record()).size() == 1, "the new one lists it");
            ArchiveGameTests.unlinkAll(helper, second, player);
            helper.setBlock(other, Blocks.AIR);
        });
        helper.succeed();
    }

    private static void secondRecovery(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = charged(helper, AT, ArchiveTier.BASIC, player);
            ItemStack wafer = filled(helper, WaferTier.K1, 7, player);
            ArchiveService.link(store(helper), archive, player, wafer);
            long serial = serial(wafer);
            player.getInventory().clearContent();
            ArchiveService.recover(store(helper), archive, player, serial, new ItemStack(JasmItems.wafer(WaferTier.K1)));
            ItemStack first = held(player, WaferTier.K1).copy();
            player.getInventory().clearContent();
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, serial, new ItemStack(JasmItems.wafer(WaferTier.K1))) == Result.OK,
                    "a second recovery works");
            helper.assertTrue(WaferValidator.validate(store(helper), first, WaferValidator.Mode.CHECK, player) == Verdict.RECOVERED_ORIGINAL,
                    "the first replacement dissolves");
            player.getInventory().clearContent();
            ArchiveGameTests.unlinkAll(helper, archive, player);
        });
        helper.succeed();
    }

    private static void unlinkAndTrust(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, owner -> DeckStorageGameTests.withPlayer(helper, friend -> {
            ArchiveBlockEntity archive = charged(helper, AT, ArchiveTier.BASIC, owner);
            ItemStack wafer = filled(helper, WaferTier.K1, 3, owner);
            ArchiveService.link(store(helper), archive, owner, wafer);
            WaferRecord record = record(helper, wafer);
            helper.assertTrue(ArchiveService.unlink(store(helper), archive, friend, record.serial()) == Result.NO_ACCESS, "strangers can't unlink");
            helper.assertTrue(ArchiveService.unlink(store(helper), archive, owner, record.serial()) == Result.OK, "the owner can");
            helper.assertTrue(record.archiveId() == null && ArchiveService.entries(store(helper), archive.record()).isEmpty(), "unlinked");
            helper.assertFalse(store(helper).state().isDirty(), "and written to disk straight away");

            helper.assertTrue(ArchiveService.trust(store(helper), archive, owner, "nobody_by_this_name") == Result.PLAYER_NOT_FOUND, "unknown name");
            helper.assertTrue(ArchiveService.trust(store(helper), archive, friend, "whoever") == Result.NOT_OWNER, "only the owner manages trust");
            store(helper).state().trust(archive.record(), friend.getUUID(), friend.getPlainTextName());
            helper.assertTrue(ArchiveService.link(store(helper), archive, friend, wafer) == Result.OK, "a trusted player can link");
            helper.assertTrue(ArchiveService.untrust(store(helper), archive, friend, friend.getUUID()) == Result.NOT_OWNER, "but not manage trust");
            helper.assertTrue(ArchiveService.untrust(store(helper), archive, owner, friend.getUUID()) == Result.OK, "the owner removes them");
            helper.assertTrue(ArchiveService.unlink(store(helper), archive, friend, record.serial()) == Result.NO_ACCESS, "and they lose access");
            ArchiveGameTests.unlinkAll(helper, archive, owner);
        }));
        helper.succeed();
    }

    private static void runningCost(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            int previousDrain = 0;
            for (ArchiveTier tier : ArchiveTier.values()) {
                helper.assertTrue(tier.drainPerTick() > previousDrain, tier + " costs more to run than the tier below");
                previousDrain = tier.drainPerTick();
                ArchiveBlockEntity archive = charged(helper, AT, tier, player);
                archive.drain();
                helper.assertTrue(archive.energy().getAmountAsInt() == tier.energyBuffer() - tier.drainPerTick(), tier + " uses its running cost");
            }
            ArchiveBlockEntity archive = charged(helper, AT, ArchiveTier.BASIC, player);
            ItemStack wafer = filled(helper, WaferTier.K1, 2, player);
            ArchiveService.link(store(helper), archive, player, wafer);
            archive.energy().set(ArchiveTier.BASIC.drainPerTick() - 1);
            archive.drain();
            archive.drain();
            helper.assertTrue(archive.energy().getAmountAsInt() == 0, "it runs dry and stays at zero");
            helper.assertTrue(ArchiveService.entries(store(helper), archive.record()).size() == 1, "its links survive");
            helper.assertTrue(ArchiveService.recover(store(helper), archive, player, serial(wafer), new ItemStack(JasmItems.wafer(WaferTier.K1)))
                    == Result.NO_POWER, "but it can't recover until charged");
            ArchiveGameTests.unlinkAll(helper, archive, player);
        });
        helper.succeed();
    }

    private static void chargedByBattery(GameTestHelper helper) {
        BlockPos above = AT.above();
        DeckStorageGameTests.withPlayer(helper, player -> ArchiveGameTests.place(helper, AT, ArchiveTier.ULTIMATE, player, null));
        helper.setBlock(above, JasmBlocks.CREATIVE_BATTERY.get());
        ArchiveBlockEntity archive = helper.getBlockEntity(AT, ArchiveBlockEntity.class);
        helper.succeedWhen(() -> {
            helper.assertTrue(archive.energy().getAmountAsInt() >= ArchiveTier.ULTIMATE.energyBuffer() - ArchiveTier.ULTIMATE.drainPerTick(),
                    "the Archive fills up (less at most one tick of running cost)");
            helper.setBlock(above, Blocks.AIR);
        });
    }
}
