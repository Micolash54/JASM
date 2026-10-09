package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineView;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmMenus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Crafting Server's menu: four Processor slots and four Storage Module slots in a side panel, what the job makes,
 * then the player's inventory (27) and hotbar (9). Parts are locked in while a job runs.
 *
 * <p>A Crafting Deck's job list can open it from afar ({@link #remote}); it then stays open while the player carries
 * that Deck, and has a button back to it.
 */
public class CraftingServerMenu extends AbstractContainerMenu implements Notices.Board, MachineView {
    /** The side panel holds the parts: Processors in the left column, Storage Modules in the right. */
    public static final int SIDE_WIDTH = 50;
    public static final int SIDE_HEIGHT = 86;
    public static final int PARTS_X = 8;
    public static final int PARTS_Y = 8;
    /** The main panel, right of the side panel. */
    /** The main panel joins the side panel, overlapping it a little so the two read as one. */
    public static final int MAIN_X = SIDE_WIDTH - 3;
    public static final int MAIN_WIDTH = 176;
    public static final int JOB_X = MAIN_X + 12;
    public static final int JOB_Y = 24;
    public static final int INVENTORY_Y = 162;
    /** Ticks between checks of what is crafting right now; a change is sent at the next check. */
    private static final int NOW_EVERY = 5;

    public static final int SLOT_SHOWN = CraftingServerBlockEntity.SLOTS;
    public static final int SLOT_INVENTORY = SLOT_SHOWN + 1;

    public static final int BUTTON_CANCEL = 0;
    public static final int BUTTON_COLLECT = 1;
    public static final int BUTTON_BACK = 2;

    static final int DATA_ENERGY_LOW = 0;
    static final int DATA_ENERGY_HIGH = 1;
    static final int DATA_RUNNING = 2;
    static final int DATA_PARALLEL = 3;
    static final int DATA_MEMORY_LOW = 4;
    static final int DATA_MEMORY_HIGH = 5;
    static final int DATA_PHASE = 6;
    static final int DATA_PROGRESS = 7;
    static final int DATA_ACTIVE = 8;
    static final int DATA_PAUSE = 9;
    static final int DATA_AMOUNT_LOW = 10;
    static final int DATA_AMOUNT_HIGH = 11;
    static final int DATA_COUNT = 12;

    private static final Identifier EMPTY_PROCESSOR = Jasm.id("container/empty_processor");
    private static final Identifier EMPTY_MODULE = Jasm.id("container/empty_module");

    private final Notices.Shown notices = new Notices.Shown();
    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final @Nullable CraftingServerBlockEntity server;
    /** Opened from a Crafting Deck: its identity and inventory slot. Null when opened at the block. */
    private final @Nullable UUID deckId;
    private final int deckSlot;
    /** Whether this was opened from a Deck, and whether the viewer owns the current job. */
    private final ContainerData remoteData;
    private final Player viewer;
    /** Processor slots come first, then the same number of rows' worth of Storage Module slots. */
    private final int processors;
    private final int slotShown;
    /** FE the server holds when full. */
    private final int capacity;
    /** What the job waits on at a machine: on the server the line last sent, on the client the last received. */
    private @Nullable Component waiting;
    /** What is crafting right now: on the server the list last sent (null before the first), on the client the last received. */
    private @Nullable List<CraftingJob.Now> now;
    private int nowTicks;

    /** Server side, at the block. */
    public CraftingServerMenu(int containerId, Inventory inventory, CraftingServerBlockEntity server, ContainerLevelAccess access) {
        this(server.menuType(), containerId, inventory, server, server.shown(), server.data(), access, server, null, -1,
                server.processorSlots(), server.memorySlots(), server.capacity());
    }

    /** Server side, from the Crafting Deck in {@code deckSlot}. */
    public static CraftingServerMenu remote(int containerId, Inventory inventory, CraftingServerBlockEntity server, UUID deckId, int deckSlot) {
        return new CraftingServerMenu(server.menuType(), containerId, inventory, server, server.shown(), server.data(), ContainerLevelAccess.NULL,
                server, deckId, deckSlot, server.processorSlots(), server.memorySlots(), server.capacity());
    }

    /** Client side. */
    public CraftingServerMenu(int containerId, Inventory inventory) {
        this(JasmMenus.CRAFTING_SERVER.get(), containerId, inventory, CraftingServerBlockEntity.PROCESSOR_SLOTS,
                CraftingServerBlockEntity.MEMORY_SLOTS, CraftingServerBlockEntity.CAPACITY);
    }

    /** Client side, for the Advanced Crafting Server. */
    public static CraftingServerMenu advanced(int containerId, Inventory inventory) {
        return new CraftingServerMenu(JasmMenus.ADVANCED_CRAFTING_SERVER.get(), containerId, inventory,
                AdvancedCraftingServerBlockEntity.PROCESSOR_SLOTS, AdvancedCraftingServerBlockEntity.MEMORY_SLOTS,
                AdvancedCraftingServerBlockEntity.CAPACITY);
    }

    private CraftingServerMenu(MenuType<?> type, int containerId, Inventory inventory, int processors, int modules, int capacity) {
        this(type, containerId, inventory, new SimpleContainer(processors + modules) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return CraftingServerBlockEntity.accepts(slot, stack, processors);
            }
        }, new SimpleContainer(1), new SimpleContainerData(DATA_COUNT), ContainerLevelAccess.NULL, null, null, -1, processors, modules, capacity);
    }

    private CraftingServerMenu(MenuType<?> type, int containerId, Inventory inventory, Container parts, Container shown, ContainerData data,
            ContainerLevelAccess access, @Nullable CraftingServerBlockEntity server, @Nullable UUID deckId, int deckSlot, int processors,
            int modules, int capacity) {
        super(type, containerId);
        this.processors = processors;
        this.slotShown = processors + modules;
        this.capacity = capacity;
        this.data = data;
        this.access = access;
        this.server = server;
        this.deckId = deckId;
        this.deckSlot = deckSlot;
        this.remoteData = new SimpleContainerData(2);
        this.viewer = inventory.player;
        remoteData.set(0, deckId != null ? 1 : 0);
        remoteData.set(1, server != null && server.job() != null && server.job().requester().equals(viewer.getUUID()) ? 1 : 0);
        for (int i = 0; i < processors; i++) {
            addSlot(new PartSlot(this, parts, i, PARTS_X, PARTS_Y + i * 18, EMPTY_PROCESSOR));
        }
        for (int i = 0; i < modules; i++) {
            addSlot(new PartSlot(this, parts, processors + i, PARTS_X + 18, PARTS_Y + i * 18, EMPTY_MODULE));
        }
        addSlot(new EncodingTerminalMenu.FakeSlot(shown, 0, JOB_X, JOB_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, MAIN_X + 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, MAIN_X + 8 + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
        addDataSlots(remoteData);
    }

    /** Sends the line saying what the job waits on at a machine whenever it changes. */
    @Override
    public void broadcastChanges() {
        if (server != null) {
            remoteData.set(1, server.job() != null && server.job().requester().equals(viewer.getUUID()) ? 1 : 0);
        }
        super.broadcastChanges();
        if (server == null || !(viewer instanceof ServerPlayer player)) {
            return;
        }
        CraftingJob job = server.job();
        Component line = job == null || job.waiting() == null ? null : job.waiting().line();
        if (!Objects.equals(line, waiting)) {
            waiting = line;
            if (player.connection.hasChannel(CraftPayloads.ServerWaiting.TYPE)) {
                PacketDistributor.sendToPlayer(player,
                        new CraftPayloads.ServerWaiting(containerId, Optional.ofNullable(line)));
            }
        }
        if (now == null || --nowTicks <= 0) {
            nowTicks = NOW_EVERY;
            List<CraftingJob.Now> rows = job == null ? List.of() : job.now();
            if (!rows.equals(now)) {
                now = rows;
                if (player.connection.hasChannel(CraftPayloads.ServerNow.TYPE)) {
                    PacketDistributor.sendToPlayer(player, new CraftPayloads.ServerNow(containerId, rows));
                }
            }
        }
    }

    /** Client side: what the job waits on at a machine, or null. */
    public @Nullable Component waiting() {
        return waiting;
    }

    public void setWaiting(@Nullable Component waiting) {
        this.waiting = waiting;
    }

    /** Client side: what the job is crafting right now, the final item first. */
    public List<CraftingJob.Now> now() {
        return now == null ? List.of() : now;
    }

    public void setNow(List<CraftingJob.Now> now) {
        this.now = now;
    }

    /** The menu slot showing what the job makes; the player's inventory follows it. */
    public int shownSlot() {
        return slotShown;
    }

    /** How tall the parts panel is: one row per Processor. */
    public int sideHeight() {
        return SIDE_HEIGHT + (processors - CraftingServerBlockEntity.PROCESSOR_SLOTS) * 18;
    }

    /** Whether this screen was opened from a Crafting Deck, so it can go back to it. */
    public boolean opensFromDeck() {
        return remoteData.get(0) != 0;
    }

    public boolean canControlJob() {
        return remoteData.get(1) != 0;
    }

    public int energy() {
        return ContainerWords.join(data.get(DATA_ENERGY_HIGH), data.get(DATA_ENERGY_LOW));
    }

    public int capacity() {
        return capacity;
    }

    public boolean running() {
        return data.get(DATA_RUNNING) != 0;
    }

    public int parallel() {
        return data.get(DATA_PARALLEL);
    }

    public int memory() {
        return ContainerWords.join(data.get(DATA_MEMORY_HIGH), data.get(DATA_MEMORY_LOW));
    }

    /** Null while idle. */
    public CraftingJob.@Nullable Phase phase() {
        int phase = data.get(DATA_PHASE);
        return phase <= 0 ? null : CraftingJob.Phase.values()[Math.min(phase - 1, CraftingJob.Phase.values().length - 1)];
    }

    /** How many the job makes. */
    public int amount() {
        return ContainerWords.join(data.get(DATA_AMOUNT_HIGH), data.get(DATA_AMOUNT_LOW));
    }

    public float progress() {
        return data.get(DATA_PROGRESS) / 1000F;
    }

    public int active() {
        return data.get(DATA_ACTIVE);
    }

    /** 0 when nothing is holding the job up; see {@link PauseReason}. */
    public PauseReason pause() {
        return PauseReason.of(data.get(DATA_PAUSE));
    }

    public boolean busy() {
        return phase() != null;
    }

    @Override
    public boolean stillValid(Player player) {
        if (server == null) {
            return true;
        }
        boolean reachable = deckId == null
                ? stillValid(access, player, server.getBlockState().getBlock())
                : player instanceof ServerPlayer serverPlayer && carriesDeck(serverPlayer) && server.getLevel() != null
                        && server.getLevel().isLoaded(server.getBlockPos());
        return !server.isRemoved() && reachable
                && (MachineAccess.canUse(server, player) || server.job() != null && server.job().requester().equals(player.getUUID()));
    }

    private boolean carriesDeck(ServerPlayer player) {
        ItemStack deck = player.getInventory().getItem(deckSlot);
        return deckId != null && DeckItem.isCrafting(deck) && deckId.equals(deck.get(JasmComponents.DECK_ID.get()))
                && DeckItem.worksIn(deck, player.level()) && DeckItem.worksIn(deck, server.getLevel());
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (slotIndex == slotShown) {
            return;
        }
        super.clicked(slotIndex, buttonNum, input, player);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (server == null || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (id == BUTTON_CANCEL) {
            if (Jobs.cancel(serverPlayer, server)) {
                Notices.good(serverPlayer, Component.translatable("message.jasm.craft.cancelling"));
            }
            return true;
        }
        if (id == BUTTON_COLLECT) {
            long moved = Jobs.collect(serverPlayer, server);
            Notices.tell(serverPlayer, moved > 0
                    ? Component.translatable("message.jasm.craft.collected", String.format("%,d", moved))
                    : Component.translatable("message.jasm.craft.nothing_to_collect"), moved > 0);
            return true;
        }
        if (id == BUTTON_BACK && carriesDeck(serverPlayer)) {
            DeckItem.open(serverPlayer, deckSlot);
            return true;
        }
        return false;
    }

    /** Shift-click: parts go to their slots; out of the server, to the hotbar first, then the inventory. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot clicked = slots.get(index);
        if (!clicked.hasItem() || !clicked.mayPickup(player) || clicked instanceof EncodingTerminalMenu.FakeSlot) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = clicked.getItem();
        ItemStack before = stack.copy();
        int inventory = slotShown + 1;
        int hotbar = inventory + 27;
        boolean moved;
        if (index < slotShown) {
            moved = moveItemStackTo(stack, hotbar, hotbar + 9, false) || moveItemStackTo(stack, inventory, hotbar, false);
        } else if (ServerPartItem.processorOf(stack) != null) {
            moved = moveItemStackTo(stack, 0, processors, false);
        } else if (ServerPartItem.memoryOf(stack) != null) {
            moved = moveItemStackTo(stack, processors, slotShown, false);
        } else {
            moved = false;
        }
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

    /** A Processor or Storage Module slot. Locked while the server has a job. */
    private static final class PartSlot extends Slot {
        private final CraftingServerMenu menu;
        private final Identifier icon;

        PartSlot(CraftingServerMenu menu, Container container, int index, int x, int y, Identifier icon) {
            super(container, index, x, y);
            this.menu = menu;
            this.icon = icon;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return CraftingServerBlockEntity.accepts(getContainerSlot(), stack, menu.processors) && !menu.busy();
        }

        @Override
        public boolean mayPickup(Player player) {
            return !menu.busy();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public Identifier getNoItemIcon() {
            return icon;
        }
    }

    /** Client side: the last message for this screen. */
    @Override
    public Notices.Shown notices() {
        return notices;
    }

    @Override
    public @Nullable BlockPos machinePos() {
        return server == null ? null : server.getBlockPos();
    }
}
