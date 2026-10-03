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
import org.jspecify.annotations.Nullable;

/** The Encoding Terminal. Only its owner and the players they trust can open it. */
public class EncodingTerminalBlock extends MachineBlock {
    @Override
    protected MapCodec<EncodingTerminalBlock> codec() {
        return simpleCodec(EncodingTerminalBlock::new);
    }

    public EncodingTerminalBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EncodingTerminalBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, JasmBlocks.ENCODING_TERMINAL_ENTITY.get(), EncodingTerminalBlockEntity::serverTick);
    }
}
