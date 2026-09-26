package dev.micolash.jasm.archive;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.storage.ArchiveRecord;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Archive's menu: a link slot and a recovery slot, then the player's inventory (27 slots) and hotbar (9). The
 * two wafer slots are the player's own at this Archive (see {@link ArchiveBlockEntity#slotsOf}); whatever is in them
 * goes back to the player on close, and stays in the Archive if the player logs out with the screen open.
 */
public class ArchiveMenu extends AbstractContainerMenu implements Notices.Board {
    public static final int LINK_SLOT = 0;
    private static final Identifier EMPTY_LINK = Jasm.id("container/empty_link");
    private static final Identifier EMPTY_BLANK = Jasm.id("container/empty_blank");
    public static final int RECOVERY_SLOT = 1;
    public static final int ROW_Y = 122;
    public static final int LINK_X = 8;
    public static final int RECOVERY_X = 118;
    public static final int INVENTORY_Y = 152;
    /** How often, in ticks, the server rebuilds the linked list to see whether it changed. */
    private static final int REFRESH_TICKS = 20;
    /** How often, in ticks, a changed charge is sent on its own. */
    private static final int ENERGY_TICKS = 5;

    private final Notices.Shown notices = new Notices.Shown();
    private final Player player;
    private final ArchiveTier tier;
    private final ContainerLevelAccess access;
    private final @Nullable ArchiveBlockEntity archive;
    private final Container waferSlots;
    private ArchivePayloads.State lastSent = ArchivePayloads.State.EMPTY;
    private int sinceRefresh = REFRESH_TICKS;
    /** Client side only: what the server has told this screen. */
    private ArchivePayloads.State view = ArchivePayloads.State.EMPTY;

    /** Server side. */
    public ArchiveMenu(int containerId, Inventory inventory, ArchiveBlockEntity archive) {
        this(containerId, inventory, archive.tier(), ContainerLevelAccess.create(archive.getLevel(), archive.getBlockPos()), archive);
    }

    /** Client side: the server tells where the Archive stands. */
    public static ArchiveMenu client(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        BlockPos pos = data.readBlockPos();
        ArchiveTier tier = inventory.player.level().getBlockState(pos).getBlock() instanceof ArchiveBlock block ? block.tier() : ArchiveTier.BASIC;
        return new ArchiveMenu(containerId, inventory, tier, ContainerLevelAccess.NULL, null);
    }

    private ArchiveMenu(int containerId, Inventory inventory, ArchiveTier tier, ContainerLevelAccess access, @Nullable ArchiveBlockEntity archive) {
        super(JasmMenus.ARCHIVE.get(), containerId);
        this.player = inventory.player;
        this.tier = tier;
        this.access = access;
        this.archive = archive;
        this.waferSlots = archive != null ? archive.slotsOf(player.getUUID()) : new SimpleContainer(2);
        addSlot(new WaferSlot(waferSlots, LINK_SLOT, LINK_X, ROW_Y));
        addSlot(new WaferSlot(waferSlots, RECOVERY_SLOT, RECOVERY_X, ROW_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
    }

    public ArchiveTier tier() {
        return tier;
    }

    public Container waferSlots() {
        return waferSlots;
    }

    public ArchivePayloads.State view() {
        return view;
    }

    public void setView(ArchivePayloads.State view) {
        this.view = view;
    }

    /** Closes if the block is gone, the player walked away, or the player lost access. */
    @Override
    public boolean stillValid(Player player) {
        if (archive == null) {
            return true;
        }
        if (archive.isRemoved() || !stillValid(access, player, archive.getBlockState().getBlock())) {
            return false;
        }
        return MachineAccess.canUse(archive, player);
    }

    /** Runs a button press from the screen. Everything is checked again here; the screen is not trusted. */
    public ArchiveService.Result handle(ServerPlayer player, ArchivePayloads.Request request) {
        if (archive == null) {
            return ArchiveService.Result.NOT_READY;
        }
        WaferStore store = WaferStore.get(player.level().getServer());
        ArchiveService.Result result = switch (request.action()) {
            case LINK -> ArchiveService.link(store, archive, player, waferSlots.getItem(LINK_SLOT));
            case UNLINK -> ArchiveService.unlink(store, archive, player, request.serial());
            case RECOVER -> ArchiveService.recover(store, archive, player, request.serial(), waferSlots.getItem(RECOVERY_SLOT));
        };
        waferSlots.setChanged();
        feedback(player, request.action(), result);
        return result;
    }

    /** Shows the outcome on the screen and sends the updated list right away. */
    private void feedback(ServerPlayer player, ArchivePayloads.Action action, ArchiveService.Result result) {
        String key = result == ArchiveService.Result.OK
                ? "message.jasm.archive.done." + action.name().toLowerCase(java.util.Locale.ROOT)
                : result.messageKey();
        Notices.tell(player, Component.translatable(key), result == ArchiveService.Result.OK);
        sinceRefresh = REFRESH_TICKS;
        broadcastChanges();
    }

    /** Also sends the screen's list and charge: the charge a few times a second when it moves, the list when it has changed. */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (archive != null && player instanceof ServerPlayer serverPlayer) {
            boolean refresh = ++sinceRefresh >= REFRESH_TICKS;
            if (refresh || (sinceRefresh % ENERGY_TICKS == 0 && archive.energy().getAmountAsInt() != lastSent.energy())) {
                ArchivePayloads.State state = refresh ? buildState(serverPlayer) : withEnergy(lastSent, archive.energy().getAmountAsInt());
                if (refresh) {
                    sinceRefresh = 0;
                }
                if (!state.equals(lastSent)) {
                    lastSent = state;
                    // Only players with a real connection to a JASM client can receive it (not, for example, test players).
                    if (serverPlayer.connection.hasChannel(ArchivePayloads.State.TYPE)) {
                        PacketDistributor.sendToPlayer(serverPlayer, state);
                    }
                }
            }
        }
    }

    private ArchivePayloads.State buildState(ServerPlayer player) {
        WaferStore store = WaferStore.get(player.level().getServer());
        ArchiveRecord record = archive.record();
        if (record == null) {
            return withEnergy(ArchivePayloads.State.EMPTY, archive.energy().getAmountAsInt());
        }
        return new ArchivePayloads.State(containerId, archive.energy().getAmountAsInt(), record.tier().registrations(),
                ArchiveService.entries(store, record));
    }

    private ArchivePayloads.State withEnergy(ArchivePayloads.State state, int energy) {
        return new ArchivePayloads.State(containerId, energy, state.registrations(), state.entries());
    }

    /**
     * Shift-click: a wafer from the inventory goes to the link slot, or the recovery slot if that one is taken;
     * a wafer in either slot goes to the leftmost free hotbar slot, then the inventory from its top-left slot.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        int hotbar = 2 + 27;
        boolean moved = index < 2
                ? moveItemStackTo(stack, hotbar, hotbar + 9, false) || moveItemStackTo(stack, 2, hotbar, false)
                : stack.getItem() instanceof WaferItem && moveItemStackTo(stack, 0, 2, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            clicked.setByPlayer(ItemStack.EMPTY);
        } else {
            clicked.setChanged();
        }
        return before;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Logging out: the wafers wait in the Archive rather than dropping where the player stood.
        if (archive != null && !(player instanceof ServerPlayer serverPlayer && serverPlayer.hasDisconnected())) {
            clearContainer(player, waferSlots);
            waferSlots.setChanged();
        }
    }

    private static final class WaferSlot extends Slot {
        WaferSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        /** Shows a faint linked wafer or blank wafer while empty, so each slot says what it is for. */
        @Override
        public Identifier getNoItemIcon() {
            return getContainerSlot() == LINK_SLOT ? EMPTY_LINK : EMPTY_BLANK;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof WaferItem;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** Client side: the last message for this screen. */
    @Override
    public Notices.Shown notices() {
        return notices;
    }
}
