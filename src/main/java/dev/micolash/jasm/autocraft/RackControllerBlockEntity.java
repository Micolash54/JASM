package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.ContainerWords;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Shows every Recipe Rack on its network in one panel. It holds no items of its own; the cards stay in their racks. */
public class RackControllerBlockEntity extends MachineBlockEntity {
    public static final int CAPACITY = 5_000;

    private NonNullList<ItemStack> items = NonNullList.withSize(0, ItemStack.EMPTY);

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case RackControllerMenu.DATA_ENERGY_LOW -> ContainerWords.low(energy.getAmountAsInt());
                case RackControllerMenu.DATA_ENERGY_HIGH -> ContainerWords.high(energy.getAmountAsInt());
                case RackControllerMenu.DATA_RUNNING -> running() ? 1 : 0;
                case RackControllerMenu.DATA_DRAIN -> drainPerTick();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return RackControllerMenu.DATA_COUNT;
        }
    };

    public RackControllerBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.RACK_CONTROLLER_ENTITY.get(), pos, state, CAPACITY);
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.RACK_CONTROLLER_DRAIN.getAsInt();
    }

    public ContainerData data() {
        return data;
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
        return 0;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new RackControllerMenu(containerId, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }
}
