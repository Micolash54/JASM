package dev.micolash.jasm.generator;

import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.battery.CreativeBatteryMenu;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.NetworkPowerSource;
import dev.micolash.jasm.network.PowerSides;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.DelegatingResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.LimitingEnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Burns one fuel item at a time for as long as a furnace would, adding FE to its buffer every tick. It only burns
 * while the buffer has room, so fuel is never wasted: a full generator keeps its flame banked until power is used.
 * Every tick it pushes FE into touching blocks that take it and charges the item in its charging slot.
 */
public class CombustionGeneratorBlockEntity extends BaseContainerBlockEntity implements NetworkPowerSource {

    public static final int FUEL_SLOT = 0;
    public static final int CHARGE_SLOT = 1;
    private final GeneratorTier tier;
    private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
    private final SimpleEnergyHandler energy;
    private final EnergyHandler output;
    private final ResourceHandler<ItemResource> slots = VanillaContainerWrapper.of(this);
    private final ResourceHandler<ItemResource> automation = new Automation();
    private final Map<Direction, BlockCapabilityCache<EnergyHandler, @Nullable Direction>> neighbours = new EnumMap<>(Direction.class);
    private final PowerSides sides = new PowerSides();
    private int burnLeft;
    private int burnTotal;
    /** FE made in the last tick, for the screen. */
    private int lastOutput;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case CombustionGeneratorMenu.DATA_ENERGY_LOW -> ContainerWords.low(energy.getAmountAsInt());
                case CombustionGeneratorMenu.DATA_ENERGY_HIGH -> ContainerWords.high(energy.getAmountAsInt());
                case CombustionGeneratorMenu.DATA_FLAME -> burnTotal <= 0 ? 0 : (int) Math.ceil(burnLeft * 1000.0 / burnTotal);
                case CombustionGeneratorMenu.DATA_OUTPUT -> lastOutput;
                case CombustionGeneratorMenu.DATA_CAPACITY_LOW -> ContainerWords.low(tier.capacity());
                case CombustionGeneratorMenu.DATA_CAPACITY_HIGH -> ContainerWords.high(tier.capacity());
                case CombustionGeneratorMenu.DATA_POTENTIAL -> tier.fePerTick();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return CombustionGeneratorMenu.DATA_COUNT;
        }
    };

    public CombustionGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.COMBUSTION_GENERATOR_ENTITY.get(), pos, state);
        this.tier = state.getBlock() instanceof CombustionGeneratorBlock block ? block.tier() : GeneratorTier.BASIC;
        this.energy = new SimpleEnergyHandler(tier.capacity(), tier.capacity(), tier.capacity()) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                setChanged();
            }
        };
        this.output = new LimitingEnergyHandler(energy, 0, tier.capacity());
    }

    public GeneratorTier tier() {
        return tier;
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, CombustionGeneratorBlockEntity generator) {
        ServerLevel serverLevel = (ServerLevel) level;
        generator.burn(serverLevel);
        generator.pushToNeighbours(serverLevel);
        generator.charge(generator.tier.transferPerTick());
        boolean lit = generator.burnLeft > 0;
        if (state.getValue(CombustionGeneratorBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(CombustionGeneratorBlock.LIT, lit), 3);
        }
    }

    /** One tick of burning. Starts the next fuel item only when the buffer has room for what it makes. */
    public void burn(ServerLevel level) {
        int rate = tier.fePerTick();
        lastOutput = 0;
        if (tier.capacity() - energy.getAmountAsInt() < rate) {
            return;
        }
        if (burnLeft <= 0) {
            ItemStack fuel = items.get(FUEL_SLOT);
            int duration = burnDuration(level, fuel);
            if (duration <= 0) {
                burnTotal = 0;
                return;
            }
            burnLeft = burnTotal = duration;
            consumeFuel(level, fuel);
        }
        burnLeft--;
        energy.set(energy.getAmountAsInt() + rate);
        lastOutput = rate;
    }

    /** How long this item burns here, in ticks: its furnace burn time, sped up by the tier. */
    public int burnDuration(ServerLevel level, ItemStack fuel) {
        return fuel.isEmpty()
                ? 0
                : tier.burnTicks(fuel.getBurnTime(RecipeType.SMELTING, level.fuelValues()));
    }

    /** Uses up one fuel item, leaving what a furnace leaves (a lava bucket leaves its bucket). */
    private void consumeFuel(ServerLevel level, ItemStack fuel) {
        ItemStackTemplate remainder = fuel.getCraftingRemainder();
        fuel.shrink(1);
        if (remainder != null) {
            if (fuel.isEmpty()) {
                items.set(FUEL_SLOT, remainder.create());
            } else {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY() + 1, worldPosition.getZ(), remainder.create());
            }
        }
        setChanged();
    }

    /** Up to the per-side limit into each touching block that takes FE. JASM blocks are handed it directly. */
    public void pushToNeighbours(ServerLevel level) {
        int limit = tier.transferPerTick();
        sides.refresh(level, worldPosition);
        for (Direction side : Direction.values()) {
            if (energy.getAmountAsInt() <= 0) {
                return;
            }
            EnergyHandler target = sides.receiver(side);
            if (target == null && !sides.jasm(side)) {
                target = neighbours
                        .computeIfAbsent(side,
                                s -> BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, worldPosition.relative(s), s.getOpposite()))
                        .getCapability();
            }
            if (target != null) {
                give(target, limit);
            }
        }
    }

    /** Charges the item in the charging slot by up to {@code amount} FE from the buffer. Returns how much went in. */
    public int charge(int amount) {
        if (amount <= 0 || items.get(CHARGE_SLOT).isEmpty()) {
            return 0;
        }
        EnergyHandler item = ItemAccess.forHandlerIndexStrict(slots, CHARGE_SLOT).getCapability(Capabilities.Energy.ITEM);
        return item == null ? 0 : give(item, amount);
    }

    /** Moves up to {@code amount} FE from the buffer into {@code target}, all or nothing per call. */
    private int give(EnergyHandler target, int amount) {
        try (Transaction tx = Transaction.openRoot()) {
            int accepted = target.insert(Math.min(amount, energy.getAmountAsInt()), tx);
            if (accepted <= 0 || energy.extract(accepted, tx) != accepted) {
                return 0;
            }
            tx.commit();
            return accepted;
        }
    }

    public SimpleEnergyHandler energy() {
        return energy;
    }

    /** What other blocks see: they may take power, never put it in. */
    public EnergyHandler output() {
        return output;
    }

    /** What hoppers and pipes see. */
    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    @Override
    public EnergyHandler networkOutput() {
        return output;
    }

    @Override
    public void neighboursChanged() {
        sides.changed();
    }

    public int burnLeft() {
        return burnLeft;
    }

    public static boolean isFuel(ItemStack stack, Level level) {
        return !stack.isEmpty() && level != null && stack.getBurnTime(RecipeType.SMELTING, level.fuelValues()) > 0;
    }

    /** Which items each slot takes: anything a furnace burns, and items that store FE. */
    public static boolean accepts(int slot, ItemStack stack, Level level) {
        return slot == FUEL_SLOT ? isFuel(stack, level) : CreativeBatteryMenu.canCharge(stack);
    }

    // --- container ---

    @Override
    public int getContainerSize() {
        return items.size();
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
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(slot, stack, level);
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new CombustionGeneratorMenu(containerId, inventory, this, data, ContainerLevelAccess.create(level, worldPosition),
                getBlockState().getBlock());
    }

    // --- saving ---

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("energy", energy.getAmountAsInt());
        output.putInt("burn_left", burnLeft);
        output.putInt("burn_total", burnTotal);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, tier.capacity()));
        burnLeft = Math.max(0, input.getIntOr("burn_left", 0));
        burnTotal = Math.max(burnLeft, input.getIntOr("burn_total", 0));
    }

    /** The mined item keeps the charge. Fuel and the charging item drop on the ground. */
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
        energy.set(Math.clamp(components.getOrDefault(JasmComponents.ENERGY.get(), 0), 0, tier.capacity()));
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("energy");
    }

    /**
     * Hoppers and pipes: fuel goes into the fuel slot, items that store FE into the charging slot. Out come
     * charged items once they are full, and what burnt fuel leaves behind (an empty bucket).
     */
    private final class Automation extends DelegatingResourceHandler<ItemResource> {
        Automation() {
            super(slots);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            if (!accepts(index, resource.toStack(1), level)) {
                return 0;
            }
            if (index == CHARGE_SLOT) {
                // One item at a time is charged.
                amount = items.get(CHARGE_SLOT).isEmpty() ? Math.min(amount, 1) : 0;
            }
            return super.insert(index, resource, amount, transaction);
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            ItemStack one = resource.toStack(1);
            int index = isFuel(one, level) ? FUEL_SLOT : CreativeBatteryMenu.canCharge(one) ? CHARGE_SLOT : -1;
            return index < 0 ? 0 : insert(index, resource, amount, transaction);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            ItemStack stack = items.get(index);
            boolean allowed = index == FUEL_SLOT ? !isFuel(stack, level) : CreativeBatteryBlockEntity.isFull(stack, transaction);
            return allowed ? super.extract(index, resource, amount, transaction) : 0;
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            int taken = extract(CHARGE_SLOT, resource, amount, transaction);
            return taken > 0 ? taken : extract(FUEL_SLOT, resource, amount, transaction);
        }
    }
}
