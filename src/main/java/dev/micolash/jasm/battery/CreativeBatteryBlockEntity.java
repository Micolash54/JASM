package dev.micolash.jasm.battery;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.network.NetworkPowerSource;
import dev.micolash.jasm.network.SourceOwnership;
import dev.micolash.jasm.network.Networks;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.DelegatingResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Holds the one item being charged. Every tick it pushes FE into each touching block that accepts it and charges
 * that item. Hoppers and pipes may put in any item that stores FE, and may only take it out once it is full.
 */
public class CreativeBatteryBlockEntity extends BlockEntity implements MenuProvider, NetworkPowerSource {
    private final SourceOwnership ownership = new SourceOwnership(this);
    private final SimpleContainer slot = CreativeBatteryMenu.chargingSlot(this::setChanged);
    private final ResourceHandler<ItemResource> slotHandler = VanillaContainerWrapper.of(slot);
    private final ResourceHandler<ItemResource> automation = new AutomationSlot();
    private final Map<Direction, BlockCapabilityCache<EnergyHandler, @Nullable Direction>> neighbours = new EnumMap<>(Direction.class);

    public CreativeBatteryBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.CREATIVE_BATTERY_ENTITY.get(), pos, state);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, CreativeBatteryBlockEntity battery) {
        battery.pushToNeighbours((ServerLevel) level);
        battery.charge(JasmConfig.BATTERY_CHARGE_PER_TICK.getAsInt());
    }

    /** One tick of output: up to the per-side limit into each touching block that takes FE. */
    @Override
    public SourceOwnership networkOwnership() {
        return ownership;
    }

    public void pushToNeighbours(ServerLevel level) {
        Networks.at(level, worldPosition);
        int amount = JasmConfig.BATTERY_PUSH_PER_FACE_PER_TICK.getAsInt();
        if (amount <= 0) {
            return;
        }
        for (Direction side : Direction.values()) {
            BlockPos next = worldPosition.relative(side);
            if (Networks.at(level, next) != null && !Networks.canConnect(level, worldPosition, next)) {
                continue;
            }
            EnergyHandler target = neighbours
                    .computeIfAbsent(side, s -> BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, worldPosition.relative(s), s.getOpposite()))
                    .getCapability();
            if (target != null) {
                try (Transaction tx = Transaction.openRoot()) {
                    target.insert(amount, tx);
                    tx.commit();
                }
            }
        }
    }

    /** Charges the item in the slot by up to {@code amount} FE and returns how much went in. */
    public int charge(int amount) {
        if (amount <= 0 || slot.getItem(0).isEmpty()) {
            return 0;
        }
        EnergyHandler item = ItemAccess.forHandlerIndexStrict(slotHandler, 0).getCapability(Capabilities.Energy.ITEM);
        if (item == null) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int inserted = item.insert(amount, tx);
            tx.commit();
            return inserted;
        }
    }

    public SimpleContainer chargingSlot() {
        return slot;
    }

    /** What hoppers and pipes see. */
    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    /**
     * Whether the item can take no more charge: it says it is full, or it refuses even 1 FE more. Checked on a copy,
     * inside {@code parent} when called during a transfer.
     */
    public static boolean isFull(ItemStack stack, @Nullable TransactionContext parent) {
        EnergyHandler battery = CreativeBatteryMenu.batteryOf(stack);
        if (battery == null) {
            return false;
        }
        if (battery.getAmountAsLong() >= battery.getCapacityAsLong()) {
            return true;
        }
        try (Transaction tx = Transaction.open(parent)) {
            return battery.insert(1, tx) == 0;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ownership.save(output);
        ItemStack stack = slot.getItem(0);
        if (!stack.isEmpty()) {
            output.store("charging", ItemStack.CODEC, stack);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ownership.load(input);
        slot.setItem(0, input.read("charging", ItemStack.CODEC).orElse(ItemStack.EMPTY));
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        ownership.collect(components);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        ownership.apply(components);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("network_owner");
    }

    /** Breaking the block (or replacing it) drops the item being charged. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) {
            Containers.dropContents(level, pos, slot);
        }
    }

    /** The charging slot as hoppers see it: chargeable items in, full items out. */
    private final class AutomationSlot extends DelegatingResourceHandler<ItemResource> {
        AutomationSlot() {
            super(slotHandler);
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
            return isFull(slot.getItem(0), transaction) ? super.extract(index, resource, amount, transaction) : 0;
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            return isFull(slot.getItem(0), transaction) ? super.extract(resource, amount, transaction) : 0;
        }
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new CreativeBatteryMenu(containerId, inventory, slot, ContainerLevelAccess.create(level, worldPosition));
    }
}
