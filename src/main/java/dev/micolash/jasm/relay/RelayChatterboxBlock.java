package dev.micolash.jasm.relay;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The Relay Chatterbox: two blocks tall, placed and broken as one, the same way a door is. The lower half is the end of
 * a pair and holds everything; cables join either half. Pistons can't move it.
 */
public class RelayChatterboxBlock extends MachineBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    /** The room, its front window set a pixel back behind the frame, as the model has it. */
    private static final Map<Direction, VoxelShape> LOWER_SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(0, 0, 1, 16, 16, 16), Block.box(0, 0, 0, 1.5, 16, 1), Block.box(14.5, 0, 0, 16, 16, 1),
            Block.box(1.5, 0, 0, 14.5, 2.2, 1), Block.box(1.5, 14.5, 0, 14.5, 16, 1)));
    /** The roof deck, and the dish on its yoke. */
    private static final Map<Direction, VoxelShape> UPPER_SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(0, 0, 0, 16, 1.5, 16), Block.box(0, 1.5, 1.5, 16, 16, 13)));

    @Override
    protected MapCodec<RelayChatterboxBlock> codec() {
        return simpleCodec(RelayChatterboxBlock::new);
    }

    public RelayChatterboxBlock(BlockBehaviour.Properties properties) {
        super(properties.pushReaction(PushReaction.BLOCK));
        registerDefaultState(defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HALF);
    }

    /**
     * The shape the model has. Neither half is a full cube, on purpose: the game lights every face of a full-cube block
     * from the block beside it, which darkened the room's inside walls whenever a block stood against the far side.
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (isLower(state) ? LOWER_SHAPES : UPPER_SHAPES).get(state.getValue(FACING));
    }

    public static boolean isLower(BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER;
    }

    /** Where the lower half is, from either half. */
    public static BlockPos lowerPos(BlockState state, BlockPos pos) {
        return isLower(state) ? pos : pos.below();
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxY() || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        return super.getStateForPlacement(context);
    }

    /** Puts the upper half on, then tells the pairing once the carried frequency and items are back in. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        super.setPlacedBy(level, pos, state, by, itemStack);
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof RelayChatterboxBlockEntity relay) {
            RelayLinks.changed(relay);
        }
    }

    /** A half without its other half turns to air; the lower one drops the block as it goes. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction directionToNeighbour,
            BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        boolean lower = isLower(state);
        if (directionToNeighbour == (lower ? Direction.UP : Direction.DOWN)) {
            return neighbourState.is(this) && isLower(neighbourState) != lower ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    /**
     * Broken from the top in creative or without the right tool, the lower half goes without its loot, as a door's
     * does. With an item inside, it still drops itself with everything in it.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && !isLower(state) && (player.isCreative() || !player.hasCorrectToolForDrops(state, level, pos))) {
            BlockPos below = pos.below();
            BlockState lower = level.getBlockState(below);
            if (lower.is(this) && isLower(lower)) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, below, Block.getId(lower));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** A block holding an item drops itself, with the item inside, as it is removed; the loot table then adds nothing. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof RelayChatterboxBlockEntity relay && relay.dropsItself()) {
            return List.of();
        }
        return super.getDrops(state, params);
    }

    /** Either half opens the lower half's screen, as any machine does: for those trusted on the network, and anyone else is told whose it is. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockPos lower = lowerPos(state, pos);
        return super.useWithoutItem(level.getBlockState(lower), level, lower, player, hitResult);
    }

    /** The lower half tops up its Link Byteling; the upper half has nothing to do. */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() || !isLower(state) ? null
                : createTickerHelper(type, JasmBlocks.RELAY_CHATTERBOX_ENTITY.get(), RelayChatterboxBlockEntity::serverTick);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isLower(state) ? new RelayChatterboxBlockEntity(pos, state) : new RelayChatterboxTopBlockEntity(pos, state);
    }
}
