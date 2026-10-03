package dev.micolash.jasm.autocraft;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The Access Port: joins every machine touching it to the network. It works through every side, whatever is placed
 * next to it and whenever. It belongs to whoever placed it, like the other crafting blocks.
 *
 * <p>Its shape follows what touches it: a small core, with a neck toward the network and a clamp pad on each machine.
 */
public class AccessPortBlock extends BaseEntityBlock {
    public static final Map<Direction, EnumProperty<PortSide>> SIDES = Arrays.stream(Direction.values())
            .collect(Collectors.toMap(side -> side, side -> EnumProperty.create(side.getSerializedName(), PortSide.class),
                    (a, b) -> a, () -> new EnumMap<>(Direction.class)));

    private static final VoxelShape CORE = Block.box(3, 3, 3, 13, 13, 13);
    private static final Map<Direction, VoxelShape> NECKS = new EnumMap<>(Direction.class);
    private static final Map<Direction, VoxelShape> PADS = new EnumMap<>(Direction.class);
    private final Map<BlockState, VoxelShape> shapes = new ConcurrentHashMap<>();

    static {
        for (Direction side : Direction.values()) {
            NECKS.put(side, facing(side, 5, 5, 0, 11, 11, 3));
            PADS.put(side, Shapes.or(facing(side, 5, 5, 1, 11, 11, 3), facing(side, 4, 4, 0, 12, 12, 1)));
        }
    }

    @Override
    protected MapCodec<AccessPortBlock> codec() {
        return simpleCodec(AccessPortBlock::new);
    }

    public AccessPortBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (EnumProperty<PortSide> property : SIDES.values()) {
            state = state.setValue(property, PortSide.NONE);
        }
        registerDefaultState(state);
    }

    /** A box drawn for the north side (low z), turned to face {@code side}. */
    private static VoxelShape facing(Direction side, double x1, double y1, double z1, double x2, double y2, double z2) {
        return switch (side) {
            case NORTH -> Block.box(x1, y1, z1, x2, y2, z2);
            case SOUTH -> Block.box(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            case WEST -> Block.box(z1, y1, 16 - x2, z2, y2, 16 - x1);
            case EAST -> Block.box(16 - z2, y1, x1, 16 - z1, y2, x2);
            case DOWN -> Block.box(x1, z1, 16 - y2, x2, z2, 16 - y1);
            case UP -> Block.box(x1, 16 - z2, y1, x2, 16 - z1, y2);
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        SIDES.values().forEach(builder::add);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.computeIfAbsent(state, s -> {
            VoxelShape shape = CORE;
            for (Direction side : Direction.values()) {
                PortSide shown = s.getValue(SIDES.get(side));
                if (shown == PortSide.LINK) {
                    shape = Shapes.or(shape, NECKS.get(side));
                } else if (shown != PortSide.NONE) {
                    shape = Shapes.or(shape, PADS.get(side));
                }
            }
            return shape;
        });
    }

    /** Something next to the port changed: it may be a new machine, or one taken away. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation,
            boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof AccessPortBlockEntity port) {
            port.refreshSides();
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AccessPortBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.ACCESS_PORT_ENTITY.get(), AccessPortBlockEntity::serverTick);
    }

    /** Opens the port's screen for those allowed to use it; everyone else is told whose it is. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof AccessPortBlockEntity port) {
            if (MachineAccess.canUse(port, serverPlayer)) {
                serverPlayer.openMenu(port, port::writeOpening);
            } else {
                serverPlayer.sendOverlayMessage(Component.translatable("message.jasm.machine.no_access", port.ownerName()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        super.setPlacedBy(level, pos, state, by, itemStack);
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            if (machine.owner() == null && by instanceof Player player) {
                machine.setOwner(player);
            }
            Networks.placedMachine(serverLevel, pos);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            Networks.invalidate(serverLevel, pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        Networks.invalidate(level, pos);
    }
}
