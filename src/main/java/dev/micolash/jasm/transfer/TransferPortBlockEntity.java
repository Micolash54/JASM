package dev.micolash.jasm.transfer;

import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.autocraft.Jobs;
import dev.micolash.jasm.autocraft.Machines;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.pool.PoolAccess;
import dev.micolash.jasm.pool.PoolStore;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
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
        var tank = Machines.fluidInlet(world, worldPosition.relative(face), face.getOpposite());
        var materials = Machines.materialInlets(world, worldPosition.relative(face), face.getOpposite());
        if (inventory == null && tank == null && materials.isEmpty()) return;
        var store = WaferStore.get(world.getServer());
        var storage = DeckStorage.checked(store, deck, player).avoiding(worldPosition.relative(face));
        // Items and fluids share one allowance: an item is one share, and 125 mB of fluid is one share.
        int allowance = budget;
        budget = (int) Math.min(budget, DeckStorage.affordable(deck));
        int itemBudget = budget;
        // Output takes the shared allowance first. Neither filter list disables the other direction.
        if (inventory != null && kind.exports() && filters.output().hasAllow()) {
            var contents = storage.contents();
            var keys = new ArrayList<>(contents.keySet());
            keys.removeIf(key -> filters.output().rank(key.getItem()) < 0);
            keys.sort(Comparator.comparingInt(key -> filters.output().rank(key.getItem())));
            for (var key : keys) {
                if (budget <= 0) break;
                // No transaction is open while the Deck moves things: its storage blocks open their own.
                int accepted;
                try (var tx = Transaction.openRoot()) {
                    accepted = inventory.insert(key, (int) Math.min(budget, contents.get(key)), tx);   // only asking
                }
                if (accepted <= 0) continue;
                var stacks = storage.withdrawQuietly(key, accepted);
                int taken = stacks.stream().mapToInt(ItemStack::getCount).sum();
                if (taken <= 0) continue;
                int inserted;
                try (var tx = Transaction.openRoot()) {
                    inserted = inventory.insert(key, taken, tx);
                    if (inserted > 0) tx.commit();
                }
                // Put back what the block did not take, wafers first even if their intake filters have changed.
                if (inserted < taken) storage.restoreQuietly(key, taken - inserted);
                if (inserted > 0) {
                    budget -= inserted;
                    transferred(inserted);
                }
            }
        }
        if (inventory != null && kind.imports() && budget > 0) {
            var unique = new LinkedHashSet<ItemResource>();
            for (int slot = 0; slot < inventory.size(); slot++) if (!inventory.getResource(slot).isEmpty()) unique.add(inventory.getResource(slot));
            var keys = new ArrayList<>(unique);
            keys.removeIf(key -> filters.input().rank(key.getItem()) < 0);
            keys.sort(Comparator.comparingInt(key -> filters.input().rank(key.getItem())));
            for (var key : keys) {
                if (budget <= 0) break;
                int room = (int) storage.room(key, budget, DeckStorage.Excess.VOID);
                if (room <= 0) continue;
                int wanted;
                try (var tx = Transaction.openRoot()) {
                    wanted = inventory.extract(key, room, tx);   // only asking
                }
                if (wanted <= 0) continue;
                var put = storage.depositResult(key, wanted, DeckStorage.Excess.VOID);
                long stored = put.total();
                if (stored <= 0) continue;
                int taken;
                try (var tx = Transaction.openRoot()) {
                    taken = inventory.extract(key, (int) stored, tx);
                    if (taken > 0) tx.commit();
                }
                // Only what was really stored is backed out: destroyed items were never on a wafer.
                if (taken < stored) undoDeposit(player, deck, store, key, Math.min(put.stored(), stored - taken));
                if (taken > 0) {
                    budget -= taken;
                    transferred(taken);
                }
            }
        }
        int shares = allowance - (itemBudget - budget);
        if (tank != null && shares > 0) {
            if (kind.exports() && filters.output().hasAllow()) shares -= fluidsOut(tank, storage, store, deck, player, shares);
            if (kind.imports() && shares > 0) shares -= fluidsIn(tank, storage, store, deck, player, shares);
        }
        if (!materials.isEmpty() && shares > 0) {
            if (kind.exports() && filters.output().hasAllow())
                shares -= TransferMaterials.out(materials, storage, deck, filters.output(), shares, this::transferred);
            if (kind.imports() && shares > 0) TransferMaterials.in(materials, storage, deck, filters.input(), shares, this::transferred);
        }
        Jobs.refreshOpenDeck(player, deck);
    }

    /** Takes back items that went into the Deck but did not leave the source: from the wafers, then the storage blocks. */
    private void undoDeposit(ServerPlayer player, ItemStack deck, WaferStore store, ItemResource key, long amount) {
        long left = amount;
        for (var record : DeckStorage.records(store, deck))
            if (record != null && left > 0) left -= store.extract(record, key, left, false, player);
        if (left > 0) extractFromChests(player, deck, key, left);
    }

    private void undoFluidDeposit(ServerPlayer player, ItemStack deck, WaferStore store, FluidResource key, long amount) {
        long left = amount;
        for (var record : DeckStorage.records(store, deck))
            if (record != null && left > 0) left -= store.extractFluid(record, key, left, false, player);
        if (left > 0) extractFluidFromTanks(player, deck, key, left);
    }

    private long extractFromChests(ServerPlayer player, ItemStack deck, ItemResource key, long amount) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        if (pool == null) return 0;
        long taken = 0;
        for (PoolStore chest : pool.stores(worldPosition.relative(face))) {
            if (taken >= amount) break;
            taken += chest.extractNow(key, amount - taken);
        }
        return taken;
    }

    private long extractFluidFromTanks(ServerPlayer player, ItemStack deck, FluidResource key, long amount) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        if (pool == null) return 0;
        long taken = 0;
        for (PoolStore tank : pool.stores(worldPosition.relative(face))) {
            if (taken >= amount) break;
            taken += tank.extractFluidNow(key, amount - taken);
        }
        return taken;
    }

    /** Pours fluid from the Deck into the block, as far as it takes and the allowance and charge go. Returns the shares used. */
    private int fluidsOut(ResourceHandler<FluidResource> tank, DeckStorage.Checked storage, WaferStore store, ItemStack deck,
            ServerPlayer player, int shares) {
        var contents = storage.fluidContents();
        var keys = new ArrayList<>(contents.keySet());
        keys.removeIf(key -> filters.output().rank(key.getFluid()) < 0);
        keys.sort(Comparator.comparingInt(key -> filters.output().rank(key.getFluid())));
        int used = 0;
        for (var key : keys) {
            long room = Math.min((long) (shares - used) * FluidAmounts.PER_SHARE, DeckStorage.affordableFluid(deck));
            if (room <= 0) break;
            // No transaction is open while the Deck moves things: its storage blocks open their own.
            long accepted;
            try (var tx = Transaction.openRoot()) {
                accepted = tank.insert(key, (int) Math.min(room, contents.get(key)), tx);   // only asking
                if (accepted <= 0 && room < FluidAmounts.PER_BUCKET) {
                    // Some blocks (a cauldron) only take a whole bucket: offer one and pay it back out of the next operations.
                    long bucket = Math.min(Math.min(FluidAmounts.PER_BUCKET, contents.get(key)), DeckStorage.affordableFluid(deck));
                    accepted = tank.insert(key, (int) bucket, tx);
                }
            }
            if (accepted <= 0) continue;
            long taken = storage.withdrawFluid(key, accepted, false);
            if (taken <= 0) continue;
            long inserted;
            try (var tx = Transaction.openRoot()) {
                inserted = tank.insert(key, (int) taken, tx);
                if (inserted > 0) tx.commit();
            }
            // Put back what the block did not take, wafers first even if their intake filters have changed.
            if (inserted < taken) storage.restoreQuietlyFluid(key, taken - inserted);
            if (inserted > 0) {
                int moved = (int) FluidAmounts.shares(inserted);
                used += moved;
                transferred(moved);
            }
        }
        return used;
    }

    /** Pulls fluid from the block into the Deck, as far as the wafers have room and the allowance and charge go. */
    private int fluidsIn(ResourceHandler<FluidResource> tank, DeckStorage.Checked storage, WaferStore store, ItemStack deck,
            ServerPlayer player, int shares) {
        var unique = new LinkedHashSet<FluidResource>();
        for (int slot = 0; slot < tank.size(); slot++) if (!tank.getResource(slot).isEmpty()) unique.add(tank.getResource(slot));
        var keys = new ArrayList<>(unique);
        keys.removeIf(key -> filters.input().rank(key.getFluid()) < 0);
        keys.sort(Comparator.comparingInt(key -> filters.input().rank(key.getFluid())));
        int used = 0;
        for (var key : keys) {
            long most = Math.min((long) (shares - used) * FluidAmounts.PER_SHARE, DeckStorage.affordableFluid(deck));
            if (most <= 0) break;
            long room = storage.roomFluid(key, most, DeckStorage.Excess.VOID);
            if (room <= 0) continue;
            long wanted;
            try (var tx = Transaction.openRoot()) {
                wanted = tank.extract(key, (int) room, tx);   // only asking
            }
            if (wanted <= 0 && most < FluidAmounts.PER_BUCKET) {
                // Some blocks (a cauldron) only give a whole bucket: take one and pay it back out of the next operations.
                long bucket = Math.min(FluidAmounts.PER_BUCKET, DeckStorage.affordableFluid(deck));
                if (storage.roomFluid(key, bucket, DeckStorage.Excess.VOID) >= bucket) {
                    try (var tx = Transaction.openRoot()) {
                        wanted = tank.extract(key, (int) bucket, tx);   // only asking
                    }
                }
            }
            if (wanted <= 0) continue;
            var put = storage.depositFluidResult(key, wanted, DeckStorage.Excess.VOID);
            long stored = put.total();
            if (stored <= 0) continue;
            long taken;
            try (var tx = Transaction.openRoot()) {
                taken = tank.extract(key, (int) stored, tx);
                if (taken > 0) tx.commit();
            }
            // Only what was really stored is backed out: destroyed fluid was never on a wafer.
            if (taken < stored) undoFluidDeposit(player, deck, store, key, Math.min(put.stored(), stored - taken));
            if (taken > 0) {
                int moved = (int) FluidAmounts.shares(taken);
                used += moved;
                transferred(moved);
            }
        }
        return used;
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
