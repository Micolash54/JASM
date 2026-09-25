package dev.micolash.jasm.gametest;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.archive.ArchiveMenu;
import dev.micolash.jasm.archive.ArchiveNetwork;
import dev.micolash.jasm.archive.ArchivePayloads;
import dev.micolash.jasm.archive.ArchivePlacement;
import dev.micolash.jasm.archive.ArchiveService;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.JasmState;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferTier;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/** Archive placement (who owns which record, and what happens to copies) and the Archive screen's safety. */
final class ArchiveGameTests {
    static final BlockPos AT = BlockPos.ZERO;
    private static final BlockPos BESIDE = new BlockPos(1, 0, 0);

    private ArchiveGameTests() {}

    static void register() {
        JasmGameTests.add("archive_fresh_placement_creates_a_record", ArchiveGameTests::freshPlacement);
        JasmGameTests.add("archive_mined_and_placed_again_keeps_its_record", ArchiveGameTests::minedAndPlacedAgain);
        JasmGameTests.add("archive_copy_placed_while_original_stands_is_new", ArchiveGameTests::copyPlacedWhileOriginalStands);
        JasmGameTests.add("archive_taken_by_another_player_gives_no_access", ArchiveGameTests::takenByAnotherPlayer);
        JasmGameTests.add("archive_left_behind_by_a_crash_takes_its_record_back", ArchiveGameTests::leftBehindTakesRecordBack);
        JasmGameTests.add("archive_with_a_lost_record_is_rebuilt_from_the_block", ArchiveGameTests::lostRecordRebuilt);
        JasmGameTests.add("archive_twin_of_a_loaded_archive_becomes_new", ArchiveGameTests::twinBecomesNew);
        JasmGameTests.add("archive_closing_the_screen_returns_wafers", ArchiveGameTests::closingReturnsWafers);
        JasmGameTests.add("archive_requests_from_wrong_or_unauthorized_screens_are_ignored", ArchiveGameTests::strayRequestsIgnored);
    }

    static JasmState state(GameTestHelper helper) {
        return WaferStore.get(helper.getLevel().getServer()).state();
    }

    /** Places an Archive as {@code placer} would, optionally from an item that carries an identity. */
    static ArchiveBlockEntity place(GameTestHelper helper, BlockPos pos, ArchiveTier tier, @Nullable ServerPlayer placer, @Nullable ItemStack item) {
        helper.setBlock(pos, JasmBlocks.archive(tier).get());
        ArchiveBlockEntity archive = helper.getBlockEntity(pos, ArchiveBlockEntity.class);
        if (item != null) {
            archive.applyComponentsFromItemStack(item);
        }
        ArchivePlacement.placed(archive, helper.getLevel(), placer);
        return archive;
    }

    private static ArchiveRecord.Placement placement(GameTestHelper helper, BlockPos pos) {
        return new ArchiveRecord.Placement(helper.getLevel().dimension(), helper.absolutePos(pos));
    }

    private static ItemStack itemFor(ArchiveTier tier, UUID identity) {
        ItemStack item = new ItemStack(JasmItems.archive(tier));
        item.set(JasmComponents.ARCHIVE_IDENTITY.get(), identity);
        return item;
    }

    /** Breaks the block with drops and returns the dropped Archive item. */
    private static ItemStack mine(GameTestHelper helper, BlockPos pos) {
        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
        List<ItemEntity> drops = helper.getEntities(EntityTypes.ITEM, pos, 2.0);
        helper.assertTrue(drops.size() == 1, "the Archive drops exactly itself, got " + drops.size());
        ItemStack item = drops.getFirst().getItem().copy();
        drops.forEach(ItemEntity::discard);
        return item;
    }

    private static void freshPlacement(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = place(helper, AT, ArchiveTier.BASIC, player, null);
            ArchiveRecord record = archive.record();
            helper.assertTrue(record != null, "a record is created");
            helper.assertTrue(record.owner().equals(player.getUUID()), "the placer owns it");
            helper.assertTrue(placement(helper, AT).equals(record.placement()), "the record knows where it stands");
            helper.assertTrue(record.isAuthorized(player.getUUID()), "the owner may use it");
            helper.assertTrue(player.getUUID().equals(archive.ownerId()), "the block keeps a copy of the owner");
        });
        helper.succeed();
    }

    private static void minedAndPlacedAgain(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = place(helper, AT, ArchiveTier.ADVANCED, player, null);
            ArchiveRecord record = archive.record();
            archive.energy().set(4_321);
            state(helper).addLinked(record, 999_999);

            ItemStack item = mine(helper, AT);
            helper.assertTrue(record.id().equals(item.get(JasmComponents.ARCHIVE_IDENTITY.get())), "the item carries the identity");
            helper.assertTrue(item.getOrDefault(JasmComponents.ENERGY.get(), 0) == 4_321, "and the charge");
            helper.assertTrue(record.placement() == null, "the record is now carried");

            ArchiveBlockEntity again = place(helper, BESIDE, ArchiveTier.ADVANCED, player, item);
            helper.assertTrue(record.id().equals(again.archiveId()), "placed again, it is the same Archive");
            helper.assertTrue(placement(helper, BESIDE).equals(record.placement()), "standing at its new spot");
            helper.assertTrue(record.linked().contains(999_999L), "its links are untouched");
            helper.assertTrue(again.energy().getAmountAsInt() == 4_321, "its charge came along");
            state(helper).removeLinked(record, 999_999);
            helper.setBlock(BESIDE, Blocks.AIR);
        });
        helper.succeed();
    }

    private static void copyPlacedWhileOriginalStands(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity original = place(helper, AT, ArchiveTier.BASIC, player, null);
            UUID id = original.archiveId();
            ArchiveBlockEntity copy = place(helper, BESIDE, ArchiveTier.BASIC, player, itemFor(ArchiveTier.BASIC, id));
            helper.assertFalse(id.equals(copy.archiveId()), "the copy gets its own identity");
            helper.assertTrue(placement(helper, AT).equals(original.record().placement()), "the original keeps its record");
            helper.assertTrue(copy.record().linked().isEmpty(), "the copy starts with no links");
            helper.setBlock(BESIDE, Blocks.AIR);
        });
        helper.succeed();
    }

    private static void takenByAnotherPlayer(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, owner -> DeckStorageGameTests.withPlayer(helper, thief -> {
            ArchiveBlockEntity archive = place(helper, AT, ArchiveTier.BASIC, owner, null);
            archive.energy().set(archive.tier().energyBuffer());
            UUID id = archive.archiveId();
            ItemStack item = mine(helper, AT);
            ArchiveBlockEntity stolen = place(helper, BESIDE, ArchiveTier.BASIC, thief, item);
            ArchiveRecord record = stolen.record();
            helper.assertTrue(id.equals(stolen.archiveId()), "it is still the same Archive");
            helper.assertTrue(record.owner().equals(owner.getUUID()), "the owner does not change");
            helper.assertFalse(record.isAuthorized(thief.getUUID()), "the new holder may not use it");
            WaferStore store = WaferStore.get(helper.getLevel().getServer());
            helper.assertTrue(ArchiveService.link(store, stolen, thief, new ItemStack(JasmItems.wafer(WaferTier.K1)))
                    == ArchiveService.Result.NO_ACCESS, "and cannot link wafers to it");
            helper.setBlock(BESIDE, Blocks.AIR);
        }));
        helper.succeed();
    }

    private static void leftBehindTakesRecordBack(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = place(helper, AT, ArchiveTier.BASIC, player, null);
            ArchiveRecord record = archive.record();
            state(helper).setPlacement(record, null);
            ArchivePlacement.loaded(archive, helper.getLevel());
            helper.assertTrue(placement(helper, AT).equals(record.placement()), "a carried record goes back to the block");

            state(helper).setPlacement(record, placement(helper, BESIDE));
            ArchivePlacement.loaded(archive, helper.getLevel());
            helper.assertTrue(record.id().equals(archive.archiveId()) && placement(helper, AT).equals(record.placement()),
                    "a record pointing at a loaded spot without that Archive also comes back");
        });
        helper.succeed();
    }

    private static void lostRecordRebuilt(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = place(helper, AT, ArchiveTier.ULTIMATE, player, null);
            UUID lost = UUID.randomUUID();
            archive.applyComponentsFromItemStack(itemFor(ArchiveTier.ULTIMATE, lost));
            ArchivePlacement.loaded(archive, helper.getLevel());
            ArchiveRecord record = archive.record();
            helper.assertTrue(lost.equals(archive.archiveId()) && record != null, "the record is rebuilt under the same id");
            helper.assertTrue(record.owner().equals(player.getUUID()), "with the owner the block remembered");
            helper.assertTrue(record.tier() == ArchiveTier.ULTIMATE && placement(helper, AT).equals(record.placement()), "tier and place");
        });
        helper.succeed();
    }

    private static void twinBecomesNew(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity original = place(helper, AT, ArchiveTier.BASIC, player, null);
            UUID id = original.archiveId();
            helper.setBlock(BESIDE, JasmBlocks.archive(ArchiveTier.BASIC).get());
            ArchiveBlockEntity twin = helper.getBlockEntity(BESIDE, ArchiveBlockEntity.class);
            twin.applyComponentsFromItemStack(itemFor(ArchiveTier.BASIC, id));
            ArchivePlacement.loaded(twin, helper.getLevel());
            helper.assertFalse(id.equals(twin.archiveId()), "the twin becomes a new Archive");
            helper.assertTrue(placement(helper, AT).equals(original.record().placement()), "the original keeps its record");
            helper.setBlock(BESIDE, Blocks.AIR);
        });
        helper.succeed();
    }

    private static void closingReturnsWafers(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, player -> {
            ArchiveBlockEntity archive = place(helper, AT, ArchiveTier.BASIC, player, null);
            ArchiveMenu menu = new ArchiveMenu(1, player.getInventory(), archive);
            menu.waferSlots().setItem(ArchiveMenu.LINK_SLOT, new ItemStack(JasmItems.wafer(WaferTier.K1)));
            menu.waferSlots().setItem(ArchiveMenu.RECOVERY_SLOT, new ItemStack(JasmItems.wafer(WaferTier.K4)));
            menu.removed(player);
            helper.assertTrue(menu.waferSlots().isEmpty(), "the slots are emptied");
            helper.assertTrue(player.getInventory().contains(s -> s.is(JasmItems.wafer(WaferTier.K1)))
                    && player.getInventory().contains(s -> s.is(JasmItems.wafer(WaferTier.K4))), "both wafers are back with the player");
        });
        helper.succeed();
    }

    private static void strayRequestsIgnored(GameTestHelper helper) {
        DeckStorageGameTests.withPlayer(helper, owner -> DeckStorageGameTests.withPlayer(helper, stranger -> {
            ArchiveBlockEntity archive = place(helper, AT, ArchiveTier.BASIC, owner, null);
            archive.energy().set(archive.tier().energyBuffer());
            standBeside(helper, owner);
            standBeside(helper, stranger);
            ArchiveMenu menu = new ArchiveMenu(7, owner.getInventory(), archive);
            owner.containerMenu = menu;
            menu.waferSlots().setItem(ArchiveMenu.LINK_SLOT, new ItemStack(JasmItems.wafer(WaferTier.K1)));
            helper.assertTrue(ArchiveNetwork.request(owner, ArchivePayloads.Request.of(8, ArchivePayloads.Action.LINK)) == null,
                    "a request for another screen is ignored");
            helper.assertFalse(menu.waferSlots().getItem(ArchiveMenu.LINK_SLOT).has(JasmComponents.WAFER_IDENTITY.get()), "nothing was linked");

            ArchiveMenu strangers = new ArchiveMenu(9, stranger.getInventory(), archive);
            stranger.containerMenu = strangers;
            strangers.waferSlots().setItem(ArchiveMenu.LINK_SLOT, new ItemStack(JasmItems.wafer(WaferTier.K1)));
            helper.assertTrue(ArchiveNetwork.request(stranger, ArchivePayloads.Request.of(9, ArchivePayloads.Action.LINK)) == null,
                    "a player without access is ignored even with the screen open");
            helper.assertTrue(archive.energy().getAmountAsInt() == archive.tier().energyBuffer(), "no charge was spent");

            helper.assertTrue(ArchiveNetwork.request(owner, ArchivePayloads.Request.of(7, ArchivePayloads.Action.LINK))
                    == ArchiveService.Result.OK, "the owner's own request works");
            unlinkAll(helper, archive, owner);
            owner.containerMenu = owner.inventoryMenu;
            stranger.containerMenu = stranger.inventoryMenu;
        }));
        helper.succeed();
    }

    /** Moves a test player next to the Archive, so its screen counts as within reach. */
    static void standBeside(GameTestHelper helper, ServerPlayer player) {
        BlockPos pos = helper.absolutePos(AT);
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
    }

    /** Clears this Archive's links so later tests start clean. */
    static void unlinkAll(GameTestHelper helper, ArchiveBlockEntity archive, ServerPlayer owner) {
        WaferStore store = WaferStore.get(helper.getLevel().getServer());
        for (long serial : List.copyOf(archive.record().linked())) {
            ArchiveService.unlink(store, archive, owner, serial);
        }
    }
}
