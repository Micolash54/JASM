package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** The Recipe Rack: 16 Filled Recipe Cards for its network. */
public class RecipeRackBlock extends MachineBlock {
    public RecipeRackBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RecipeRackBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.RECIPE_RACK_ENTITY.get(), RecipeRackBlockEntity::serverTick);
    }
}
