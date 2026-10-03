package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/** Keeping jobs safe across crashes and unloads, and clearing finished ones from the list. */
@EventBusSubscriber(modid = Jasm.MODID)
final class JobRecovery {
    private JobRecovery() {}

    /**
     * A chunk with a port or server of this job is being saved and unloaded: the job's items are written too, so a
     * crash later can't find a set both inside the machine and back in the job.
     */
    static void writeNow(MinecraftServer server, UUID jobId) {
        WaferStore store = WaferStore.ifOpen(server);
        AutocraftState.Job entry = AutocraftState.get(server).job(jobId).orElse(null);
        if (store != null && entry != null) {
            store.jobRecord(entry.serial(), entry.recordId()).ifPresent(store::writeNow);
        }
    }

    /** A job the list says belongs here, but the block lost (its chunk was saved before the job started): it returns its items. */
    public static void adopt(ServerLevel level, CraftingServerBlockEntity server) {
        if (server.busy()) {
            return;
        }
        ArchiveRecord.Placement here = new ArchiveRecord.Placement(level.dimension(), server.getBlockPos());
        WaferStore store = WaferStore.ifOpen(level.getServer());
        if (store == null) {
            return;
        }
        for (AutocraftState.Job entry : AutocraftState.get(level.getServer()).jobs()) {
            if (!entry.server().equals(here)) {
                continue;
            }
            WaferRecord record = store.jobRecord(entry.serial(), entry.recordId()).orElse(null);
            if (record != null && !record.contents().isEmpty()) {
                Jasm.LOGGER.warn("Crafting Server at {} lost its job {} in a crash; returning its items", here, entry.id());
                server.setJob(CraftingJob.adopted(entry));
                return;
            }
        }
    }

    /**
     * Finished jobs leave the list once their records are on disk, and an empty record gives up its slot. A finished
     * job whose record still holds something is left alone: whatever is in it stays on disk for an admin to find.
     */
    @SubscribeEvent
    static void cleanUp(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 200 != 0) {
            return;
        }
        WaferStore store = WaferStore.ifOpen(server);
        if (store == null) {
            return;
        }
        AutocraftState state = AutocraftState.get(server);
        for (AutocraftState.Job entry : state.jobs()) {
            if (!entry.finished()) {
                continue;
            }
            WaferRecord record = store.jobRecord(entry.serial(), entry.recordId()).orElse(null);
            if (record == null) {
                state.removeJob(entry.id());
            } else if (!record.isDirty() && record.isEmpty()) {
                store.deleteJob(record);
                state.removeJob(entry.id());
            } else if (!record.isDirty()) {
                Jasm.LOGGER.warn("Finished crafting job {} still holds {} items in record #{}; they are kept", entry.id(), record.used(),
                        record.serial());
                state.removeJob(entry.id());
            }
        }
    }

    /** Null means a locked job is unloaded: keep its possible returns until it can be checked. */
    static @Nullable Set<ItemResource> expectedPortReturns(ServerLevel level, AccessPortBlockEntity port) {
        Set<ItemResource> expected = new HashSet<>();
        var state = AutocraftState.get(level.getServer());
        for (UUID id : port.lockedJobs()) {
            var entry = state.job(id).orElse(null);
            if (entry == null || entry.finished()) continue;
            ServerLevel jobLevel = level.getServer().getLevel(entry.server().dimension());
            BlockPos pos = entry.server().pos();
            if (jobLevel == null || !jobLevel.isLoaded(pos) || !(jobLevel.getBlockEntity(pos) instanceof CraftingServerBlockEntity server)
                    || server.job() == null || !server.job().id().equals(id))
                return null;
            for (var sent : server.job().sent) {
                if (sent.port.equals(port.getBlockPos()) && port.lock(sent.side) != null) {
                    sent.waiting.forEach(amount -> expected.add(amount.item()));
                }
            }
        }
        return expected;
    }
}
