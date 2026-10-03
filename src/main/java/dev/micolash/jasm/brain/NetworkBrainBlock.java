package dev.micolash.jasm.brain;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jspecify.annotations.Nullable;

/** The Network Brain: lets its network hold more machines. With 8 chambers round it, it is a floor of a brain tower. */
public class NetworkBrainBlock extends MachineBlock {
    /** The middle of a complete floor. */
    public static final BooleanProperty FLOOR = BooleanProperty.create("floor");
    /** Powered and leading its network. */
    public static final BooleanProperty AWAKE = BooleanProperty.create("awake");

    @Override
    protected MapCodec<NetworkBrainBlock> codec() {
        return simpleCodec(NetworkBrainBlock::new);
    }

    public NetworkBrainBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FLOOR, false).setValue(AWAKE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FLOOR, AWAKE);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkBrainBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.NETWORK_BRAIN_ENTITY.get(), NetworkBrainBlockEntity::serverTick);
    }
}
