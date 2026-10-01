package dev.micolash.jasm.station;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** The Bitling Station: a low charging pad that a roaming Bitling comes home to. */
public class BitlingStationBlock extends MachineBlock {
    /** How high the pad is, in blocks: the Bitling stands on top of it. */
    public static final double PAD_HEIGHT = 4 / 16.0;
    private static final VoxelShape SHAPE = Shapes.box(0, 0, 0, 1, PAD_HEIGHT, 1);

    @Override
    protected MapCodec<BitlingStationBlock> codec() {
        return simpleCodec(BitlingStationBlock::new);
    }

    public BitlingStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BitlingStationBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.BITLING_STATION_ENTITY.get(), BitlingStationBlockEntity::serverTick);
    }
}
