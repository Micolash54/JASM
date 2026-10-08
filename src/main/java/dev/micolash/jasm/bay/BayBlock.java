package dev.micolash.jasm.bay;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.network.PlacementAchievements;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** A bay: works on the block in front of it, which faces whoever placed it, like a dispenser. */
public class BayBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    private final BayKind kind;

    public BayBlock(BayKind kind, BlockBehaviour.Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public static Function<BlockBehaviour.Properties, BayBlock> of(BayKind kind) {
        return properties -> new BayBlock(kind, properties);
    }

    public BayKind kind() {
        return kind;
    }

    @Override
    protected MapCodec<BayBlock> codec() {
        return simpleCodec(properties -> new BayBlock(kind, properties));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Toward the player, but never up: placed looking down, it faces the player across the floor instead.
        Direction facing = context.getNearestLookingDirection().getOpposite();
        if (facing == Direction.UP) facing = context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    /**
     * A hair short of a full block on top. A full solid block has its inside faces lit from the blocks around it, so
     * they went dark beside a solid block; this way the open frame is lit from its own space.
     */
    private static final VoxelShape COLLISION = Block.box(0, 0, 0, 16, 15.99, 16);

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return kind == BayKind.DEPLOYMENT ? new DeploymentBayBlockEntity(pos, state) : new DemolitionBayBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            // Only the Demolition Bay does anything between frames: its laser sound and the cracks.
            return kind == BayKind.DEMOLITION ? (l, p, s, entity) -> {
                if (entity instanceof BayBlockEntity bay) bay.clientTick(l);
            } : null;
        }
        return (l, p, s, entity) -> {
            if (entity instanceof BayBlockEntity bay) BayBlockEntity.serverTick(l, p, s, bay);
        };
    }

    /** Opens the panel for those allowed to use the bay. Others get nothing. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof BayBlockEntity bay
                && MachineAccess.canUse(bay, serverPlayer)) {
            serverPlayer.openMenu(bay, buf -> BayMenu.writeOpening(buf, bay));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation,
            boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof BayBlockEntity bay) {
            bay.neighbourChanged(serverLevel);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        super.setPlacedBy(level, pos, state, by, itemStack);
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof BayBlockEntity bay) {
            if (bay.owner() == null && by instanceof Player player) bay.setOwner(player);
            Networks.placedMachine(serverLevel, pos);
            PlacementAchievements.placed(serverLevel, pos, by);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!oldState.is(this)) Networks.invalidate(serverLevel, pos);
        // Turned by a wrench: a new front to look at, and the I/O grid turned with it.
        else if (level.getBlockEntity(pos) instanceof BayBlockEntity bay) {
            bay.wake();
            if (oldState.getValue(FACING) != state.getValue(FACING)) level.invalidateCapabilities(pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        Networks.invalidate(level, pos);
    }
}
