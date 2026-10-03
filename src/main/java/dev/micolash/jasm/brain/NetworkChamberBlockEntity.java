package dev.micolash.jasm.brain;

import dev.micolash.jasm.core.BrainFloor;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import org.jspecify.annotations.Nullable;

/**
 * A Network Chamber. Part of a brain floor, it passes power and clicks on to the brain. On its own it holds nothing and
 * does nothing, but it still carries its network like any machine. It never counts toward the limit.
 */
public class NetworkChamberBlockEntity extends MachineBlockEntity {
    private @Nullable BlockPos brainPos;

    public NetworkChamberBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.NETWORK_CHAMBER_ENTITY.get(), pos, state, 0);
    }

    /** The brain whose floor this chamber is part of, while it is loaded. */
    public @Nullable NetworkBrainBlockEntity brain() {
        if (brainPos == null || level == null || !level.isLoaded(brainPos)) {
            return null;
        }
        if (level.getBlockEntity(brainPos) instanceof NetworkBrainBlockEntity brain && brain.floor() && BrainFloor.contains(brainPos.getX(),
                brainPos.getY(), brainPos.getZ(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ())) {
            return brain;
        }
        return null;
    }

    public @Nullable BlockPos brainPos() {
        return brainPos;
    }

    /** Joins a brain's floor, or leaves it with null. */
    public void claim(@Nullable BlockPos brain) {
        brainPos = brain == null ? null : brain.immutable();
        if (level != null) {
            BlockState state = level.getBlockState(worldPosition);
            if (state.hasProperty(NetworkChamberBlock.FORMED) && state.getValue(NetworkChamberBlock.FORMED) != (brain != null)) {
                level.setBlock(worldPosition, state.setValue(NetworkChamberBlock.FORMED, brain != null), Block.UPDATE_ALL);
            }
        }
        setChanged();
    }

    /** A formed chamber shares its brain's power, so a cable on any side of the floor charges the brain. */
    @Override
    public SimpleEnergyHandler energy() {
        NetworkBrainBlockEntity brain = brain();
        return brain != null ? brain.energy() : super.energy();
    }

    @Override
    public int drainPerTick() {
        return 0;
    }

    @Override
    public boolean countsTowardLimit() {
        return false;
    }

    // --- container: it holds nothing ---

    @Override
    protected NonNullList<ItemStack> getItems() {
        return NonNullList.create();
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
    }

    @Override
    public int getContainerSize() {
        return 0;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.jasm.network_chamber");
    }

    /** It has no screen of its own; a click opens its brain's. */
    @Override
    protected @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return null;
    }

    // --- saving ---

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("brain", BlockPos.CODEC, brainPos);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        brainPos = input.read("brain", BlockPos.CODEC).orElse(null);
    }
}
