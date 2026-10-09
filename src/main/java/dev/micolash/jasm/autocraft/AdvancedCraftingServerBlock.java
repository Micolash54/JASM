package dev.micolash.jasm.autocraft;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.registry.JasmBlocks;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The Advanced Crafting Server: two blocks tall. The bottom half is the server; the top half holds the screen, lit while
 * a job runs. Cables join either half. Breaking either half breaks both, the same way a door does.
 */
public class AdvancedCraftingServerBlock extends CraftingServerBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    /** Only used on the top half: whether its screen is on. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    @Override
    protected MapCodec<AdvancedCraftingServerBlock> codec() {
        return simpleCodec(AdvancedCraftingServerBlock::new);
    }

    public AdvancedCraftingServerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER).setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HALF, LIT);
    }

    public static boolean isLower(BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER;
    }

    /** Where the server itself is, from either half. */
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

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        super.setPlacedBy(level, pos, state, by, itemStack);
    }

    /** A half without its other half turns to air; the bottom one drops the server and everything in it as it goes. */
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
     * Broken from the top in creative or without the right tool, the bottom half goes without dropping the server, as a
     * door's does. Its parts and its job's items still spill.
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

    /** The top half opens the server below it. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockPos server = lowerPos(state, pos);
        return super.useWithoutItem(level.getBlockState(server), level, server, player, hitResult);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isLower(state) ? new AdvancedCraftingServerBlockEntity(pos, state) : new AdvancedCraftingServerTopBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null
                : createTickerHelper(type, JasmBlocks.ADVANCED_CRAFTING_SERVER_ENTITY.get(), CraftingServerBlockEntity::serverTick);
    }

    /** Turns the top half's screen on or off. */
    static void setScreen(ServerLevel level, BlockPos lower, boolean on) {
        BlockPos pos = lower.above();
        BlockState upper = level.getBlockState(pos);
        if (upper.getBlock() instanceof AdvancedCraftingServerBlock && !isLower(upper) && upper.getValue(LIT) != on) {
            level.setBlock(pos, upper.setValue(LIT, on), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }
}
