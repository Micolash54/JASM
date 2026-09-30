package dev.micolash.jasm.transfer;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.DeckLinkLayout;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmMenus;
import dev.micolash.jasm.storage.WaferSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class TransferPortMenu extends AbstractContainerMenu {
    public static final int WIDTH = PortUpgradeLayout.MAIN_WIDTH;
    public static final int LINK_IN = PortOperations.UPGRADE_SLOTS;
    public static final int LINK_OUT = LINK_IN + 1;
    public static final int INVENTORY_START = LINK_OUT + 1;
    private final @Nullable TransferPortBlockEntity port;
    private final ContainerData data;
    private final TransferPortKind kind;
    private TransferFilters filters;
    public TransferPortMenu(int id, Inventory inventory, TransferPortBlockEntity port) {
        this(id, inventory, port, port.data(), port.kind(), port.filters());
    }
    public static TransferPortMenu client(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        return new TransferPortMenu(id, inventory, null, new SimpleContainerData(6), buf.readEnum(TransferPortKind.class), TransferFilters.STREAM_CODEC.decode(buf));
    }
    private TransferPortMenu(int id, Inventory inventory, @Nullable TransferPortBlockEntity port, ContainerData data, TransferPortKind kind, TransferFilters filters) {
        super(JasmMenus.TRANSFER_PORT.get(), id);
        this.port = port; this.data = data; this.kind = kind; this.filters = filters;
        addDataSlots(data);
        var contents = port == null ? new SimpleContainer(AccessPortBlockEntity.INVENTORY_SIZE) : port;
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            addSlot(new Slot(contents, AccessPortBlockEntity.SPEED_START + i, PortUpgradeLayout.x(i), PortUpgradeLayout.y(i)) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.is(JasmItems.SPEED_UPGRADE.get()); }
                @Override public int getMaxStackSize() { return 1; }
                @Override public net.minecraft.resources.Identifier getNoItemIcon() { return Jasm.id("container/empty_upgrade"); }
            });
        }
        addSlot(new Slot(contents, AccessPortBlockEntity.DECK_IN, DeckLinkLayout.PORT.slotX(), DeckLinkLayout.INPUT_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return DeckItem.isCrafting(stack); }
            @Override public int getMaxStackSize() { return 1; }
            @Override public net.minecraft.resources.Identifier getNoItemIcon() { return Jasm.id("container/empty_deck"); }
        });
        addSlot(new Slot(contents, AccessPortBlockEntity.DECK_OUT, DeckLinkLayout.PORT.slotX(), DeckLinkLayout.OUTPUT_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        int ix = (WIDTH - 162) / 2;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, 9 + row * 9 + col, ix + col * 18, inventoryY() + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, ix + col * 18, inventoryY() + 58));
    }
    public TransferPortKind kind() { return kind; }
    public TransferFilters filters() { return filters; }
    public int inventoryY() { return kind == TransferPortKind.INPUT_OUTPUT ? 276 : 180; }
    public int height() { return inventoryY() + 84; }
    public boolean defaultDeck() { return data.get(4) != 0; }
    public boolean linked() { return data.get(5) != 0; }
    public boolean running() { return data.get(2) != 0; }
    public int rate() {
        int upgrades = 0;
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) if (getSlot(i).getItem().is(JasmItems.SPEED_UPGRADE.get())) upgrades++;
        return PortOperations.itemsPerOperation(upgrades);
    }
    public boolean canReset() { return !defaultDeck() && getSlot(LINK_IN).getItem().isEmpty(); }
    public boolean configure(boolean output, WaferSettings settings) {
        if (output ? !kind.exports() : !kind.imports()) return false;
        WaferSettings previous = output ? filters.output() : filters.input();
        if (settings.rules().stream().anyMatch(rule -> !rule.valid() && previous.rules().stream().noneMatch(old -> old.mode() == rule.mode() && old.value().equals(rule.value())))) return false;
        filters = output ? new TransferFilters(filters.input(), settings) : new TransferFilters(settings, filters.output());
        if (port != null) port.setFilters(filters);
        return true;
    }
    @Override public boolean stillValid(Player player) {
        return port == null || port.installed() && MachineAccess.canUse(port, player) && player.distanceToSqr(Vec3.atCenterOf(port.getBlockPos())) <= 64;
    }
    @Override public boolean clickMenuButton(Player player, int id) { return id == 0 && port != null && stillValid(player) && port.resetDeck(player); }
    @Override public void clicked(int index, int button, ContainerInput input, Player player) {
        ItemStack before = getSlot(LINK_IN).getItem().copy();
        super.clicked(index, button, input, player);
        if (port != null) { if (!ItemStack.matches(before, getSlot(LINK_IN).getItem())) port.queueDeckLink(player); port.processDeckLink(); }
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        var slot = slots.get(index); if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem(); var before = stack.copy();
        if (index < INVENTORY_START) { if (!moveItemStackTo(stack, INVENTORY_START, slots.size(), true)) return ItemStack.EMPTY; }
        else if (stack.is(JasmItems.SPEED_UPGRADE.get())) {
            boolean moved = false;
            for (int i = 0; i < PortOperations.UPGRADE_SLOTS && !stack.isEmpty(); i++) {
                if (!moveItemStackTo(stack, 0, LINK_IN, false)) break;
                moved = true;
            }
            if (!moved) return ItemStack.EMPTY;
        }
        else if (DeckItem.isCrafting(stack)) { if (!moveItemStackTo(stack, LINK_IN, LINK_IN + 1, false)) return ItemStack.EMPTY; }
        else return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        if (port != null) { if (index >= INVENTORY_START && DeckItem.isCrafting(before)) port.queueDeckLink(player); port.processDeckLink(); }
        return before;
    }
}
