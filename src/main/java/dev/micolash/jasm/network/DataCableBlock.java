package dev.micolash.jasm.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jspecify.annotations.Nullable;

/**
 * A Data Cable, undyed or in one of the 16 dye colours. Cables of the same colour join; an undyed cable joins every
 * colour; any cable joins a crafting-network block. Cables also reach toward anything that stores FE, to show where
 * power goes in and out.
 */
public class DataCableBlock extends PipeBlock {
    private final @Nullable DyeColor color;

    public DataCableBlock(BlockBehaviour.Properties properties, @Nullable DyeColor color) {
        super(6.0F, properties);
        this.color = color;
        registerDefaultState(stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
    }

    public @Nullable DyeColor color() {
        return color;
    }

    /** Whether two cables carry data between them: same colour, or either one undyed. */
    public static boolean compatible(@Nullable DyeColor a, @Nullable DyeColor b) {
        return a == null || b == null || a == b;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction side : Direction.values()) {
            BlockPos next = context.getClickedPos().relative(side);
            state = state.setValue(PROPERTY_BY_DIRECTION.get(side), connects(context.getLevel(), next, context.getLevel().getBlockState(next), side));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(direction), connects(level, neighbourPos, neighbourState, direction));
    }

    private boolean connects(BlockGetter level, BlockPos neighbourPos, BlockState neighbour, Direction direction) {
        BlockPos own = neighbourPos.relative(direction.getOpposite());
        if (level instanceof ServerLevel serverLevel && level.getBlockState(own).getBlock() instanceof DataCableBlock
                && (neighbour.getBlock() instanceof DataCableBlock || level.getBlockEntity(neighbourPos) instanceof MachineBlockEntity
                    || level.getBlockEntity(neighbourPos) instanceof dev.micolash.jasm.archive.ArchiveBlockEntity
                    || level.getBlockEntity(neighbourPos) instanceof NetworkPowerSource)) {
            return Networks.canConnect(serverLevel, own, neighbourPos);
        }
        if (neighbour.getBlock() instanceof DataCableBlock other) {
            return compatible(color, other.color);
        }
        if (neighbour.getBlock() instanceof MachineBlock) {
            return true;
        }
        return level instanceof Level world && neighbour.hasBlockEntity()
                && world.getCapability(Capabilities.Energy.BLOCK, neighbourPos, direction.getOpposite()) != null;
    }

    public void refreshConnections(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(this)) {
            return;
        }
        BlockState updated = state;
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            updated = updated.setValue(PROPERTY_BY_DIRECTION.get(side), connects(level, next, level.getBlockState(next), side));
        }
        if (state != updated) {
            level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            Networks.placedCable(serverLevel, pos);
            Networks.invalidate(serverLevel);
            refreshConnections(serverLevel, pos);
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (serverLevel.getBlockState(next).getBlock() instanceof DataCableBlock cable) {
                    cable.refreshConnections(serverLevel, next);
                }
            }
            // Tells blocks next to it (a generator, say) that power can go in here now.
            serverLevel.invalidateCapabilities(pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        if (!(level.getBlockState(pos).getBlock() instanceof DataCableBlock)) {
            CableClaims.get(level).remove(level, pos);
        }
        Networks.invalidate(level);
        level.invalidateCapabilities(pos);
    }
}
