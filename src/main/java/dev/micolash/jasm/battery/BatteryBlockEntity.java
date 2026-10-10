package dev.micolash.jasm.battery;

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
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * One block of a battery. It holds its own share of the power, saved with it and carried by the mined item, so joining,
 * splitting or breaking a battery never makes or loses any. Everything else goes through its {@link BatteryGroup}.
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
    private @Nullable BatteryGroup group;
    /** Whether the blocks around have heard of this one since it was placed or loaded. */
    private boolean announced;

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
        }
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

    // set by the battery it joins. what it holds stays, even above the new room
    void resize(int room) {
        if (energy.getCapacityAsInt() != room) energy.resize(room);
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
        return new BatteryMenu(containerId, this, ContainerLevelAccess.create(level, worldPosition));
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
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy.set(Math.clamp(input.getIntOr("energy", 0), 0, BatteryGroup.MOST_PER_BLOCK));
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
