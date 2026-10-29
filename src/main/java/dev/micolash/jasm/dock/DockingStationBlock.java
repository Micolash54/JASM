package dev.micolash.jasm.dock;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** The Docking Station: a network block that holds one Deck so the network can use it while its player is away. */
public class DockingStationBlock extends MachineBlock {
    /** The pedestal up to the tray floor, and the rim round the tray. The same whichever way it faces. */
    private static final VoxelShape SHAPE = Shapes.or(Block.box(0, 0, 0, 16, 15, 16),
            Block.box(0, 15, 0, 16, 16, 2), Block.box(0, 15, 14, 16, 16, 16),
            Block.box(0, 15, 2, 2, 16, 14), Block.box(14, 15, 2, 16, 16, 14));

    @Override
    protected MapCodec<DockingStationBlock> codec() {
        return simpleCodec(DockingStationBlock::new);
    }

    public DockingStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** The Deck's own player sees the panel too, so they can always get their Deck back. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DockingStationBlockEntity dock && dock.canOpen(serverPlayer)) {
            serverPlayer.openMenu(dock);
            return InteractionResult.SUCCESS;
        }
        return super.useWithoutItem(state, level, pos, player, hitResult);
    }

    /** Second guard behind the break event: a player who may not take the Deck out never breaks the dock. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        return mayBreak(level, pos, player) ? super.playerWillDestroy(level, pos, state, player) : state;
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, ItemStack tool, boolean willHarvest,
            FluidState fluid) {
        if (!level.isClientSide() && !mayBreak(level, pos, player)) return false;
        return super.onDestroyedByPlayer(state, level, pos, player, tool, willHarvest, fluid);
    }

    /** A dock holding a Deck drops itself, with the Deck inside, as it is removed; the loot table then adds nothing. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof DockingStationBlockEntity dock && dock.dropsItself()) return List.of();
        return super.getDrops(state, params);
    }

    private static boolean mayBreak(Level level, BlockPos pos, Player player) {
        return !(level.getBlockEntity(pos) instanceof DockingStationBlockEntity dock) || level.isClientSide() || dock.mayBreak(player);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DockingStationBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.DOCKING_STATION_ENTITY.get(), DockingStationBlockEntity::serverTick);
    }
}
