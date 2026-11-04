package dev.micolash.jasm.tether;

import dev.micolash.jasm.chunkload.ChunkLoaders;
import dev.micolash.jasm.chunkload.ChunkLoaders.ChunkKey;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.workshop.BitlingItem;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The Network Tether: while a Nibbling or Byteling keeps watch at its desk, every chunk its network touches stays loaded,
 * both ends of a Relay Chatterbox link included. The network tops up the operator's battery and the operator pays for
 * the chunks, a little more for each one held. Several Tethers on one network: the one placed first that can pay keeps
 * watch, the rest stand by. When the operator can't pay, the chunks are let go {@link #GRACE_TICKS} later; when it is
 * taken out, at once. Mined, the block keeps its operator inside its item, like the Relay Chatterbox. Hoppers and pipes
 * see no slots.
 */
public class NetworkTetherBlockEntity extends MachineBlockEntity implements WorldlyContainer {
    public static final int OPERATOR = 0;
    /** Ticks the chunks stay held after the operator last paid, so a flicker of power doesn't drop them. */
    public static final int GRACE_TICKS = 200;
    /** How often, at most, a Tether looks for chunks its network no longer touches. */
    private static final int TRIM_TICKS = 20;
    private static final int[] NO_SLOTS = new int[0];

    private NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
    /** Set once the block, being removed, has dropped itself with its operator inside, so the loot table drops nothing more. */
    private boolean droppedItself;
    /** Game time it was first placed; the first placed keeps watch. Saved. */
    private long placedAt = -1;

    // Server only, not saved.
    private TetherStatus status = TetherStatus.EMPTY;
    /** FE the operator paid on the last tick it paid, and when. */
    private int paid;
    private long paidAt = Long.MIN_VALUE;
    /** When the operator first couldn't pay while chunks were held; -1 while it pays. */
    private long unpaidSince = -1;
    /**
     * The chunks it was last told to hold. They are worked out once for each network and limit, so a new set means the
     * network or the server's limit has changed.
     */
    private @Nullable Set<ChunkKey> matched;
    private boolean trimmed;
    /** The list it last held every chunk of {@link #matched} in; null until it does, and once it lets go. */
    private @Nullable ChunkLoaders heldAllIn;
    /** Its own chunk, which it holds while on no network. */
    private @Nullable Set<ChunkKey> own;
    /** While it keeps watch under the server's limit: how many chunks its network touches. 0 otherwise. */
    private int limitedOf;

    /** The look as this player's game was told it. Client only. */
    private int shownLook = look(TetherStatus.Mood.EMPTY, null);

    public NetworkTetherBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.NETWORK_TETHER_ENTITY.get(), pos, state, 0);
    }

    /** The operator slot takes any Nibbling or Byteling. */
    public static boolean accepts(int slot, ItemStack stack) {
        return slot == OPERATOR && stack.getItem() instanceof BitlingItem critter && critter.stage() != BitlingStage.BITLING;
    }

    public ItemStack operator() {
        return items.get(OPERATOR);
    }

    private @Nullable BitlingItem seated() {
        ItemStack operator = operator();
        return accepts(OPERATOR, operator) && operator.getItem() instanceof BitlingItem critter ? critter : null;
    }

    // --- the cost ---

    /** FE a tick the operator pays to keep {@code chunks} chunks loaded: the base cost plus the chunk cost times n(n+1)/2. */
    public static int cost(int chunks) {
        if (chunks <= 0) return 0;
        long n = chunks;
        long total = JasmConfig.orDefault(JasmConfig.TETHER_BASE_COST) + JasmConfig.orDefault(JasmConfig.TETHER_CHUNK_COST) * (n * (n + 1) / 2);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Whether the operator could pay for {@code chunks} chunks this tick. */
    boolean canLead(int chunks) {
        return seated() != null && charge() >= cost(chunks);
    }

    long placedAt() {
        return placedAt;
    }

    // --- the battery: the network tops up the operator, the operator pays for the chunks ---

    /** FE in the operator's battery, or 0 without one. */
    public int charge() {
        return seated() == null ? 0 : BitlingItem.energy(operator());
    }

    /** Moves the power the block was given into the operator's battery, as the Relay Chatterbox charges its Link Byteling. */
    private void topUp() {
        BitlingItem critter = seated();
        if (critter == null) return;
        ItemStack operator = operator();
        int amount = energy.getAmountAsInt();
        int charge = BitlingItem.energy(operator);
        int moved = Math.min(amount, critter.battery() - charge);
        if (moved > 0) {
            energy.set(amount - moved);
            operator.set(JasmComponents.ENERGY.get(), charge + moved);
            chargedItem(moved);
            batteryChanged();
        }
    }

    /** Takes up to {@code amount} from the operator's battery. */
    private void spend(int amount, long now) {
        int charge = charge();
        int taken = Math.min(amount, charge);
        if (taken > 0) {
            operator().set(JasmComponents.ENERGY.get(), charge - taken);
            batteryChanged();
        }
        paid = taken;
        paidAt = now;
    }

    /** The charge changes no comparator signal, so only saving needs to hear about it. */
    private void batteryChanged() {
        if (level != null) level.blockEntityChanged(worldPosition);
    }

    /** Lets in as much power as the operator's battery has room for, so charging is only as quick as the supply. */
    private void sizeIntake() {
        BitlingItem critter = seated();
        int room = critter == null ? 0 : Math.max(0, critter.battery() - BitlingItem.energy(operator()));
        if (energy.getCapacityAsInt() != room) energy.resize(room);
    }

    /** FE a tick the operator is paying right now: 0 when it paid nothing on the last tick. Server only. */
    public int powerUse() {
        return level != null && level.getGameTime() - paidAt <= 1 ? paid : 0;
    }

    // --- the tick ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, NetworkTetherBlockEntity tether) {
        tether.tick((ServerLevel) level);
    }

    private void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (placedAt < 0) {
            placedAt = now;
            setChanged();
        }
        boolean enabled = JasmConfig.orDefault(JasmConfig.TETHER_ENABLED);
        if (enabled) topUp();
        ChunkLoaders loaders = ChunkLoaders.get(level.getServer());
        // Asking for the network every tick also keeps it alive, so it passes power on each tick.
        CableNetwork network = Networks.at(level, worldPosition);
        TetherWatch watch = network == null ? null : network.derived(TetherWatch.OF);
        int limit = JasmConfig.orDefault(JasmConfig.TETHER_MAX_CHUNKS);
        if (own == null) own = Set.of(ChunkKey.of(level, worldPosition));
        int count = watch == null ? 1 : watch.held(limit);
        boolean holding = !loaders.held(level, worldPosition).isEmpty();
        limitedOf = 0;
        TetherStatus next;
        if (!enabled || seated() == null) {
            loaders.release(level, worldPosition);
            heldAllIn = null;
            unpaidSince = -1;
            next = enabled ? TetherStatus.EMPTY : TetherStatus.DISABLED;
        } else if (canLead(count) && (watch == null || watch.leader(now, count) == this)) {
            unpaidSince = -1;
            // Only the leader asks which chunks, so the network works the pick out once.
            Set<ChunkKey> wanted = watch == null ? own : watch.wanted(level.dimension(), worldPosition, limit);
            boolean fresh = wanted != matched;
            if (fresh) {
                matched = wanted;
                trimmed = false;
            }
            if (watch != null && wanted.size() < watch.chunks().size()) limitedOf = watch.chunks().size();
            // The chunks change only with the network or the limit, so once all are held there is nothing to add.
            boolean all = !fresh && heldAllIn == loaders || loaders.hold(level, worldPosition, wanted, false);
            heldAllIn = all ? loaders : null;
            // Chunks it holds and no longer wants (the network shrank, or the limit fell) go only once every held chunk
            // is loaded: until then the network may look smaller than it is, as after a restart.
            if (all && !trimmed && (now + worldPosition.asLong()) % TRIM_TICKS == 0 && loaders.allLoaded(level, worldPosition)) {
                loaders.hold(level, worldPosition, wanted, true);
                trimmed = true;
            }
            spend(cost(loaders.held(level, worldPosition).size()), now);
            next = all ? TetherStatus.WATCHING : TetherStatus.STARTING;
        } else if (canLead(count)) {
            loaders.release(level, worldPosition);
            heldAllIn = null;
            unpaidSince = -1;
            next = TetherStatus.STANDBY;
        } else if (holding) {
            if (unpaidSince < 0) unpaidSince = now;
            if (now - unpaidSince >= GRACE_TICKS) {
                loaders.release(level, worldPosition);
                heldAllIn = null;
                next = TetherStatus.ASLEEP;
            } else {
                next = TetherStatus.NO_POWER;
            }
        } else {
            next = TetherStatus.ASLEEP;
        }
        status = next;
        if (enabled) {
            sizeIntake();
        } else if (energy.getCapacityAsInt() != 0) {
            energy.resize(0);
        }
        showLook(level);
    }

    /** What the Tether is doing. Server only. */
    public TetherStatus status() {
        return status;
    }

    /** How many chunks it holds right now. Server only. */
    public int chunksHeld() {
        return level instanceof ServerLevel serverLevel ? ChunkLoaders.count(serverLevel, worldPosition) : 0;
    }

    /** While it keeps watch with the server's limit cutting it short, how many chunks its network touches; else 0. Server only. */
    public int limitedOf() {
        return limitedOf;
    }

    // --- what players' games are told: the mood and which critter sits there ---

    /** The look packed in one number: the mood, then the operator's kind and stage. */
    private static int look(TetherStatus.Mood mood, @Nullable BitlingItem critter) {
        int kind = critter == null ? 0 : critter.kind().ordinal();
        int stage = critter == null ? 0 : critter.stage().ordinal();
        return mood.ordinal() | kind << 2 | stage << 4;
    }

    private int look() {
        BitlingItem critter = seated();
        return look(critter == null ? TetherStatus.Mood.EMPTY : status.mood(), critter);
    }

    /** Tells players' games when the block would be drawn differently, and lights the base's strips while on watch. */
    private void showLook(ServerLevel level) {
        syncLooks(look());
        boolean lit = status.mood() == TetherStatus.Mood.WATCH;
        BlockState state = getBlockState();
        if (state.hasProperty(NetworkTetherBlock.POWERED) && state.getValue(NetworkTetherBlock.POWERED) != lit) {
            level.setBlock(worldPosition, state.setValue(NetworkTetherBlock.POWERED, lit), Block.UPDATE_CLIENTS);
        }
    }

    /** The mood as this player's game knows it. */
    public TetherStatus.Mood shownMood() {
        TetherStatus.Mood[] all = TetherStatus.Mood.values();
        int mood = shownLook & 3;
        return mood < all.length ? all[mood] : TetherStatus.Mood.EMPTY;
    }

    /** The operator's kind as this player's game knows it. */
    public BitlingKind shownKind() {
        BitlingKind[] all = BitlingKind.values();
        return all[((shownLook >> 2) & 3) % all.length];
    }

    // Players only get the look, not the slot or the charge.
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        int now = look();
        looksChanged(now);
        tag.putInt("look", now);
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        shownLook = input.getIntOr("look", shownLook);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        handleUpdateTag(input);
    }

    // --- the machine ---

    /** The block uses none itself: the operator's battery pays for the chunks. */
    @Override
    public int drainPerTick() {
        return 0;
    }

    /**
     * Never takes a machine place: a Tether that stopped over the limit would let chunks go, their machines would drop
     * out of the count, and it would start again, over and over.
     */
    @Override
    public boolean countsTowardLimit() {
        return false;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new NetworkTetherMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    /**
     * Removed however (mined, in creative, by a blast, a command or another mod): with an operator inside, the block drops
     * itself with the operator in it, once, and stays where it fell until picked up; the loot table then adds nothing.
     * Its chunks are let go.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null && !level.isClientSide() && !operator().isEmpty()) {
            ItemStack self = new ItemStack(state.getBlock());
            self.applyComponents(collectComponents());
            ItemEntity dropped = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, self);
            dropped.setUnlimitedLifetime();
            dropped.setInvulnerable(true);
            dropped.setDefaultPickUpDelay();
            level.addFreshEntity(dropped);
            droppedItself = true;
        }
        items.set(OPERATOR, ItemStack.EMPTY);
        if (level instanceof ServerLevel serverLevel) {
            ChunkLoaders.removed(serverLevel, pos);
        }
    }

    /** Whether this block drops itself (holding its operator), so its loot table must drop nothing. */
    public boolean dropsItself() {
        return droppedItself || !operator().isEmpty();
    }

    // --- the container: one operator, and nothing through the faces ---

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(slot, stack);
    }

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
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return false;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    // --- saving ---

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putLong("placed_at", placedAt);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        // Power that came in after the last tick may be more than the intake is sized for now: keep all of it.
        energy.resize(Math.max(0, input.getIntOr("energy", 0)));
        super.loadAdditional(input);
        items = NonNullList.withSize(1, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        placedAt = input.getLongOr("placed_at", -1);
    }

    /** The mined block keeps its operator. It is never listed as container contents, so nothing can reach it from an inventory. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (!operator().isEmpty()) {
            components.set(JasmComponents.TETHER_CONTENTS.get(), new TetherContents(operator().copy()));
        }
    }

    /** Placed again: the operator goes back in as it was. */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        TetherContents held = components.get(JasmComponents.TETHER_CONTENTS.get());
        if (held != null) {
            items.set(OPERATOR, accepts(OPERATOR, held.operator()) ? held.operator().copyWithCount(1) : ItemStack.EMPTY);
            droppedItself = false;
            setChanged();
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("Items");
    }
}
