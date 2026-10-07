package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.pool.PoolAccess;
import dev.micolash.jasm.storage.WaferRecord;
import dev.micolash.jasm.storage.WaferStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Keeps each open Deck screen up to date. Once per tick, for every player with a Deck open, it sends the full
 * contents the first time, then only counts that changed, plus the charge and wafer status when those change.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class DeckViewTracker {
    static final int PAGE_SIZE = 512;

    /** What was last sent for one open menu. */
    private static final class Sent {
        Map<ItemResource, Long> contents;
        Map<FluidResource, Long> fluids;
        Map<MaterialKey, Long> materials;
        Map<ItemResource, Long> chest = Map.of();
        Map<FluidResource, Long> fluidChest = Map.of();
        long poolVersion = -1;
        long poolCheck;
        boolean hadPool;
        Set<UUID> waferIds = Set.of();
        DeckWafers wafers;
        int energy = -1;
        List<DeckStorage.SlotStatus> slots;
        boolean dirty = true;
    }

    private static final Map<DeckMenu, Sent> SENT = new WeakHashMap<>();
    private static WaferStore listeningTo;

    private DeckViewTracker() {}

    /** Forces a recount for this menu at the end of the tick. */
    public static void markDirty(DeckMenu menu) {
        Sent sent = SENT.get(menu);
        if (sent != null) {
            sent.dirty = true;
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        WaferStore store = WaferStore.ifOpen(server);
        if (store == null) {
            return;
        }
        if (listeningTo != store) {
            store.addListener(waferId -> SENT.values().forEach(s -> {
                if (s.waferIds.contains(waferId)) {
                    s.dirty = true;
                }
            }));
            listeningTo = store;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof DeckMenu menu && menu.stillValid(player)) {
                sync(store, player, menu);
            }
        }
    }

    /** Sends whatever changed for one open menu. */
    public static void sync(WaferStore store, ServerPlayer player, DeckMenu menu) {
        Sent sent = SENT.computeIfAbsent(menu, m -> new Sent());
        DeckWafers wafers = DeckItem.wafers(menu.deck());
        if (!wafers.equals(sent.wafers)) {
            sent.dirty = true;
        }
        long tick = player.level().getServer().getTickCount();
        if (tick - sent.poolCheck >= Tuning.POOL_SNAPSHOT_TICKS) {
            sent.poolCheck = tick;
            NetworkPool pool = PoolAccess.forDeck(player, menu.deck());
            if (pool != null) pool.contents();   // lists the blocks again if the listing is old, which bumps the version
            long version = pool == null ? -1 : pool.version();
            if (version != sent.poolVersion || (pool != null) != sent.hadPool) {
                sent.poolVersion = version;
                sent.hadPool = pool != null;
                sent.dirty = true;
            }
        }
        if (sent.dirty) {
            sent.dirty = false;
            sent.wafers = wafers;
            List<WaferRecord> records = DeckStorage.records(store, menu.deck()).stream().filter(Objects::nonNull).toList();
            Set<UUID> ids = new HashSet<>();
            Map<ItemResource, Long> contents = new HashMap<>();
            Map<FluidResource, Long> fluids = new HashMap<>();
            for (WaferRecord record : records) {
                ids.add(record.id());
                record.contents().forEach((key, count) -> contents.merge(key, count, Long::sum));
                record.fluids().forEach((key, amount) -> fluids.merge(key, amount, Long::sum));
            }
            sent.waferIds = ids;
            NetworkPool pool = PoolAccess.forDeck(player, menu.deck());
            Map<ItemResource, long[]> totals = totals(contents, pool == null ? Map.of() : pool.contents());
            if (sent.contents == null) {
                sendSnapshot(player, menu.containerId, totals);
            } else {
                List<DeckPayloads.Entry> changes = new ArrayList<>();
                totals.forEach((key, t) -> {
                    if (!Long.valueOf(t[0]).equals(sent.contents.get(key)) || t[1] != sent.chest.getOrDefault(key, 0L)) {
                        changes.add(new DeckPayloads.Entry(key, t[0], t[1]));
                    }
                });
                sent.contents.keySet().stream().filter(key -> !totals.containsKey(key))
                        .forEach(key -> changes.add(new DeckPayloads.Entry(key, 0)));
                for (int from = 0; from < changes.size(); from += PAGE_SIZE) {
                    PacketDistributor.sendToPlayer(player, new DeckPayloads.Delta(menu.containerId,
                            changes.subList(from, Math.min(changes.size(), from + PAGE_SIZE))));
                }
            }
            sent.contents = new HashMap<>();
            sent.chest = new HashMap<>();
            totals.forEach((key, t) -> {
                sent.contents.put(key, t[0]);
                if (t[1] > 0) sent.chest.put(key, t[1]);
            });
            sendFluids(player, menu.containerId, sent, totals(fluids, pool == null ? Map.of() : pool.fluidContents()));
            sendMaterials(player, menu.containerId, sent, pool == null ? Map.of() : pool.materialContents());
        }
        int energy = DeckItem.energy(menu.deck());
        List<DeckStorage.SlotStatus> slots = DeckStorage.status(store, menu.deck());
        if (energy != sent.energy || !slots.equals(sent.slots)) {
            sent.energy = energy;
            sent.slots = slots;
            PacketDistributor.sendToPlayer(player, new DeckPayloads.Status(menu.containerId, energy, slots));
        }
    }

    /** The first time everything, then only the amounts that changed. Nothing is sent while there are no fluids at all. */
    private static void sendFluids(ServerPlayer player, int containerId, Sent sent, Map<FluidResource, long[]> totals) {
        if (sent.fluids == null) {
            if (!totals.isEmpty()) {
                List<DeckPayloads.FluidEntry> entries = totals.entrySet().stream()
                        .map(e -> new DeckPayloads.FluidEntry(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
                int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                for (int page = 0; page < pages; page++) {
                    PacketDistributor.sendToPlayer(player, new DeckPayloads.FluidSnapshot(containerId, page, pages,
                            entries.subList(page * PAGE_SIZE, Math.min(entries.size(), (page + 1) * PAGE_SIZE))));
                }
            }
        } else {
            List<DeckPayloads.FluidEntry> changes = new ArrayList<>();
            totals.forEach((key, t) -> {
                if (!Long.valueOf(t[0]).equals(sent.fluids.get(key)) || t[1] != sent.fluidChest.getOrDefault(key, 0L)) {
                    changes.add(new DeckPayloads.FluidEntry(key, t[0], t[1]));
                }
            });
            sent.fluids.keySet().stream().filter(key -> !totals.containsKey(key))
                    .forEach(key -> changes.add(new DeckPayloads.FluidEntry(key, 0)));
            for (int from = 0; from < changes.size(); from += PAGE_SIZE) {
                PacketDistributor.sendToPlayer(player, new DeckPayloads.FluidDelta(containerId,
                        changes.subList(from, Math.min(changes.size(), from + PAGE_SIZE))));
            }
        }
        sent.fluids = new HashMap<>();
        sent.fluidChest = new HashMap<>();
        totals.forEach((key, t) -> {
            sent.fluids.put(key, t[0]);
            if (t[1] > 0) sent.fluidChest.put(key, t[1]);
        });
    }

    // like fluids, minus the wafer part: these only ever sit in storage blocks
    private static void sendMaterials(ServerPlayer player, int containerId, Sent sent, Map<MaterialKey, Long> now) {
        if (sent.materials == null) {
            if (!now.isEmpty()) {
                List<DeckPayloads.MaterialEntry> entries = materialChanges(Map.of(), now);
                int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                for (int page = 0; page < pages; page++) {
                    PacketDistributor.sendToPlayer(player, new DeckPayloads.MaterialSnapshot(containerId, page, pages,
                            entries.subList(page * PAGE_SIZE, Math.min(entries.size(), (page + 1) * PAGE_SIZE))));
                }
            }
        } else {
            List<DeckPayloads.MaterialEntry> changes = materialChanges(sent.materials, now);
            for (int from = 0; from < changes.size(); from += PAGE_SIZE) {
                PacketDistributor.sendToPlayer(player, new DeckPayloads.MaterialDelta(containerId,
                        changes.subList(from, Math.min(changes.size(), from + PAGE_SIZE))));
            }
        }
        sent.materials = new HashMap<>(now);
    }

    /** What changed between two listings; 0 means gone. */
    public static List<DeckPayloads.MaterialEntry> materialChanges(Map<MaterialKey, Long> before, Map<MaterialKey, Long> now) {
        List<DeckPayloads.MaterialEntry> changes = new ArrayList<>();
        now.forEach((key, amount) -> {
            if (!amount.equals(before.get(key))) changes.add(new DeckPayloads.MaterialEntry(key, amount));
        });
        before.keySet().stream().filter(key -> !now.containsKey(key)).forEach(key -> changes.add(new DeckPayloads.MaterialEntry(key, 0)));
        return changes;
    }

    /** The total and the part of it held in storage blocks, for each key. */
    public static <K> Map<K, long[]> totals(Map<K, Long> stored, Map<K, Long> chests) {
        Map<K, long[]> out = new HashMap<>();
        stored.forEach((key, count) -> out.put(key, new long[]{count, 0}));
        chests.forEach((key, count) -> out.merge(key, new long[]{count, count}, (a, b) -> new long[]{a[0] + b[0], b[1]}));
        return out;
    }

    private static void sendSnapshot(ServerPlayer player, int containerId, Map<ItemResource, long[]> totals) {
        List<DeckPayloads.Entry> entries = totals.entrySet().stream()
                .map(e -> new DeckPayloads.Entry(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        for (int page = 0; page < pages; page++) {
            List<DeckPayloads.Entry> part = entries.subList(page * PAGE_SIZE, Math.min(entries.size(), (page + 1) * PAGE_SIZE));
            PacketDistributor.sendToPlayer(player, new DeckPayloads.Snapshot(containerId, page, pages, part));
        }
    }
}
