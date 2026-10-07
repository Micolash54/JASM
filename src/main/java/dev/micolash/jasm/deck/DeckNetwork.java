package dev.micolash.jasm.deck;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.JasmServerData;
import dev.micolash.jasm.storage.WaferStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
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
    private DeckNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(DeckPayloads.Extract.TYPE, DeckPayloads.Extract.STREAM_CODEC,
                        (payload, context) -> extract((ServerPlayer) context.player(), payload))
                .playToServer(DeckPayloads.Insert.TYPE, DeckPayloads.Insert.STREAM_CODEC,
                        (payload, context) -> insert((ServerPlayer) context.player(), payload))
                .playToServer(DeckPayloads.Configure.TYPE, DeckPayloads.Configure.STREAM_CODEC,
                        (payload, context) -> configure((ServerPlayer) context.player(), payload))
                .playToServer(DeckPayloads.ClearGrid.TYPE, DeckPayloads.ClearGrid.STREAM_CODEC,
                        (payload, context) -> clearGrid((ServerPlayer) context.player(), payload))
                .playToServer(DeckPayloads.FillGrid.TYPE, DeckPayloads.FillGrid.STREAM_CODEC,
                        (payload, context) -> fillGrid((ServerPlayer) context.player(), payload))
                .playToServer(DeckPayloads.FluidAction.TYPE, DeckPayloads.FluidAction.STREAM_CODEC,
                        (payload, context) -> fluidAction((ServerPlayer) context.player(), payload))
                .playToServer(DeckPayloads.PourSlot.TYPE, DeckPayloads.PourSlot.STREAM_CODEC,
                        (payload, context) -> pourSlot((ServerPlayer) context.player(), payload))
                .playToServer(DeckPayloads.EmptyWafer.TYPE, DeckPayloads.EmptyWafer.STREAM_CODEC,
                        (payload, context) -> emptyWafer((ServerPlayer) context.player(), payload))
                .playToClient(DeckPayloads.EmptyProgress.TYPE, DeckPayloads.EmptyProgress.STREAM_CODEC, DeckNetwork::onEmptyProgress)
                .playToClient(DeckPayloads.Emptied.TYPE, DeckPayloads.Emptied.STREAM_CODEC, DeckNetwork::onEmptied)
                .playToClient(DeckPayloads.FluidSnapshot.TYPE, DeckPayloads.FluidSnapshot.STREAM_CODEC, DeckNetwork::onFluidSnapshot)
                .playToClient(DeckPayloads.FluidDelta.TYPE, DeckPayloads.FluidDelta.STREAM_CODEC, DeckNetwork::onFluidDelta)
                .playToClient(DeckPayloads.MaterialSnapshot.TYPE, DeckPayloads.MaterialSnapshot.STREAM_CODEC, DeckNetwork::onMaterialSnapshot)
                .playToClient(DeckPayloads.MaterialDelta.TYPE, DeckPayloads.MaterialDelta.STREAM_CODEC, DeckNetwork::onMaterialDelta)
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
        var storage = DeckStorage.checked(store, menu.deck(), player);
        long available = storage.count(key);
        long wanted = switch (payload.mode()) {
            case STACK -> Math.min(max, cursorRoom(menu, key));
            case HALF -> Math.min(cursorRoom(menu, key), (Math.min(available, max) + 1) / 2);
            case TO_INVENTORY -> Math.min(max, inventoryRoom(player.getInventory(), key));
        };
        long moved = 0;
        if (wanted > 0 && available > 0) {
            for (ItemStack stack : storage.withdraw(key, Math.min(wanted, available))) {
                moved += stack.getCount();
                if (payload.mode() == DeckPayloads.ExtractMode.TO_INVENTORY) {
                    // Room was counted first; anything that still does not fit is dropped at the player's feet, never lost.
                    player.getInventory().placeItemBackInInventory(stack);
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
        ItemStack carried = menu.getCarried();
        long moved;
        if (payload.one()) {
            ItemStack single = carried.copyWithCount(1);
            moved = DeckStorage.deposit(store, menu.deck(), single, player, DeckStorage.Excess.VOID);
            carried.shrink((int) moved);
        } else {
            moved = DeckStorage.deposit(store, menu.deck(), carried, player, DeckStorage.Excess.VOID);
        }
        menu.setCarried(carried);
        finish(player, menu);
        return moved;
    }

    /** Starts emptying one wafer: the menu moves a share of it every tick. False if nothing started. */
    public static boolean emptyWafer(ServerPlayer player, DeckPayloads.EmptyWafer payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null || !allow(player) || payload.slot() < 0 || payload.slot() >= menu.waferSlots() || menu.emptying()) {
            return false;
        }
        WaferEmptying.Plan plan = WaferEmptying.plan(WaferStore.get(player.level().getServer()), menu.deck(), payload.slot(), player);
        if (plan == null) {
            send(player, new DeckPayloads.Emptied(menu.containerId, payload.slot(), WaferEmptying.Result.NOTHING));
            return false;
        }
        menu.startEmptying(payload.slot(), plan.total());
        send(player, new DeckPayloads.EmptyProgress(menu.containerId, payload.slot(), 0, plan.total(), plan.fluid()));
        return true;
    }

    /** One tick of an emptying: moves a share, then reports the bar or, when it can go no further, the result. */
    static void stepEmptying(ServerPlayer player, DeckMenu menu) {
        int slot = menu.emptyingSlot();
        long total = menu.emptyingTotal();
        WaferStore store = WaferStore.get(player.level().getServer());
        long share = Math.max(1, (total + WaferEmptying.STEPS - 1) / WaferEmptying.STEPS);
        WaferEmptying.Result step = WaferEmptying.empty(store, menu.deck(), slot, player, share);
        long moved = menu.addEmptied(step.moved());
        DeckViewTracker.markDirty(menu);
        if (step.moved() > 0 && step.left() > 0) {
            send(player, new DeckPayloads.EmptyProgress(menu.containerId, slot, moved, Math.max(total, moved), step.fluid()));
            return;
        }
        menu.stopEmptying();
        WaferEmptying.Outcome outcome = moved == 0 && step.left() == 0 ? WaferEmptying.Outcome.NOTHING
                : step.left() == 0 ? WaferEmptying.Outcome.DONE : step.outcome();
        send(player, new DeckPayloads.Emptied(menu.containerId, slot, new WaferEmptying.Result(moved, step.left(), outcome, step.fluid())));
    }

    /** Sends to the player, unless it is a test player with no connection to the client. */
    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void send(ServerPlayer player, T payload) {
        if (player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** Pours a container sitting in the player's own inventory into the Deck. Returns the millibuckets moved. */
    public static long pourSlot(ServerPlayer player, DeckPayloads.PourSlot payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null || payload.slot() < 0 || payload.slot() >= menu.slots.size() || !allow(player)) {
            return 0;
        }
        Slot slot = menu.slots.get(payload.slot());
        if (slot.container != player.getInventory()) {
            return 0;
        }
        var container = ItemAccess.forPlayerSlot(player, slot.getContainerSlot()).getCapability(Capabilities.Fluid.ITEM);
        if (container == null) {
            return 0;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        long moved = DeckFluids.pour(player, menu, DeckStorage.checked(store, menu.deck(), player), container, true);
        finish(player, menu);
        return moved;
    }

    /** Fills or empties the container on the cursor. Returns the millibuckets moved. */
    public static long fluidAction(ServerPlayer player, DeckPayloads.FluidAction payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null || !allow(player)) {
            return 0;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        var storage = DeckStorage.checked(store, menu.deck(), player);
        DeckPayloads.FluidMode mode = payload.mode();
        long moved = switch (mode) {
            case FILL -> DeckFluids.fill(player, menu, storage, payload.key(), false, false);
            case FILL_ALL -> DeckFluids.fill(player, menu, storage, payload.key(), true, false);
            case FILL_TO_INVENTORY -> DeckFluids.fill(player, menu, storage, payload.key(), false, true);
            case FILL_ALL_TO_INVENTORY -> DeckFluids.fill(player, menu, storage, payload.key(), true, true);
            case EMPTY -> DeckFluids.empty(player, menu, storage, false);
            case EMPTY_ALL -> DeckFluids.empty(player, menu, storage, true);
        };
        finish(player, menu);
        return moved;
    }

    /** Changes one wafer's routing settings. The settings clean themselves up; the slot must hold a usable wafer. */
    public static boolean configure(ServerPlayer player, DeckPayloads.Configure payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null || !allow(player) || payload.slot() < 0 || payload.slot() >= menu.waferSlots()) {
            return false;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        boolean done = DeckStorage.configure(store, menu.deck(), payload.slot(), payload.settings(), player);
        finish(player, menu);
        return done;
    }

    /** Empties a Crafting Deck's grid onto its wafers or into the inventory. */
    public static boolean clearGrid(ServerPlayer player, DeckPayloads.ClearGrid payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null || !menu.isCrafting() || !menu.dimensionAllowed() || !allow(player)) {
            return false;
        }
        if (payload.toInventory()) menu.gridToInventory(player);
        else menu.returnGrid(player);
        finish(player, menu);
        return true;
    }

    /** Fills a Crafting Deck's grid for a recipe, from its wafers and the player's inventory. */
    public static boolean fillGrid(ServerPlayer player, DeckPayloads.FillGrid payload) {
        DeckMenu menu = openMenu(player, payload.containerId());
        if (menu == null || !menu.isCrafting() || !menu.dimensionAllowed() || !allow(player)) {
            return false;
        }
        menu.fillGrid(player, payload.slots(), payload.max());
        finish(player, menu);
        return true;
    }

    private static void finish(ServerPlayer player, DeckMenu menu) {
        menu.broadcastChanges();
        DeckViewTracker.markDirty(menu);
    }

    public static @Nullable DeckMenu openMenu(ServerPlayer player, int containerId) {
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
    public static boolean allow(ServerPlayer player) {
        long tick = player.level().getServer().getTickCount();
        long[] seen = JasmServerData.of(player.level().getServer()).deckOps.computeIfAbsent(player.getUUID(), id -> new long[]{tick, 0});
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

    private static void onEmptyProgress(DeckPayloads.EmptyProgress payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.setEmptying(payload);
        }
    }

    private static void onEmptied(DeckPayloads.Emptied payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.setEmptied(payload);
        }
    }

    private static void onFluidSnapshot(DeckPayloads.FluidSnapshot payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.applyFluidSnapshotPage(payload.page(), payload.entries());
        }
    }

    private static void onMaterialSnapshot(DeckPayloads.MaterialSnapshot payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.applyMaterialSnapshotPage(payload.page(), payload.entries());
        }
    }

    private static void onMaterialDelta(DeckPayloads.MaterialDelta payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.applyMaterials(payload.entries());
        }
    }

    private static void onFluidDelta(DeckPayloads.FluidDelta payload, IPayloadContext context) {
        DeckView view = view(context, payload.containerId());
        if (view != null) {
            view.applyFluids(payload.entries());
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
