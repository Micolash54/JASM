package dev.micolash.jasm.crystal;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** Every so many powered ticks, gives each touching Seeded Amethyst one extra growth attempt. */
public class ResonatorBlockEntity extends MachineBlockEntity {
    public static final int CAPACITY = 10_000;

    /** Powered ticks since the last pulse. */
    private int sincePulse;

    public ResonatorBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.CRYSTAL_RESONATOR_ENTITY.get(), pos, state, CAPACITY);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ResonatorBlockEntity resonator) {
        if (resonator.payForTick() && ++resonator.sincePulse >= JasmConfig.RESONATOR_INTERVAL.getAsInt()) {
            resonator.sincePulse = 0;
            resonator.pulse((ServerLevel) level);
        }
    }

    private void pulse(ServerLevel level) {
        for (Direction side : Direction.values()) {
            BlockPos next = worldPosition.relative(side);
            BlockState there = level.getBlockState(next);
            if (there.getBlock() instanceof SeededAmethystBlock seeded) {
                seeded.grow(there, level, next, level.getRandom());
            }
        }
    }

    @Override
    public int drainPerTick() {
        return JasmConfig.RESONATOR_DRAIN.getAsInt();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("since_pulse", sincePulse);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        sincePulse = input.getIntOr("since_pulse", 0);
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return NonNullList.create();
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {}

    @Override
    public int getContainerSize() {
        return 0;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return null;
    }
}
