package dev.micolash.jasm.archive;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.JasmState;
import dev.micolash.jasm.storage.WaferStore;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Keeps each Archive record tied to one block. An Archive item carries its record's id, so mining and placing it
 * again keeps its owner, trust and links. When two blocks claim the same record (a copied item, or a block left
 * behind by a crash), the one the record points to keeps it and the other becomes a new, empty Archive. An
 * unloaded position is never loaded to check; when in doubt, the newcomer becomes the new Archive. Wafers are
 * never affected: at worst they need linking again. A block whose record was lost gets it back under the same id,
 * and its list of linked wafers is rebuilt from the wafers' own records.
 */
public final class ArchivePlacement {
    /** Owner of an Archive placed by something other than a player: nobody can use it. */
    public static final UUID NO_OWNER = new UUID(0L, 0L);

    private ArchivePlacement() {}

    /** A player (or a machine) placed the block. The item's identity, if any, is already on the block. */
    public static void placed(ArchiveBlockEntity archive, ServerLevel level, @Nullable Player placer) {
        JasmState state = WaferStore.get(level.getServer()).state();
        ArchiveRecord.Placement here = here(archive, level);
        UUID id = archive.archiveId();
        ArchiveRecord record = id == null ? null : state.archive(id).orElse(null);
        boolean fresh = record == null;
        if (id != null && record == null) {
            Jasm.LOGGER.warn("Archive placed at {} carries unknown record {}; it becomes a new Archive", here, id);
        } else if (record != null && record.tier() != archive.tier()) {
            Jasm.LOGGER.warn("Archive placed at {} carries record {} of another tier; it becomes a new Archive", here, id);
            fresh = true;
        } else if (record != null && record.placement() != null && !record.placement().equals(here)) {
            Jasm.LOGGER.info("Archive placed at {} is a copy of {} standing at {}; it becomes a new Archive", here, id, record.placement());
            fresh = true;
        }
        if (fresh) {
            record = placer == null
                    ? state.createArchive(archive.tier(), NO_OWNER, "")
                    : state.createArchive(archive.tier(), placer.getUUID(), placer.getPlainTextName());
        }
        state.setPlacement(record, here);
        archive.bind(record);
    }

    /** The block is gone (mined, replaced): its record is now carried as an item. */
    public static void removed(ArchiveBlockEntity archive, ServerLevel level) {
        WaferStore store = WaferStore.ifOpen(level.getServer());
        UUID id = archive.archiveId();
        if (store == null || id == null) {
            return;
        }
        ArchiveRecord.Placement here = here(archive, level);
        store.state().archive(id).ifPresent(record -> {
            if (here.equals(record.placement())) {
                store.state().setPlacement(record, null);
            }
        });
    }

    /** First server tick after the block was loaded from disk. The store is open. */
    public static void loaded(ArchiveBlockEntity archive, ServerLevel level) {
        WaferStore store = WaferStore.get(level.getServer());
        JasmState state = store.state();
        ArchiveRecord.Placement here = here(archive, level);
        UUID id = archive.archiveId();
        UUID owner = archive.ownerId() == null ? NO_OWNER : archive.ownerId();
        if (id == null) {
            Jasm.LOGGER.info("Archive at {} has no record yet; creating one", here);
            ArchiveRecord record = state.createArchive(archive.tier(), owner, archive.ownerName());
            state.setPlacement(record, here);
            archive.bind(record);
            return;
        }
        ArchiveRecord record = state.archive(id).orElse(null);
        if (record == null) {
            Jasm.LOGGER.warn("Archive at {} lost its record {}; rebuilding it from the block and its wafers", here, id);
            record = state.restoreArchive(id, archive.tier(), owner, archive.ownerName());
            store.relinkAll(record);
        } else if (record.tier() != archive.tier()) {
            Jasm.LOGGER.warn("Archive at {} has record {} of another tier; it becomes a new Archive", here, id);
            record = state.createArchive(archive.tier(), owner, archive.ownerName());
        } else if (record.placement() != null && !record.placement().equals(here) && !isStale(level, record.placement(), id)) {
            Jasm.LOGGER.warn("Archive at {} claims record {}, which belongs to the Archive at {}; it becomes a new Archive",
                    here, id, record.placement());
            record = state.createArchive(archive.tier(), owner, archive.ownerName());
        } else if (!here.equals(record.placement())) {
            Jasm.LOGGER.info("Archive at {} takes back record {} (was {})", here, id, record.placement() == null ? "carried" : record.placement());
        }
        state.setPlacement(record, here);
        archive.bind(record);
    }

    /** Whether the record's position is loaded and no longer holds that Archive. Unloaded positions are never loaded. */
    private static boolean isStale(ServerLevel level, ArchiveRecord.Placement at, UUID id) {
        ServerLevel other = level.getServer().getLevel(at.dimension());
        if (other == null || !other.isLoaded(at.pos())) {
            return false;
        }
        return !(other.getBlockEntity(at.pos()) instanceof ArchiveBlockEntity twin && id.equals(twin.archiveId()));
    }

    private static ArchiveRecord.Placement here(ArchiveBlockEntity archive, ServerLevel level) {
        return new ArchiveRecord.Placement(level.dimension(), archive.getBlockPos());
    }
}
