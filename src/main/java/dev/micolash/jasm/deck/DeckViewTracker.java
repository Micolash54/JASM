package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
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
            store.addListener((waferId, key, count) -> SENT.values().forEach(s -> {
                if (s.waferIds.contains(waferId)) {
                    s.dirty = true;
                }
            }));
            listeningTo = store;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof DeckMenu menu && menu.stillValid(player)) {
                DeckStorage.drain(menu.deck());
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
        if (sent.dirty) {
            sent.dirty = false;
            sent.wafers = wafers;
            List<WaferRecord> records = DeckStorage.records(store, menu.deck()).stream().filter(Objects::nonNull).toList();
            Set<UUID> ids = new HashSet<>();
            Map<ItemResource, Long> contents = new HashMap<>();
            for (WaferRecord record : records) {
                ids.add(record.id());
                record.contents().forEach((key, count) -> contents.merge(key, count, Long::sum));
            }
            sent.waferIds = ids;
            if (sent.contents == null) {
                sendSnapshot(player, menu.containerId, contents);
            } else {
                List<DeckPayloads.Entry> changes = new ArrayList<>();
                contents.forEach((key, count) -> {
                    if (!count.equals(sent.contents.get(key))) {
                        changes.add(new DeckPayloads.Entry(key, count));
                    }
                });
                sent.contents.keySet().stream().filter(key -> !contents.containsKey(key))
                        .forEach(key -> changes.add(new DeckPayloads.Entry(key, 0)));
                for (int from = 0; from < changes.size(); from += PAGE_SIZE) {
                    PacketDistributor.sendToPlayer(player, new DeckPayloads.Delta(menu.containerId,
                            changes.subList(from, Math.min(changes.size(), from + PAGE_SIZE))));
                }
            }
            sent.contents = contents;
        }
        int energy = DeckItem.energy(menu.deck());
        List<DeckStorage.SlotStatus> slots = DeckStorage.status(store, menu.deck());
        if (energy != sent.energy || !slots.equals(sent.slots)) {
            sent.energy = energy;
            sent.slots = slots;
            PacketDistributor.sendToPlayer(player, new DeckPayloads.Status(menu.containerId, energy, slots));
        }
    }

    private static void sendSnapshot(ServerPlayer player, int containerId, Map<ItemResource, Long> contents) {
        List<DeckPayloads.Entry> entries = contents.entrySet().stream().map(e -> new DeckPayloads.Entry(e.getKey(), e.getValue())).toList();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        for (int page = 0; page < pages; page++) {
            List<DeckPayloads.Entry> part = entries.subList(page * PAGE_SIZE, Math.min(entries.size(), (page + 1) * PAGE_SIZE));
            PacketDistributor.sendToPlayer(player, new DeckPayloads.Snapshot(containerId, page, pages, part));
        }
    }
}
