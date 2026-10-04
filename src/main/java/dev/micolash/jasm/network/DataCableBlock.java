package dev.micolash.jasm.network;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.bay.BayBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** A Data Cable in one of three tiers. Any two cables join, and any cable joins a JASM network block. */
public class DataCableBlock extends PipeBlock implements EntityBlock {
    public static final BooleanProperty HAS_PORTS = BooleanProperty.create("has_ports");
    /** False for a space that only holds thin ports, placed without a cable. It stays off the network until a cable fills it. */
    public static final BooleanProperty CORE = BooleanProperty.create("core");
    private final CableTier tier;

    @Override
    protected MapCodec<DataCableBlock> codec() {
        return simpleCodec(properties -> new DataCableBlock(properties, tier));
    }

    public DataCableBlock(BlockBehaviour.Properties properties, CableTier tier) {
        super(6.0F, properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false).setValue(HAS_PORTS, false).setValue(CORE, true));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DataCableBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != JasmBlocks.DATA_CABLE_ENTITY.get()) return null;
        return (world, pos, shown, entity) -> DataCableBlockEntity.serverTick(world, pos, shown, (DataCableBlockEntity) entity);
    }

    public static VoxelShape portShape(Direction side) {
        return switch (side) {
            case NORTH -> Shapes.or(Block.box(4, 4, 0, 12, 12, 2), Block.box(5, 5, 2, 11, 11, 5));
            case SOUTH -> Shapes.or(Block.box(4, 4, 14, 12, 12, 16), Block.box(5, 5, 11, 11, 11, 14));
            case WEST -> Shapes.or(Block.box(0, 4, 4, 2, 12, 12), Block.box(2, 5, 5, 5, 11, 11));
            case EAST -> Shapes.or(Block.box(14, 4, 4, 16, 12, 12), Block.box(11, 5, 5, 14, 11, 11));
            case DOWN -> Shapes.or(Block.box(4, 0, 4, 12, 2, 12), Block.box(5, 2, 5, 11, 5, 11));
            case UP -> Shapes.or(Block.box(4, 14, 4, 12, 16, 12), Block.box(5, 11, 5, 11, 14, 11));
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        var shape = state.getValue(CORE) ? super.getShape(state, level, pos, context) : Shapes.empty();
        if (level.getBlockEntity(pos) instanceof DataCableBlockEntity cable) {
            for (Direction side : Direction.values()) if (cable.port(side) != null) shape = Shapes.or(shape, portShape(side));
        }
        return shape;
    }

    /** Whether this is a space holding only thin ports, without a cable of its own. */
    public static boolean coreless(BlockState state) {
        return state.getBlock() instanceof DataCableBlock && !state.getValue(CORE);
    }

    /** A cable used on a cable-less port space fills it in: the ports stay, and it carries data like any cable. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (state.getValue(CORE) || !isCable(stack)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        return fill(level, pos, stack, player) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    public static boolean isCable(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof DataCableBlock;
    }

    /** Puts the held cable into the port space at {@code pos}. Returns false if the player may not. */
    public static boolean fill(Level level, BlockPos pos, ItemStack stack, Player player) {
        BlockState state = level.getBlockState(pos);
        if (!coreless(state) || !(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof DataCableBlock cable)) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof DataCableBlockEntity host)) {
            return true;
        }
        host.syncOwners();
        if (!host.ports().isEmpty() && !MachineAccess.canUse(host.ports().getFirst(), player)) {
            return false;
        }
        BlockState filled = cable.defaultBlockState().setValue(HAS_PORTS, state.getValue(HAS_PORTS)).setValue(CORE, true);
        if (state.is(cable)) {
            level.setBlock(pos, state.setValue(CORE, true), Block.UPDATE_ALL);
        } else {
            host.replaceBlock(filled);
        }
        Networks.invalidate(serverLevel, pos);
        refreshConnectionsAround(serverLevel, pos);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        var sound = filled.getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return true;
    }

    /** Recomputes the arms of this cable and the cables next to it. */
    public static void refreshConnectionsAround(ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof DataCableBlock cable) cable.refreshConnections(level, pos);
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (level.isLoaded(next) && level.getBlockState(next).getBlock() instanceof DataCableBlock cable) cable.refreshConnections(level, next);
        }
    }

    public static @Nullable Direction hitPort(DataCableBlockEntity cable, Vec3 hit) {
        var local = hit.subtract(Vec3.atLowerCornerOf(cable.getBlockPos()));
        for (Direction side : Direction.values()) {
            if (cable.port(side) != null && portShape(side).toAabbs().stream().anyMatch(box -> box.inflate(0.00001).contains(local))) return side;
        }
        return null;
    }

    public static @Nullable Direction selectedPort(BlockGetter level, BlockPos pos, Player player) {
        var hit = player.pick(player.blockInteractionRange(), 1, false);
        return hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)
                && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable ? hitPort(cable, hit.getLocation()) : null;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return selectedPort(level, pos, player) != null
                ? JasmBlocks.ACCESS_PORT.get().defaultBlockState().getDestroyProgress(player, level, pos)
                : super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // The part renderer draws the whole host so cracks stay on the selected part.
        return state.getValue(HAS_PORTS) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof DataCableBlockEntity cable) {
            Direction side = hitPort(cable, hit.getLocation());
            if (side != null) {
                if (player instanceof ServerPlayer serverPlayer) {
                    cable.syncOwners();
                    var port = cable.port(side);
                    if (MachineAccess.canUse(port, player)) serverPlayer.openMenu(port, port::writeOpening);
                    else serverPlayer.sendOverlayMessage(Component.translatable("message.jasm.machine.no_access", port.ownerName()));
                }
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        return selectedPort(level, pos, player) == null ? super.playerWillDestroy(level, pos, state, player) : state;
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
            ItemStack tool, boolean harvest, FluidState fluid) {
        Direction side = selectedPort(level, pos, player);
        if (side != null && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable) {
            if (level.isClientSide()) return false;
            cable.syncOwners();
            if (MachineAccess.canUse(cable.port(side), player)) {
                if (level instanceof ServerLevel serverLevel) {
                    var bounds = portShape(side).bounds();
                    var center = bounds.getCenter().add(Vec3.atLowerCornerOf(pos));
                    serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, JasmBlocks.ACCESS_PORT.get().defaultBlockState()),
                            center.x, center.y, center.z, 16, bounds.getXsize() / 4, bounds.getYsize() / 4, bounds.getZsize() / 4, 0.05);
                }
                cable.detach(side, !player.preventsBlockDrops());
            }
            return false;
        }
        return level.isClientSide() ? level.setBlock(pos, fluid.createLegacyBlock(), 11) : level.removeBlock(pos, false);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData,
            Player player) {
        if (level instanceof Level world && world.getBlockEntity(pos) instanceof DataCableBlockEntity cable) {
            Direction side = selectedPort(world, pos, player);
            if (side != null) return cable.portItem(cable.port(side));
        }
        return new ItemStack(this);
    }

    public CableTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, HAS_PORTS, CORE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction side : Direction.values()) {
            BlockPos next = context.getClickedPos().relative(side);
            if (!context.getLevel().isLoaded(next)) continue;
            state = state.setValue(PROPERTY_BY_DIRECTION.get(side), connects(context.getLevel(), next, context.getLevel().getBlockState(next), side));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(direction), connects(level, neighbourPos, neighbourState, direction));
    }

    /** Whether that spot can be read without pulling a chunk in. Block getters that aren't levels hold what they hold. */
    private static boolean loaded(BlockGetter level, BlockPos pos) {
        return !(level instanceof LevelReader reader) || reader.hasChunkAt(pos);
    }

    private boolean connects(BlockGetter level, BlockPos neighbourPos, BlockState neighbour, Direction direction) {
        BlockPos own = neighbourPos.relative(direction.getOpposite());
        if (!loaded(level, own) || !loaded(level, neighbourPos)) return false;
        if (level.getBlockEntity(own) instanceof DataCableBlockEntity host && host.port(direction) != null) return false;
        if (coreless(level.getBlockState(own)) || coreless(neighbour)) return false;
        if (level instanceof ServerLevel serverLevel && level.getBlockState(own).getBlock() instanceof DataCableBlock
                && (neighbour.getBlock() instanceof DataCableBlock || level.getBlockEntity(neighbourPos) instanceof MachineBlockEntity
                        || level.getBlockEntity(neighbourPos) instanceof ArchiveBlockEntity)) {
            return Networks.canConnect(serverLevel, own, neighbourPos);
        }
        if (neighbour.getBlock() instanceof DataCableBlock) {
            return true;
        }
        if (neighbour.getBlock() instanceof MachineBlock || neighbour.getBlock() instanceof BayBlock) {
            return true;
        }
        return level.getBlockEntity(neighbourPos) instanceof ArchiveBlockEntity
                || level.getBlockEntity(neighbourPos) instanceof NetworkPowerSource;
    }

    public void refreshConnections(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(this)) {
            return;
        }
        BlockState updated = state.setValue(HAS_PORTS, level.getBlockEntity(pos) instanceof DataCableBlockEntity host && !host.ports().isEmpty());
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            // An arm towards a chunk that isn't loaded keeps its shape until that chunk comes back.
            if (!level.isLoaded(next)) continue;
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
            Networks.invalidate(serverLevel, pos);
            refreshConnections(serverLevel, pos);
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (serverLevel.isLoaded(next) && serverLevel.getBlockState(next).getBlock() instanceof DataCableBlock cable) {
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
        Networks.invalidate(level, pos);
        level.invalidateCapabilities(pos);
    }
}
