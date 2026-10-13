package dev.micolash.jasm.battery;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.NetworkEnergy;
import dev.micolash.jasm.network.NetworkPowerSource;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.DelegatingResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * One block of a battery. It holds its own share of the power, saved with it and carried by the mined item, so joining
 * or unloading a battery never makes or loses any. Breaking a block trims what the rest holds to the smaller battery's room. Everything else goes through its {@link BatteryGroup}.
 * The battery has one charging slot, filled from its whole power. The item is kept by one of its blocks, the one
 * {@link BatteryGroup#holder()} names, and drops when that block breaks. Every block's screen and hoppers reach that slot.
 */
public class BatteryBlockEntity extends BlockEntity implements NetworkPowerSource, MenuProvider {
    /** FE one block holds on its own. Joined to others it holds a little more, see {@link BatteryGroup#roomPerBlock}. */
    public static final int CAPACITY = 2_000_000;

    private final NetworkEnergy energy = new NetworkEnergy(CAPACITY) {
        @Override
        protected void onEnergyChanged(int previousAmount) {
            if (level != null) level.blockEntityChanged(worldPosition);
            if (group != null && group.alive()) group.changed((long) getAmountAsInt() - previousAmount);
        }
    };
    private final EnergyHandler groupEnergy = new GroupEnergy();
    /** What this block keeps; only the holder's is ever filled. */
    private final SimpleContainer slot = CreativeBatteryMenu.chargingSlot(this::setChanged);
    private final ResourceHandler<ItemResource> slotHandler = VanillaContainerWrapper.of(slot);
    private final Container batterySlot = new BatterySlot();
    private final ResourceHandler<ItemResource> automation = new AutomationSlot();
    private @Nullable BatteryGroup group;
    /** Whether the blocks around have heard of this one since it was placed or loaded. */
    private boolean announced;
    /** A block of its battery was broken: the next resize trims what it holds to the new room. */
    private boolean trim;

    public BatteryBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.BATTERY_ENTITY.get(), pos, state);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, BatteryBlockEntity battery) {
        ServerLevel serverLevel = (ServerLevel) level;
        if (!battery.announced) {
            battery.announced = true;
            battery.announce(serverLevel);
        }
        BatteryGroup group = battery.group(serverLevel);
        if (group.leader() == battery) {
            group.tick(serverLevel);
            battery.charge(group);
        }
    }

    /** FE a tick the charging slot gives in a battery of {@code size} blocks. */
    public static int chargeRate(int size) {
        long rate = JasmConfig.orDefault(JasmConfig.BATTERY_CHARGE_BASE)
                + (long) size * JasmConfig.orDefault(JasmConfig.BATTERY_CHARGE_PER_BLOCK);
        return (int) Math.min(rate, JasmConfig.orDefault(JasmConfig.BATTERY_CHARGE_MAX));
    }

    /**
     * Charges the item in the battery's slot from its whole power, by its size's rate and never more than it holds. Run
     * once a tick by the leader. Returns how much went in.
     */
    public int charge(BatteryGroup group) {
        BatteryBlockEntity holder = group.holder();
        if (holder.slot.getItem(0).isEmpty()) {
            return 0;
        }
        int amount = (int) Math.min(chargeRate(group.size()), group.stored());
        if (amount <= 0) {
            return 0;
        }
        EnergyHandler item = ItemAccess.forHandlerIndexStrict(holder.slotHandler, 0).getCapability(Capabilities.Energy.ITEM);
        if (item == null) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int accepted = item.insert(amount, tx);
            if (accepted <= 0 || group.energy().extract(accepted, tx) != accepted) {
                return 0;
            }
            tx.commit();
            return accepted;
        }
    }

    /** The battery's charging slot, whichever block keeps it. */
    public Container chargingSlot() {
        return batterySlot;
    }

    boolean holdsItem() {
        return !slot.isEmpty();
    }

    /** A second item in one battery (two joined, or saved when every block had a slot) drops out here, as breaking would drop it. */
    void popItem(ServerLevel level) {
        Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), slot.removeItemNoUpdate(0));
        setChanged();
    }

    /** What hoppers and pipes see. */
    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    /**
     * Placed or loaded: the batteries beside it join up with it, and the networks of the cables beside it look again for
     * batteries. Done on the first tick, not while the chunk is still loading. A block set without a player (commands,
     * structures) gets its joined sides here too.
     */
    private void announce(ServerLevel level) {
        BlockState state = getBlockState();
        BlockState joined = BatteryBlock.joined(state, level, worldPosition);
        if (joined != state) {
            level.setBlock(worldPosition, joined, Block.UPDATE_CLIENTS);
        }
        BatteryGroup.forgetAround(level, worldPosition);
        for (Direction side : Direction.values()) {
            BlockPos next = worldPosition.relative(side);
            if (level.isLoaded(next) && level.getBlockEntity(next) instanceof DataCableBlockEntity) {
                Networks.invalidate(level, next);
            }
        }
    }

    /** The battery this block is part of, worked out again only after a battery beside it came or went. */
    public BatteryGroup group(ServerLevel level) {
        if (group == null || !group.alive()) {
            group = BatteryGroup.of(level, this);
        }
        group.refreshCapacity();
        return group;
    }

    /** Only this block's own share. */
    public SimpleEnergyHandler energy() {
        return energy;
    }

    // set by the battery it joins. what it holds stays, even above the new room, unless a block of it was broken
    void resize(int room) {
        if (energy.getCapacityAsInt() != room) energy.resize(room);
        if (trim) {
            trim = false;
            if (energy.getAmountAsInt() > room) energy.set(room);
        }
    }

    /** The whole battery: what goes in or out is spread over all its blocks. */
    public EnergyHandler groupEnergy() {
        return groupEnergy;
    }

    @Override
    public void neighboursChanged() {
        if (group != null) {
            group.sidesChanged();
        }
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new BatteryMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) {
            BatteryGroup.forgetAround(serverLevel, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("energy", energy.getAmountAsInt());
        ItemStack stack = slot.getItem(0);
        if (!stack.isEmpty()) {
            output.store("charging", ItemStack.CODEC, stack);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, BatteryGroup.MOST_PER_BLOCK));
        slot.setItem(0, input.read("charging", ItemStack.CODEC).orElse(ItemStack.EMPTY));
    }

    /**
     * Breaking the block (or replacing it) drops the item it keeps, and the rest of the battery keeps no more than the
     * smaller battery's room. Not called when the chunk unloads, so a half-loaded battery keeps all its power.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) {
            Containers.dropContents(level, pos, slot);
        }
        if (level instanceof ServerLevel serverLevel && !isRemoved()) {
            for (BatteryBlockEntity block : group(serverLevel).blocks()) {
                if (block != this) block.trim = true;
            }
        }
    }

    /** The mined item carries this block's share. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (energy.getAmountAsInt() > 0) {
            components.set(JasmComponents.ENERGY.get(), energy.getAmountAsInt());
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        energy.set(Math.clamp(components.getOrDefault(JasmComponents.ENERGY.get(), 0), 0, BatteryGroup.MOST_PER_BLOCK));
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        output.discard("energy");
    }

    /**
     * The battery's slot seen from this block: the holder's. With no battery to reach (this block is gone) it is empty
     * and takes nothing; anything still set into it drops rather than vanish.
     */
    private final class BatterySlot implements Container {
        private @Nullable SimpleContainer target() {
            return !isRemoved() && level instanceof ServerLevel serverLevel ? group(serverLevel).holder().slot : null;
        }

        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            SimpleContainer target = target();
            return target == null || target.isEmpty();
        }

        @Override
        public ItemStack getItem(int index) {
            SimpleContainer target = target();
            return target == null ? ItemStack.EMPTY : target.getItem(index);
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            SimpleContainer target = target();
            return target == null ? ItemStack.EMPTY : target.removeItem(index, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            SimpleContainer target = target();
            return target == null ? ItemStack.EMPTY : target.removeItemNoUpdate(index);
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            SimpleContainer target = target();
            if (target != null) {
                target.setItem(index, stack);
            } else if (!stack.isEmpty() && level != null) {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
            }
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int index, ItemStack stack) {
            SimpleContainer target = target();
            return target != null && target.canPlaceItem(index, stack);
        }

        @Override
        public void setChanged() {
            SimpleContainer target = target();
            if (target != null) target.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            SimpleContainer target = target();
            if (target != null) target.clearContent();
        }
    }

    /** The charging slot as hoppers see it: chargeable items in, only full items out. */
    private final class AutomationSlot extends DelegatingResourceHandler<ItemResource> {
        AutomationSlot() {
            super(VanillaContainerWrapper.of(batterySlot));
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return CreativeBatteryMenu.canCharge(resource.toStack(1)) ? super.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            return CreativeBatteryMenu.canCharge(resource.toStack(1)) ? super.insert(resource, amount, transaction) : 0;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return CreativeBatteryBlockEntity.isFull(batterySlot.getItem(0), transaction) ? super.extract(index, resource, amount, transaction) : 0;
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            return CreativeBatteryBlockEntity.isFull(batterySlot.getItem(0), transaction) ? super.extract(resource, amount, transaction) : 0;
        }
    }

    /** Hands everything to the current group, so it stays right when the battery is joined or split. */
    private final class GroupEnergy implements EnergyHandler {
        private @Nullable EnergyHandler current() {
            return !isRemoved() && level instanceof ServerLevel serverLevel ? group(serverLevel).energy() : null;
        }

        @Override
        public long getAmountAsLong() {
            EnergyHandler current = current();
            return current == null ? 0 : current.getAmountAsLong();
        }

        @Override
        public long getCapacityAsLong() {
            EnergyHandler current = current();
            return current == null ? 0 : current.getCapacityAsLong();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            EnergyHandler current = current();
            return current == null ? 0 : current.insert(amount, transaction);
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            EnergyHandler current = current();
            return current == null ? 0 : current.extract(amount, transaction);
        }
    }
}
