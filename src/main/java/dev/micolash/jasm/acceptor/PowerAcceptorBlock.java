package dev.micolash.jasm.acceptor;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.PowerSourceBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** The one way power moves between a network and other mods' blocks, in either direction. Its screen picks which. */
public class PowerAcceptorBlock extends PowerSourceBlock {
    @Override
    protected MapCodec<? extends PowerAcceptorBlock> codec() {
        return simpleCodec(PowerAcceptorBlock::new);
    }

    public PowerAcceptorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PowerAcceptorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, JasmBlocks.POWER_ACCEPTOR_ENTITY.get(), PowerAcceptorBlockEntity::serverTick);
    }
}
