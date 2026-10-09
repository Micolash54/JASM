package dev.micolash.jasm.battery;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Stores power. Touching Batteries are one battery: their model joins up, and the gold core shows the charge of the whole
 * group, filling from its lowest block to its highest.
 */
public class BatteryBlock extends BaseEntityBlock {
    /** Whether another Battery touches that side. */
    public static final Map<Direction, BooleanProperty> CONNECTED = PipeBlock.PROPERTY_BY_DIRECTION;
    /** How many pixels of this block's core are lit, from the bottom. */
    public static final IntegerProperty CHARGE = IntegerProperty.create("charge", 0, 16);
    /** Whether the whole battery is full: the lines on top light up. */
    public static final BooleanProperty FULL = BooleanProperty.create("full");

    /** A block glows as bright as glowstone once its lit core shows above the base. */
    public static int light(BlockState state) {
        return state.getValue(CHARGE) > 2 ? 15 : 0;
    }

    @Override
    protected MapCodec<BatteryBlock> codec() {
        return simpleCodec(BatteryBlock::new);
    }

    public BatteryBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(CHARGE, 0).setValue(FULL, false);
        for (BooleanProperty side : CONNECTED.values()) {
            state = state.setValue(side, false);
        }
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTED.values().toArray(BooleanProperty[]::new));
        builder.add(CHARGE, FULL);
    }

    /** The most blocks one battery may have in this world. */
    public static int maxBlocks() {
        return JasmConfig.orDefault(JasmConfig.BATTERY_MAX_BLOCKS);
    }

    /** No state, so no placing, when the block would make a battery of more than {@link #maxBlocks()}, joining several if it must. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (sizeWith(level, pos, maxBlocks()) > maxBlocks()) {
            return null;
        }
        return joined(defaultBlockState(), level, pos);
    }

    /** Blocks in the battery a new block at {@code pos} would make, counted only until it passes {@code limit}. Never pulls in a chunk. */
    static int sizeWith(Level level, BlockPos pos, int limit) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(pos);
        queue.add(pos);
        int size = 0;
        while (!queue.isEmpty() && size <= limit) {
            BlockPos at = queue.poll();
            size++;
            for (Direction side : Direction.values()) {
                BlockPos next = at.relative(side);
                if (seen.add(next) && level.isLoaded(next) && level.getBlockState(next).getBlock() instanceof BatteryBlock) {
                    queue.add(next);
                }
            }
        }
        return size;
    }

    /** A battery beside a new block joins up with it at once. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            BatteryGroup.forgetAround(serverLevel, pos);
        }
    }

    /** The state with each side joined where another Battery touches it. Never pulls in a chunk. */
    static BlockState joined(BlockState state, Level level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            state = state.setValue(CONNECTED.get(side), level.isLoaded(next) && level.getBlockState(next).getBlock() instanceof BatteryBlock);
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        return state.setValue(CONNECTED.get(direction), neighbourState.is(this));
    }

    /** Anyone can open it: it only shows the readings of the whole battery. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof BatteryBlockEntity battery) {
            serverPlayer.openMenu(battery);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation,
            boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BatteryBlockEntity battery) {
            battery.neighboursChanged();
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BatteryBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.BATTERY_ENTITY.get(), BatteryBlockEntity::serverTick);
    }
}
