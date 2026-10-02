package dev.micolash.jasm.brain;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmTags;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** A Network Chamber: built into a 2×2×2 or 3×3×3 cube around a Network Brain, it lets the brain grow further. */
public class NetworkChamberBlock extends MachineBlock {
    /** Part of a brain's cube. */
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    @Override
    protected MapCodec<NetworkChamberBlock> codec() {
        return simpleCodec(NetworkChamberBlock::new);
    }

    public NetworkChamberBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FORMED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FORMED);
    }

    private static @Nullable NetworkBrainBlockEntity brainAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof NetworkChamberBlockEntity chamber ? chamber.brain() : null;
    }

    /** A formed chamber opens its brain's screen; a loose one does nothing. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!state.getValue(FORMED)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        NetworkBrainBlockEntity brain = brainAt(level, pos);
        if (brain == null) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (MachineAccess.canUse(brain, serverPlayer)) {
                serverPlayer.openMenu(brain);
            } else {
                serverPlayer.sendOverlayMessage(Component.translatable("message.jasm.machine.no_access", brain.ownerName()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** A typed chip on a formed chamber feeds its brain. When no chip goes in, the click opens the screen instead. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!stack.is(JasmTags.TYPED_CHIPS) || !state.getValue(FORMED)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide()) {
            NetworkBrainBlockEntity brain = brainAt(level, pos);
            if (brain == null || NetworkBrainBlock.feed(brain, player, stack, player.isShiftKeyDown()) == 0) {
                return InteractionResult.TRY_WITH_EMPTY_HAND;
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            BrainShapes.recheckAround(serverLevel, pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        BrainShapes.recheckAround(level, pos);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkChamberBlockEntity(pos, state);
    }
}
