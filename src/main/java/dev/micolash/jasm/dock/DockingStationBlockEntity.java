package dev.micolash.jasm.dock;

import com.mojang.authlib.GameProfile;
import dev.micolash.jasm.autocraft.AutocraftState;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.autocraft.Rules;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.network.PlayerNames;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferHolderItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.jspecify.annotations.Nullable;

/**
 * The Docking Station: holds one Deck for its network. The network's ports, jobs, rules and Deck to Deck deliveries use that
 * Deck whether or not its player is online, paying from the Deck's own charge as always, and the dock tops that charge
 * up from the network's power. It costs nothing to run.
 *
 * <p>Taking the Deck out: the network's owner and the Deck's owner always may; anyone else trusted on the network only
 * while it is unlocked. Breaking the dock with a Deck in it follows the same rule, and the mined dock keeps the Deck
 * (and its lock) inside until it is placed again. Nothing but a player's request at the panel moves the Deck: hoppers,
 * pipes and ports see no slots.
 */
public class DockingStationBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    public static final int SLOT = 0;
    private static final int[] NO_SLOTS = new int[0];

    private NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
    /** Locked: only the Deck's owner and the network's owner may take it out. A newly docked Deck starts locked. */
    private boolean locked = true;
    /** Whose Deck it is: the player it is paired to, noted when it went in. */
    private @Nullable UUID deckOwner;
    private String deckOwnerName = "";
    /** The Deck identity this dock is listed under in {@link DockingStations}, while it is in a loaded chunk. */
    private @Nullable UUID listed;
    /** The stand-in last used for the docked Deck's player, kept while it stays theirs. */
    private @Nullable FakePlayer stand;
    /** Set once the dock, being removed, has dropped itself with its Deck inside, so the loot table drops nothing more. */
    private boolean droppedWithDeck;

    public DockingStationBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.DOCKING_STATION_ENTITY.get(), pos, state, Tuning.DOCK_CAPACITY);
    }

    /** An empty dock does nothing at all; a docked Deck is charged from what the network has given the dock. */
    static void serverTick(Level level, BlockPos pos, BlockState state, DockingStationBlockEntity dock) {
        if (!dock.deck().isEmpty() && dock.payForTick()) dock.charge();
    }

    /** No running cost: the dock's power only ever goes into the Deck. */
    @Override
    public int drainPerTick() {
        return 0;
    }

    /** FE a tick the dock gives the Deck: the same as a Battery's charging slot before its size bonus. */
    public static int chargeRate() {
        return JasmConfig.orDefault(JasmConfig.BATTERY_CHARGE_BASE);
    }

    /** Moves up to {@link #chargeRate()} FE into the docked Deck, never past its battery. Returns how much went in. */
    public int charge() {
        ItemStack deck = deck();
        if (!(deck.getItem() instanceof DeckItem item)) return 0;
        int stored = deck.getOrDefault(JasmComponents.ENERGY.get(), 0);
        int amount = Math.min(Math.min(chargeRate(), item.tier().battery() - stored), energy.getAmountAsInt());
        if (amount <= 0) return 0;
        deck.set(JasmComponents.ENERGY.get(), stored + amount);
        energy.set(energy.getAmountAsInt() - amount);
        setChanged();
        return amount;
    }

    // --- the Deck ---

    public ItemStack deck() {
        return items.get(SLOT);
    }

    public boolean locked() {
        return locked;
    }

    public @Nullable UUID deckOwner() {
        return deck().isEmpty() ? null : deckOwner;
    }

    public String deckOwnerName() {
        return deck().isEmpty() ? "" : deckOwnerName;
    }

    /**
     * The docked Deck, if it is {@code player}'s Deck {@code deckId} and the dock can work for it now: powered, its network
     * not over the limit, and on the network the Deck is paired with. Otherwise empty.
     */
    public ItemStack usableDeck(UUID player, UUID deckId) {
        ItemStack deck = deck();
        if (deck.isEmpty() || isRemoved() || !player.equals(deckOwner) || !deckId.equals(deck.get(JasmComponents.DECK_ID.get()))
                || !running() || stopped() || networkBlocked() || !(level instanceof ServerLevel serverLevel))
            return ItemStack.EMPTY;
        CableNetwork network = Networks.at(serverLevel, worldPosition);
        return network != null && onNetwork(network, deck.get(JasmComponents.DECK_NETWORK.get())) ? deck : ItemStack.EMPTY;
    }

    private static boolean onNetwork(CableNetwork network, @Nullable UUID terminal) {
        if (terminal == null) return false;
        for (EncodingTerminalBlockEntity there : network.machines(EncodingTerminalBlockEntity.class)) {
            if (terminal.equals(there.ensureId())) return true;
        }
        return false;
    }

    /**
     * Who acts for the docked Deck: a stand-in for its player, at the dock. It carries nothing and hears nothing; routes
     * only use it to reach the Deck's wafers and network.
     */
    public ServerPlayer actor() {
        UUID owner = deckOwner == null ? new UUID(0, 0) : deckOwner;
        if (stand == null || !stand.getUUID().equals(owner) || stand.level() != level) {
            stand = FakePlayerFactory.get((ServerLevel) level, new GameProfile(owner, deckOwnerName.isEmpty() ? "[JASM]" : deckOwnerName));
            stand.setPos(worldPosition.getCenter());
        }
        return stand;
    }

    /** Whether the docked Deck is paired with an Encoding Terminal on this dock's network, so the network can use it. */
    public boolean linkedHere() {
        ItemStack deck = deck();
        if (deck.isEmpty() || !(level instanceof ServerLevel serverLevel)) return false;
        CableNetwork network = networkBlocked() ? null : Networks.at(serverLevel, worldPosition);
        return network != null && onNetwork(network, deck.get(JasmComponents.DECK_NETWORK.get()));
    }

    // --- who may do what ---

    public boolean isDeckOwner(Player player) {
        return !deck().isEmpty() && player.getUUID().equals(deckOwner);
    }

    public boolean isNetworkOwner(Player player) {
        return owner() != null && owner().equals(player.getUUID());
    }

    /** Players trusted on the network see the panel, and so does the Deck's own player, trusted or not. */
    public boolean canOpen(Player player) {
        return MachineAccess.canUse(this, player) || isDeckOwner(player);
    }

    public DockRefusal takeOutRefusal(Player player) {
        if (deck().isEmpty()) return DockRefusal.EMPTY;
        if (isNetworkOwner(player) || isDeckOwner(player)) return DockRefusal.NONE;
        if (!MachineAccess.canUse(this, player)) return DockRefusal.NO_ACCESS;
        return locked ? DockRefusal.LOCKED : DockRefusal.NONE;
    }

    public DockRefusal lockRefusal(Player player) {
        if (deck().isEmpty()) return DockRefusal.EMPTY;
        return isDeckOwner(player) || isNetworkOwner(player) ? DockRefusal.NONE : DockRefusal.NOT_YOURS;
    }

    /** Breaking a dock with a Deck in it is allowed to those who may take the Deck out. An empty dock breaks like any machine. */
    public boolean mayBreak(Player player) {
        return deck().isEmpty() || takeOutRefusal(player) == DockRefusal.NONE;
    }

    public DockRefusal dockRefusal(Player player, ItemStack stack) {
        if (!(level instanceof ServerLevel serverLevel) || !MachineAccess.canUse(this, player)) return DockRefusal.NO_ACCESS;
        if (!DeckItem.isDeck(stack)) return DockRefusal.NOT_A_DECK;
        if (!deck().isEmpty()) return DockRefusal.OCCUPIED;
        UUID id = stack.get(JasmComponents.DECK_ID.get());
        UUID terminal = stack.get(JasmComponents.DECK_NETWORK.get());
        AutocraftState.Pairing pairing = id == null ? null : AutocraftState.get(serverLevel.getServer()).pairing(id).orElse(null);
        CableNetwork network = networkBlocked() ? null : Networks.at(serverLevel, worldPosition);
        if (pairing == null || network == null || !pairing.terminal().equals(terminal) || !onNetwork(network, terminal))
            return DockRefusal.NOT_LINKED_HERE;
        if (owner() == null || !pairing.player().equals(owner()) && !MachineAccess.trustedBy(network, owner(), pairing.player()))
            return DockRefusal.NOT_TRUSTED_HERE;
        if (DockingStations.docked(id)) return DockRefusal.DOCKED_ELSEWHERE;
        return DockRefusal.NONE;
    }

    // --- what players ask for ---

    /** Puts one Deck from {@code stack} into the dock, if the rules allow. {@code stack} shrinks when it goes in. */
    public DockRefusal dock(ServerPlayer player, ItemStack stack) {
        DockRefusal refusal = dockRefusal(player, stack);
        if (refusal != DockRefusal.NONE) return refusal;
        ServerLevel serverLevel = (ServerLevel) level;
        UUID owner = AutocraftState.get(serverLevel.getServer()).pairing(stack.get(JasmComponents.DECK_ID.get())).orElseThrow().player();
        deckOwner = owner;
        deckOwnerName = PlayerNames.of(serverLevel.getServer(), owner, Networks.at(serverLevel, worldPosition));
        locked = true;
        items.set(SLOT, stack.split(1));
        contentsChanged();
        return DockRefusal.NONE;
    }

    /**
     * Gives the Deck to {@code player}'s inventory, if they may take it. {@code expected} is the Deck their panel showed:
     * a request about a Deck that has since left or been swapped is turned down.
     */
    public DockRefusal takeOut(ServerPlayer player, @Nullable UUID expected) {
        DockRefusal refusal = changed(expected);
        if (refusal == DockRefusal.NONE) refusal = takeOutRefusal(player);
        if (refusal != DockRefusal.NONE) return refusal;
        ItemStack out = deck().copy();
        if (!player.getInventory().add(out)) return DockRefusal.INVENTORY_FULL;
        items.set(SLOT, ItemStack.EMPTY);
        contentsChanged();
        return DockRefusal.NONE;
    }

    public DockRefusal setLocked(ServerPlayer player, boolean lock, @Nullable UUID expected) {
        DockRefusal refusal = changed(expected);
        if (refusal == DockRefusal.NONE) refusal = lockRefusal(player);
        if (refusal != DockRefusal.NONE) return refusal;
        if (locked != lock) {
            locked = lock;
            setChanged();
        }
        return DockRefusal.NONE;
    }

    private DockRefusal changed(@Nullable UUID expected) {
        if (deck().isEmpty()) return DockRefusal.EMPTY;
        return expected == null || expected.equals(deck().get(JasmComponents.DECK_ID.get())) ? DockRefusal.NONE : DockRefusal.CHANGED;
    }

    private void contentsChanged() {
        setChanged();
        relist();
    }

    // --- the list of loaded docks ---

    /** Brings this dock's entry in {@link DockingStations} up to date with what it holds. */
    private void relist() {
        if (!(level instanceof ServerLevel serverLevel) || isRemoved()) return;
        ItemStack deck = deck();
        if (!deck.isEmpty() && deckOwner == null) {
            // put here some other way (a command): its paired player is its owner
            UUID id = deck.get(JasmComponents.DECK_ID.get());
            deckOwner = id == null ? null : AutocraftState.get(serverLevel.getServer()).pairing(id).map(AutocraftState.Pairing::player).orElse(null);
        }
        DockingStations.holds(this, listed, deck, DeckItem.isCrafting(deck) && !Rules.of(deck).isEmpty());
        listed = deck.isEmpty() ? null : deck.get(JasmComponents.DECK_ID.get());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        loaded();
    }

    /** Listed, and the Deck's wafers read now, not on the tick a port first wants them. */
    private void loaded() {
        relist();
        if (level instanceof ServerLevel serverLevel && !deck().isEmpty()) {
            WaferStore store = WaferStore.ifOpen(serverLevel.getServer());
            if (store != null) store.prefetch(serials(deck()));
        }
    }

    private static List<Long> serials(ItemStack deck) {
        List<Long> serials = new ArrayList<>();
        if (deck.getItem() instanceof WaferHolderItem holder) {
            holder.forEachWafer(deck, wafer -> {
                var identity = wafer.get(JasmComponents.WAFER_IDENTITY.get());
                if (identity != null) serials.add(identity.serial());
            });
        }
        return serials;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        DockingStations.holds(this, listed, ItemStack.EMPTY, false);
        listed = null;
    }

    /**
     * Removed with a Deck in it, however (mined, with or without the right tool, in creative, by a command or another
     * mod): the dock drops itself with the Deck, its lock and owner inside, once, and stays where it fell until picked
     * up. The loot table then drops nothing (see {@link DockingStationBlock}). An empty dock drops by its loot table as usual.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null && !level.isClientSide() && !deck().isEmpty()) {
            ItemStack self = new ItemStack(state.getBlock());
            self.applyComponents(collectComponents());
            ItemEntity dropped = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, self);
            dropped.setUnlimitedLifetime();
            dropped.setInvulnerable(true);
            dropped.setDefaultPickUpDelay();
            level.addFreshEntity(dropped);
            droppedWithDeck = true;
        }
        items.set(SLOT, ItemStack.EMPTY);
        DockingStations.holds(this, listed, ItemStack.EMPTY, false);
        listed = null;
    }

    /** Whether this dock drops itself (holding a Deck), so its loot table must drop nothing. */
    public boolean dropsItself() {
        return droppedWithDeck || !deck().isEmpty();
    }

    // --- the container: no automation may put a Deck in or take it out ---

    @Override
    public int[] getSlotsForFace(Direction side) {
        return NO_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return false;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack taken = super.removeItem(slot, amount);
        relist();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack taken = super.removeItemNoUpdate(slot);
        relist();
        return taken;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        relist();
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
        relist();
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new DockingStationMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    // --- saving ---

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putBoolean("locked", locked);
        output.storeNullable("deck_owner", UUIDUtil.CODEC, deckOwner);
        output.putString("deck_owner_name", deckOwnerName);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(1, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        locked = input.getBooleanOr("locked", true);
        deckOwner = input.read("deck_owner", UUIDUtil.CODEC).orElse(null);
        deckOwnerName = input.getStringOr("deck_owner_name", "");
        relist();
    }

    /**
     * The mined dock keeps its charge and owner, and the Deck with its lock and whose it is. The Deck is never listed as
     * container contents, so nothing that opens containers in an inventory can reach it.
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (!deck().isEmpty()) {
            components.set(JasmComponents.DOCKED_DECK.get(), new DockedDeck(deck().copy(), locked, Optional.ofNullable(deckOwner), deckOwnerName));
        }
    }

    /** Placed again: the Deck goes back in as it was, locked or not. */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        DockedDeck held = components.get(JasmComponents.DOCKED_DECK.get());
        if (held != null && !held.deck().isEmpty()) {
            items.set(SLOT, held.deck().copy());
            locked = held.locked();
            deckOwner = held.owner().orElse(null);
            deckOwnerName = held.ownerName();
            droppedWithDeck = false;
            setChanged();
            loaded();
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("Items");
        output.discard("locked");
        output.discard("deck_owner");
        output.discard("deck_owner_name");
    }
}
