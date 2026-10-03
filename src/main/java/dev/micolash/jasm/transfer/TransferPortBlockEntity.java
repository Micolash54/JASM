package dev.micolash.jasm.transfer;

import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.autocraft.Machines;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** A cable-mounted inventory mover. Its Deck linking is shared with the Access Port. */
public class TransferPortBlockEntity extends AccessPortBlockEntity {
    private final TransferPortKind kind;
    private final Direction face;
    private TransferFilters filters = TransferFilters.DEFAULT;
    private RedstoneMode redstoneMode = RedstoneMode.IGNORE;

    public TransferPortBlockEntity(BlockPos pos, BlockState state, TransferPortKind kind, Direction face) {
        super(pos, state);
        this.kind = kind;
        this.face = face;
    }
    public TransferPortKind kind() { return kind; }
    public TransferFilters filters() { return filters; }
    public void setFilters(TransferFilters filters) { this.filters = filters; setChanged(); }
    public RedstoneMode redstoneMode() { return redstoneMode; }
    public void setRedstoneMode(RedstoneMode mode) { redstoneMode = mode; setChanged(); }
    public boolean hasRedstoneUpgrade() {
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            if (getItem(SPEED_START + i).is(JasmItems.REDSTONE_UPGRADE.get())) return true;
        }
        return false;
    }
    @Override
    public boolean hasMachine(Direction side) { return false; }
    @Override
    protected boolean canPowerSide(Direction side) { return side == face; }
    @Override
    public int[] getSlotsForFace(Direction side) { return new int[0]; }
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot >= SPEED_START && slot < INVENTORY_SIZE && stack.is(JasmItems.REDSTONE_UPGRADE.get())) {
            for (int i = SPEED_START; i < INVENTORY_SIZE; i++) {
                if (i != slot && getItem(i).is(JasmItems.REDSTONE_UPGRADE.get())) return false;
            }
            return true;
        }
        return (slot == DECK_IN || slot == POWER_SLOT || slot >= SPEED_START) && super.canPlaceItem(slot, stack);
    }
    @Override
    public Component getDisplayName() { return label().isEmpty() ? Component.translatable("item.jasm." + kind.id()) : Component.literal(label()); }
    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return new TransferPortMenu(id, inventory, this); }
    @Override
    public void writeOpening(RegistryFriendlyByteBuf buf) {
        buf.writeEnum(kind);
        TransferFilters.STREAM_CODEC.encode(buf, filters);
    }

    public void tickTransfer() {
        payForTick();
        sendPower();
        processDeckLink();
        if (!(level instanceof ServerLevel world) || !running() || networkBlocked()) return;
        // Thin ports in one block space read the same signal, each using its own mode.
        if (hasRedstoneUpgrade() && !redstoneMode.allows(world.hasNeighborSignal(worldPosition))) return;
        int budget = transferBudget();
        if (budget <= 0) return;
        var network = Networks.at(world, worldPosition);
        var pairing = network == null ? null : destination(network);
        if (pairing == null) return;
        var player = world.getServer().getPlayerList().getPlayer(pairing.player());
        if (player == null) return;
        ItemStack deck = Jobs.findDeck(player, pairing.deck());
        if (deck.isEmpty() && pairing.deck().equals(getItem(DECK_OUT).get(JasmComponents.DECK_ID.get()))) deck = getItem(DECK_OUT);
        if (deck.isEmpty() || !DeckStorage.hasPower(deck) || !DeckItem.worksIn(deck, player.level())
                || !DeckItem.worksIn(deck, world)
                || !pairing.terminal().equals(deck.get(JasmComponents.DECK_NETWORK.get())))
            return;
        var inventory = Machines.inlet(world, worldPosition.relative(face), face.getOpposite());
        if (inventory == null) return;
        var store = WaferStore.get(world.getServer());
        var storage = DeckStorage.checked(store, deck, player);
        budget = (int) Math.min(budget, DeckStorage.affordable(deck));
        // Output takes the shared allowance first. Neither filter list disables the other direction.
        if (kind.exports() && filters.output().hasAllow()) {
            var contents = storage.contents();
            var keys = new ArrayList<>(contents.keySet());
            keys.removeIf(key -> filters.output().rank(key.getItem()) < 0);
            keys.sort(Comparator.comparingInt(key -> filters.output().rank(key.getItem())));
            for (var key : keys) {
                if (budget <= 0) break;
                try (var tx = Transaction.openRoot()) {
                    int accepted = inventory.insert(key, (int) Math.min(budget, contents.get(key)), tx);
                    if (accepted <= 0) continue;
                    var stacks = storage.withdrawQuietly(key, accepted);
                    int taken = stacks.stream().mapToInt(ItemStack::getCount).sum();
                    if (taken == accepted) {
                        tx.commit();
                        budget -= taken;
                        transferred(taken);
                    } else if (taken > 0) {
                        // Restore the original wafers even if their intake filters have changed.
                        long remaining = taken;
                        for (var record : DeckStorage.records(store, deck))
                            if (record != null && remaining > 0) remaining -= store.insert(record, key, remaining, false, player);
                    }
                }
            }
        }
        if (kind.imports() && budget > 0) {
            var unique = new LinkedHashSet<ItemResource>();
            for (int slot = 0; slot < inventory.size(); slot++) if (!inventory.getResource(slot).isEmpty()) unique.add(inventory.getResource(slot));
            var keys = new ArrayList<>(unique);
            keys.removeIf(key -> filters.input().rank(key.getItem()) < 0);
            keys.sort(Comparator.comparingInt(key -> filters.input().rank(key.getItem())));
            for (var key : keys) {
                if (budget <= 0) break;
                int room = (int) storage.room(key, budget);
                if (room <= 0) continue;
                try (var tx = Transaction.openRoot()) {
                    int taken = inventory.extract(key, room, tx);
                    if (taken <= 0) continue;
                    long stored = storage.depositAmount(key, taken);
                    if (stored == taken) {
                        tx.commit();
                        budget -= taken;
                        transferred(taken);
                    } else if (stored > 0) {
                        // The source transaction rolls back, so undo a partial deposit as well.
                        for (var record : DeckStorage.records(store, deck))
                            if (record != null && stored > 0) stored -= store.extract(record, key, stored, false, player);
                    }
                }
            }
        }
        Jobs.refreshOpenDeck(player, deck);
    }
    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("transfer_filters", TransferFilters.CODEC, filters);
        output.store("redstone_mode", RedstoneMode.CODEC, redstoneMode);
    }
    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        filters = input.read("transfer_filters", TransferFilters.CODEC).orElse(TransferFilters.DEFAULT);
        redstoneMode = input.read("redstone_mode", RedstoneMode.CODEC).orElse(RedstoneMode.IGNORE);
        // Earlier ports held up to four speed upgrades in the old power slot.
        ItemStack old = getItem(POWER_SLOT);
        if (old.is(JasmItems.SPEED_UPGRADE.get())) {
            for (int i = 0; i < PortOperations.UPGRADE_SLOTS && !old.isEmpty(); i++) {
                if (getItem(SPEED_START + i).isEmpty()) setItem(SPEED_START + i, old.split(1));
            }
            if (old.isEmpty()) setItem(POWER_SLOT, ItemStack.EMPTY);
        }
    }
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(JasmComponents.TRANSFER_FILTERS.get(), filters);
        components.set(JasmComponents.REDSTONE_MODE.get(), redstoneMode);
    }
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        filters = components.getOrDefault(JasmComponents.TRANSFER_FILTERS.get(), TransferFilters.DEFAULT);
        redstoneMode = components.getOrDefault(JasmComponents.REDSTONE_MODE.get(), RedstoneMode.IGNORE);
    }
    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("transfer_filters");
        output.discard("redstone_mode");
    }
}
