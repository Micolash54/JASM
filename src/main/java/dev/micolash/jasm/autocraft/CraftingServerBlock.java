package dev.micolash.jasm.autocraft;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import org.jspecify.annotations.Nullable;

/** The Crafting Server. Pistons can't move it, so a running job never ends up somewhere else. */
public class CraftingServerBlock extends MachineBlock {
    @Override
    protected MapCodec<CraftingServerBlock> codec() {
        return simpleCodec(CraftingServerBlock::new);
    }

    public CraftingServerBlock(BlockBehaviour.Properties properties) {
        super(properties.pushReaction(PushReaction.BLOCK));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CraftingServerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.CRAFTING_SERVER_ENTITY.get(), CraftingServerBlockEntity::serverTick);
    }
}
