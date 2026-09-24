package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.storage.WaferStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Registers the Deck messages and handles the ones the screen sends. The server trusts nothing in them: it
 * checks the menu is really open and valid, re-checks the wafers, and uses only amounts it can actually move.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class DeckNetwork {
    /** Operations handled so far this tick, per player. */
    private static final Map<UUID, long[]> RATE = new HashMap<>();

    private DeckNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(DeckPayloads.Extract.TYPE, DeckPayloads.Extract.STREAM_CODEC,
                        (payload, context) -> extract((ServerPlayer) context.player(), payload))
                .playToServer(DeckPayloads.Insert.TYPE, DeckPayloads.Insert.STREAM_CODEC,
                        (payload, context) -> insert((ServerPlayer) context.player(), payload))
                .playToClient(DeckPayloads.Snapshot.TYPE, DeckPayloads.Snapshot.STREAM_CODEC, DeckNetwork::onSnapshot)
                .playToClient(DeckPayloads.Delta.TYPE, DeckPayloads.Delta.STREAM_CODEC, DeckNetwork::onDelta)
                .playToClient(DeckPayloads.Status.TYPE, DeckPayloads.Status.STREAM_CODEC, DeckNetwork::onStatus);
    }

    // --- server ---

    /** Takes items out onto the cursor or into the inventory. Returns the amount moved. */
    public static long extract(ServerPlayer player, DeckPayloads.Extract payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null || !allow(player)) {
            return 0;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        ItemResource key = payload.key();
        if (key.isEmpty()) {
            return 0;
        }
        int max = key.getMaxStackSize();
        menu.wafers().flush();
        DeckStorage.checkAll(store, menu.deck(), player);
        long available = DeckStorage.count(store, menu.deck(), key);
        long wanted = switch (payload.mode()) {
            case STACK -> Math.min(max, cursorRoom(menu, key));
            case HALF -> Math.min(cursorRoom(menu, key), (Math.min(available, max) + 1) / 2);
            case TO_INVENTORY -> Math.min(max, inventoryRoom(player.getInventory(), key));
        };
        long moved = 0;
        if (wanted > 0 && available > 0) {
            for (ItemStack stack : DeckStorage.withdraw(store, menu.deck(), key, Math.min(wanted, available), player)) {
                moved += stack.getCount();
                if (payload.mode() == DeckPayloads.ExtractMode.TO_INVENTORY) {
                    // Room was counted first; anything that still does not fit is dropped at the player's feet, never lost.
                    player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
                } else if (menu.getCarried().isEmpty()) {
                    menu.setCarried(stack);
                } else {
                    menu.getCarried().grow(stack.getCount());
                }
            }
        }
        finish(player, menu);
        return moved;
    }

    /** Stores the cursor stack (or one item of it). Returns the amount stored. */
    public static long insert(ServerPlayer player, DeckPayloads.Insert payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null || !allow(player) || menu.getCarried().isEmpty()) {
            return 0;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        menu.wafers().flush();
        ItemStack carried = menu.getCarried();
        long moved;
        if (payload.one()) {
            ItemStack single = carried.copyWithCount(1);
            moved = DeckStorage.deposit(store, menu.deck(), single, player);
            carried.shrink((int) moved);
        } else {
            moved = DeckStorage.deposit(store, menu.deck(), carried, player);
        }
        menu.setCarried(carried);
        finish(player, menu);
        return moved;
    }

    private static void finish(ServerPlayer player, DeckMenu menu) {
        menu.wafers().reload();
        menu.broadcastChanges();
        DeckViewTracker.markDirty(menu);
    }

    private static @Nullable DeckMenu openMenu(ServerPlayer player, int containerId) {
        if (player.containerMenu instanceof DeckMenu menu && menu.containerId == containerId && menu.stillValid(player)) {
            return menu;
        }
        return null;
    }

    /** Space for {@code key} on the cursor: all of a stack if empty, the rest of it if it holds the same item, else none. */
    private static long cursorRoom(DeckMenu menu, ItemResource key) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return key.getMaxStackSize();
        }
        return key.matches(carried) ? Math.max(0, carried.getMaxStackSize() - carried.getCount()) : 0;
    }

    /** How many of {@code key} fit into the main inventory and hotbar. */
    private static long inventoryRoom(Inventory inventory, ItemResource key) {
        long room = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                room += key.getMaxStackSize();
            } else if (key.matches(stack)) {
                room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return room;
    }

    /** At most {@code maxOpsPerTick} operations per player per tick; the rest are dropped. */
    private static boolean allow(ServerPlayer player) {
        long tick = player.level().getServer().getTickCount();
        long[] seen = RATE.computeIfAbsent(player.getUUID(), id -> new long[] {tick, 0});
        if (seen[0] != tick) {
            seen[0] = tick;
            seen[1] = 0;
        }
        return ++seen[1] <= JasmConfig.DECK_MAX_OPS_PER_TICK.getAsInt();
    }

    // --- client ---

    private static @Nullable DeckView view(IPayloadContext context, int containerId) {
        if (context.player().containerMenu instanceof DeckMenu menu && menu.containerId == containerId) {
            return menu.view();
        }
        return null;
    }

    private static void onSnapshot(DeckPayloads.Snapshot payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.applySnapshotPage(payload.page(), payload.entries());
        }
    }

    private static void onDelta(DeckPayloads.Delta payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.apply(payload.entries());
        }
    }

    private static void onStatus(DeckPayloads.Status payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.applyStatus(payload.energy(), payload.slots());
        }
    }
}
