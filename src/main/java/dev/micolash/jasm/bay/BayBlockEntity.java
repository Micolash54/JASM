package dev.micolash.jasm.bay;

import com.mojang.authlib.GameProfile;
import dev.micolash.jasm.config.Feature;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.MachineSides;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.transfer.SpeedUpgradeItem;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;

/**
 * What both bays share: a 3×3 grid, one tank, four upgrade slots, a power buffer fed by cables and JASM power sources,
 * and the work loop. Work comes in cycles; the action happens as a cycle ends, after looking at the front again.
 * A bay with nothing to do sleeps until a block update, a change in its slots or tank, or a redstone change wakes it.
 * Waits that no update announces (power, crowding, claims, an unloaded front) are looked at every half second.
 */
public abstract class BayBlockEntity extends MachineBlockEntity {
    public static final int GRID = 9;
    public static final int UPGRADE_START = GRID;
    public static final int UPGRADES = 4;
    public static final int SLOTS = GRID + UPGRADES;
    /** Block event: a cycle started; the parameter is its length in ticks, plus {@link #EVENT_SCOOP}. */
    public static final int EVENT_CYCLE = 1;
    /** Set in the cycle event when the Demolition Bay scoops a fluid rather than breaking a block. */
    private static final int EVENT_SCOOP = 1 << 16;
    private static final UUID NOBODY = UUID.fromString("5a5f9a1e-7c1b-4a8e-9a39-2f3c1e0b6d11");

    protected NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    protected final FluidStacksResourceHandler tank;
    private final ResourceHandler<ItemResource> itemAutomation;
    private final ResourceHandler<FluidResource> fluidAutomation;
    private final MachineSides.Gated<ItemResource> itemGates;
    private final MachineSides.Gated<FluidResource> fluidGates;
    /** The Deployment Bay only takes in and the Demolition Bay only gives out, so each offers just that or None. */
    private final MachineSides sides;
    private final BayClock clock = new BayClock();
    private BayRedstone redstone = BayRedstone.IGNORE;
    /** What the bay may take (Demolition) or put out (Deployment). Empty lets everything through. */
    private WaferSettings filter = WaferSettings.DEFAULT;
    private BayStatus status = BayStatus.SLEEPING;
    private boolean wake = true;
    private boolean signal;
    private boolean signalKnown;

    // Client side only: what the renderer draws.
    private long clientCycleStart = Long.MIN_VALUE;
    private int clientCycleTicks;
    private boolean clientScoop;
    /** Flips every cycle, so two Bitlings take turns. */
    private boolean clientTurn;
    /** The cycle started after a rest, not straight after the last one. */
    private boolean clientRested = true;
    private int shownLooks;
    /** When the filter started refusing what is in front, in game ticks, so the Bitling shakes its head from then. */
    private long clientRefusedSince = Long.MIN_VALUE;
    private ItemStack shownHeld = ItemStack.EMPTY;

    protected BayBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, JasmConfig.orDefault(JasmConfig.BAY_ENERGY_CAPACITY));
        tank = new FluidStacksResourceHandler(1, JasmConfig.orDefault(JasmConfig.BAY_TANK_BUCKETS) * FluidAmounts.PER_BUCKET) {
            @Override
            protected void onContentsChanged(int index, FluidStack previousContents) {
                setChanged();
                wake();
            }
        };
        itemAutomation = new BayAutomation.Items(VanillaContainerWrapper.of(this), kind().takesIn());
        fluidAutomation = new BayAutomation.Fluids(tank, kind().takesIn());
        itemGates = new MachineSides.Gated<>(itemAutomation);
        fluidGates = new MachineSides.Gated<>(fluidAutomation);
        sides = new MachineSides(kind().takesIn() ? MachineSides.IN_ONLY : MachineSides.OUT_ONLY, true, this::sidesChanged);
    }

    public abstract BayKind kind();

    /** What there is to do in front right now, and what it would cost. Only reads the world. */
    protected abstract Plan plan(ServerLevel level, BlockPos front);

    /** Does the work, looking at the front again first. WORKING if something was done, or why not. */
    protected abstract BayStatus act(ServerLevel level, BlockPos front);

    /** Every half second, even asleep. */
    protected void everyHalfSecond(ServerLevel level) {}

    public record Plan(BayStatus status, int cost, boolean scoop) {
        public static Plan work(int cost) {
            return new Plan(BayStatus.WORKING, Math.max(0, cost), false);
        }

        /** Work on a fluid rather than a block: no cracks, a softer beam. */
        public static Plan scoop(int cost) {
            return new Plan(BayStatus.WORKING, Math.max(0, cost), true);
        }

        public static Plan idle(BayStatus status) {
            return new Plan(status, 0, false);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BayBlockEntity bay) {
        bay.tick((ServerLevel) level);
        bay.pushOutputs((ServerLevel) level);
    }

    private void tick(ServerLevel level) {
        if (!signalKnown) {
            signal = level.hasNeighborSignal(worldPosition);
            signalKnown = true;
        }
        boolean halfSecond = (level.getGameTime() + worldPosition.asLong()) % 10 == 0;
        if (halfSecond) {
            everyHalfSecond(level);
        }
        if (clock.running()) {
            if (!clock.advance()) return;
            BlockPos front = front();
            BayStatus done = level.isLoaded(front) ? act(level, front) : BayStatus.FRONT_NOT_LOADED;
            if (done == BayStatus.WORKING) {
                wake = true;
            } else {
                setStatus(done);
                return;
            }
        }
        if (!wake && !(halfSecond && status.recheck())) return;
        wake = false;
        tryStart(level);
    }

    private void tryStart(ServerLevel level) {
        if (!Feature.BAYS.on()) {
            setStatus(BayStatus.TURNED_OFF);
            return;
        }
        BlockPos front = front();
        if (!level.isLoaded(front)) {
            setStatus(BayStatus.FRONT_NOT_LOADED);
            return;
        }
        boolean pulseMode = hasRedstoneUpgrade() && redstone == BayRedstone.PULSE;
        if (hasRedstoneUpgrade() && !redstone.allows(signal) || pulseMode && !clock.takePulse()) {
            setStatus(BayStatus.PAUSED);
            return;
        }
        Plan plan = plan(level, front);
        if (plan.status() != BayStatus.WORKING) {
            setStatus(plan.status());
            return;
        }
        if (energy.getAmountAsInt() < powerFor(plan.cost())) {
            setStatus(BayStatus.NO_POWER);
            return;
        }
        int ticks = cycleTicks();
        clock.start(ticks);
        setStatus(BayStatus.WORKING);
        level.blockEvent(worldPosition, getBlockState().getBlock(), EVENT_CYCLE,
                Math.min(ticks, EVENT_SCOOP - 1) | (plan.scoop() ? EVENT_SCOOP : 0));
    }

    /** What an action costing {@code fe} on its own really costs, with this bay's Speed Upgrades. */
    private int powerFor(int fe) {
        return SpeedUpgradeItem.power(fe, speedUpgrades());
    }

    /** Pays {@code fe}, plus the Speed Upgrades' extra, if the buffer holds it. */
    protected boolean pay(int fe) {
        fe = powerFor(fe);
        int amount = energy.getAmountAsInt();
        if (amount < fe) return false;
        if (fe > 0) energy.set(amount - fe);
        return true;
    }

    protected void setStatus(BayStatus now) {
        if (status != now) {
            status = now;
            setChanged();
        }
        syncLooks(looks());
    }

    /** Something changed that may give the bay work. */
    public void wake() {
        wake = true;
    }

    /** A block beside the bay changed: the front, or the redstone signal. */
    public void neighbourChanged(ServerLevel level) {
        boolean now = level.hasNeighborSignal(worldPosition);
        if (now && !signal) clock.pulse();
        signal = now;
        signalKnown = true;
        wake();
    }

    public BayStatus status() {
        return status;
    }

    /** No standing cost: only actions use power. */
    @Override
    public int drainPerTick() {
        return 0;
    }

    /** A bay doesn't take one of its network's machine places. */
    @Override
    public boolean countsTowardLimit() {
        return false;
    }

    @Override
    public boolean running() {
        return status != BayStatus.NO_POWER;
    }

    public Direction facing() {
        return getBlockState().getValue(BayBlock.FACING);
    }

    @Override
    public Direction frontSide() {
        return facing();
    }

    @Override
    public MachineSides sides() {
        return sides;
    }

    @Override
    protected MachineSides.Gated<ItemResource> itemGates() {
        return itemGates;
    }

    @Override
    protected MachineSides.Gated<FluidResource> fluidGates() {
        return fluidGates;
    }

    public BlockPos front() {
        return worldPosition.relative(facing());
    }

    /** Acts as the owner, so claims and spawn protection treat the bay as them. */
    public FakePlayer fakePlayer(ServerLevel level) {
        UUID id = owner() == null ? NOBODY : owner();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(id, "[JASM]"));
        Direction look = facing();
        float yaw = switch (look) {
            case NORTH -> 180F;
            case EAST -> -90F;
            case WEST -> 90F;
            default -> 0F;
        };
        float pitch = look == Direction.DOWN ? 90F : look == Direction.UP ? -90F : 0F;
        player.snapTo(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, yaw, pitch);
        return player;
    }

    // --- upgrades and redstone ---

    public int speedUpgrades() {
        return SpeedUpgradeItem.count(this, UPGRADE_START, SLOTS);
    }

    public boolean hasRedstoneUpgrade() {
        for (int i = UPGRADE_START; i < SLOTS; i++) if (items.get(i).is(JasmItems.REDSTONE_UPGRADE.get())) return true;
        return false;
    }

    public int cycleTicks() {
        return BayClock.ticksFor(speedUpgrades(), JasmConfig.BAY_CYCLE_TICKS.get());
    }

    public BayRedstone redstone() {
        return redstone;
    }

    public void setRedstone(BayRedstone mode) {
        redstone = mode;
        setChanged();
        wake();
    }

    // --- the filter ---

    public WaferSettings filter() {
        return filter;
    }

    // a new filter may let the bay do what it refused, so look at the front again
    public void setFilter(WaferSettings filter) {
        if (this.filter.equals(filter)) return;
        this.filter = filter;
        setChanged();
        wake();
    }

    // components ignored, same as every other filter
    protected boolean passes(ItemStack stack) {
        return filter.rank(stack.getItem()) >= 0;
    }

    protected boolean passes(Fluid fluid) {
        return filter.rank(fluid) >= 0;
    }

    // --- the grid ---

    public ItemStack gridStack(int slot) {
        return items.get(slot);
    }

    public FluidStacksResourceHandler tank() {
        return tank;
    }

    public ResourceHandler<ItemResource> itemAutomation() {
        return itemAutomation;
    }

    public ResourceHandler<FluidResource> fluidAutomation() {
        return fluidAutomation;
    }

    /** Merges as much of {@code stack} into {@code grid} as fits, onto matching stacks first. Shrinks {@code stack}. */
    static void merge(List<ItemStack> grid, ItemStack stack) {
        for (int slot = 0; slot < grid.size() && !stack.isEmpty(); slot++) {
            ItemStack held = grid.get(slot);
            if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, stack)) {
                int moved = Math.min(stack.getCount(), held.getMaxStackSize() - held.getCount());
                held.grow(moved);
                stack.shrink(moved);
            }
        }
        for (int slot = 0; slot < grid.size() && !stack.isEmpty(); slot++) {
            if (grid.get(slot).isEmpty()) grid.set(slot, stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
        }
    }

    /** Whether all of these fit into the grid together. Nothing at all always fits. */
    public boolean fitsAll(List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>(GRID);
        for (int slot = 0; slot < GRID; slot++) copy.add(items.get(slot).copy());
        for (ItemStack stack : stacks) {
            ItemStack rest = stack.copy();
            merge(copy, rest);
            if (!rest.isEmpty()) return false;
        }
        return true;
    }

    /** As much of {@code stack} as fits, onto matching stacks first. Shrinks {@code stack}; returns how many went in. */
    public int insertIntoGridPartly(ItemStack stack) {
        int before = stack.getCount();
        merge(items.subList(0, GRID), stack);
        int moved = before - stack.getCount();
        if (moved > 0) gridChanged();
        return moved;
    }

    /** Call after changing grid stacks in place. */
    protected void gridChanged() {
        setChanged();
        wake();
        syncLooks(looks());
    }

    public int usedSlots() {
        int used = 0;
        for (int slot = 0; slot < GRID; slot++) if (!items.get(slot).isEmpty()) used++;
        return used;
    }

    /** The first stack in the grid the filter lets out; the Deployment Bay's Bitling holds it up, as what it will place next. */
    protected ItemStack held() {
        for (int slot = 0; slot < GRID; slot++) if (!items.get(slot).isEmpty() && passes(items.get(slot))) return items.get(slot);
        return ItemStack.EMPTY;
    }

    /**
     * Status (4 bits), which grid slots hold something (9 bits, one box on the shelf each), whether two Bitlings work
     * here, then a hash of the held item.
     */
    private int looks() {
        ItemStack held = held();
        int boxes = 0;
        for (int slot = 0; slot < GRID; slot++) if (!items.get(slot).isEmpty()) boxes |= 1 << slot;
        return status.ordinal() | boxes << 4 | (BayTiming.pair(cycleTicks()) ? 1 << 13 : 0)
                | Objects.hash(held.getItem(), held.getComponentsPatch()) << 14;
    }

    // --- container ---

    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        gridChanged();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack taken = super.removeItem(slot, amount);
        gridChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack taken = super.removeItemNoUpdate(slot);
        gridChanged();
        return taken;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(kind(), slot, stack, this::hasOtherRedstone);
    }

    private boolean hasOtherRedstone(int except) {
        for (int i = UPGRADE_START; i < SLOTS; i++) {
            if (i != except && items.get(i).is(JasmItems.REDSTONE_UPGRADE.get())) return true;
        }
        return false;
    }

    /**
     * Grid slots take anything on a Deployment Bay and nothing on a Demolition Bay; upgrade slots take Speed Upgrades
     * and one Redstone Upgrade.
     */
    public static boolean accepts(BayKind kind, int slot, ItemStack stack, IntPredicate otherRedstone) {
        if (slot < GRID) return kind.takesIn();
        if (slot >= SLOTS) return false;
        if (stack.is(JasmItems.SPEED_UPGRADE.get())) return true;
        return stack.is(JasmItems.REDSTONE_UPGRADE.get()) && !otherRedstone.test(slot);
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
        return SLOTS;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new BayMenu(kind(), containerId, inventory, this);
    }

    /** Only the grid spills; the upgrades ride on the mined item. The tank's fluid is lost. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) {
            for (int slot = 0; slot < GRID; slot++) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), items.get(slot));
                items.set(slot, ItemStack.EMPTY);
            }
        }
    }

    // --- saving ---

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        tank.serialize(output.child("tank"));
        output.store("redstone", BayRedstone.CODEC, redstone);
        if (!filter.isDefault()) output.store("filter", WaferSettings.CODEC, filter);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        input.child("tank").ifPresent(tank::deserialize);
        redstone = input.read("redstone", BayRedstone.CODEC).orElse(BayRedstone.IGNORE);
        filter = input.read("filter", WaferSettings.CODEC).orElse(WaferSettings.DEFAULT);
        wake = true;
    }

    /** The mined bay keeps its upgrades, redstone mode and filter; the grid drops and is never copied onto the item. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        // Only what was set, so a fresh bay's item still stacks with new ones.
        List<ItemStack> upgrades = items.subList(UPGRADE_START, SLOTS);
        if (upgrades.stream().anyMatch(stack -> !stack.isEmpty())) {
            components.set(JasmComponents.BAY_UPGRADES.get(), ItemContainerContents.fromItems(upgrades));
        }
        if (redstone != BayRedstone.IGNORE) components.set(JasmComponents.BAY_REDSTONE.get(), redstone);
        if (!filter.isDefault()) components.set(JasmComponents.BAY_FILTER.get(), filter);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        ItemContainerContents upgrades = components.getOrDefault(JasmComponents.BAY_UPGRADES.get(), ItemContainerContents.EMPTY);
        NonNullList<ItemStack> list = NonNullList.withSize(UPGRADES, ItemStack.EMPTY);
        upgrades.copyInto(list);
        for (int i = 0; i < UPGRADES; i++) items.set(UPGRADE_START + i, list.get(i));
        redstone = components.getOrDefault(JasmComponents.BAY_REDSTONE.get(), BayRedstone.IGNORE);
        filter = components.getOrDefault(JasmComponents.BAY_FILTER.get(), WaferSettings.DEFAULT);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("redstone");
        output.discard("filter");
    }

    // --- what players' games are told ---

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("looks", looks());
        ItemStack held = held();
        if (!held.isEmpty()) {
            ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), held.copyWithCount(1))
                    .ifSuccess(encoded -> tag.put("held", encoded));
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        BayStatus before = shownStatus();
        shownLooks = input.getIntOr("looks", 0);
        if (shownStatus() == BayStatus.FILTERED && before != BayStatus.FILTERED) {
            clientRefusedSince = level == null ? Long.MIN_VALUE : level.getGameTime();
        }
        shownHeld = input.read("held", ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        handleUpdateTag(input);
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == EVENT_CYCLE) {
            if (level != null && level.isClientSide()) {
                long now = level.getGameTime();
                clientRested = clientCycleStart == Long.MIN_VALUE || now - clientCycleStart > clientCycleTicks + 1;
                clientCycleStart = now;
                clientCycleTicks = param & EVENT_SCOOP - 1;
                clientScoop = (param & EVENT_SCOOP) != 0;
                clientTurn = !clientTurn;
                cycleStartedHere(level);
            }
            return true;
        }
        return super.triggerEvent(id, param);
    }

    public long clientCycleStart() {
        return clientCycleStart;
    }

    public int clientCycleTicks() {
        return clientCycleTicks;
    }

    public boolean clientScoop() {
        return clientScoop;
    }

    public boolean clientTurn() {
        return clientTurn;
    }

    public boolean clientRested() {
        return clientRested;
    }

    /** In this player's game, as a cycle starts. */
    protected void cycleStartedHere(Level level) {}

    /** In each player's game, every tick. */
    public void clientTick(Level level) {}

    public boolean shownPair() {
        return (shownLooks & 1 << 13) != 0;
    }

    public BayStatus shownStatus() {
        return BayStatus.byId(shownLooks & 15);
    }

    /** One bit per grid slot that holds something, slot 0 lowest. */
    public int shownBoxes() {
        return shownLooks >> 4 & 511;
    }

    public ItemStack shownHeld() {
        return shownHeld;
    }

    public long clientRefusedSince() {
        return clientRefusedSince;
    }
}
