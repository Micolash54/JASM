package dev.micolash.jasm.acceptor;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.PowerSourceBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** The one way other mods' power gets into a network: draws FE from blocks beside it and feeds touching cables and machines. */
public class PowerAcceptorBlock extends PowerSourceBlock {
    @Override
    protected MapCodec<PowerAcceptorBlock> codec() {
        return simpleCodec(PowerAcceptorBlock::new);
    }

    public PowerAcceptorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** Nothing to open, so a click leaves room to place blocks against it. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return InteractionResult.PASS;
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
