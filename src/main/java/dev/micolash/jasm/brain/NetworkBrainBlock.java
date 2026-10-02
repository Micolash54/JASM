package dev.micolash.jasm.brain;

import com.mojang.serialization.MapCodec;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.BrainSize;
import dev.micolash.jasm.network.MachineAccess;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.util.TriState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jspecify.annotations.Nullable;

/** The Network Brain: lets its network hold more machines, and slowly learns while it has power. */
public class NetworkBrainBlock extends MachineBlock {
    public static final EnumProperty<BrainSize> SIZE = EnumProperty.create("size", BrainSize.class);
    /** Powered and leading its network. */
    public static final BooleanProperty AWAKE = BooleanProperty.create("awake");

    @Override
    protected MapCodec<NetworkBrainBlock> codec() {
        return simpleCodec(NetworkBrainBlock::new);
    }

    public NetworkBrainBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(SIZE, BrainSize.SINGLE).setValue(AWAKE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SIZE, AWAKE);
    }

    /** Puts chips from the player's hand into the brain's slot: one, or as many as fit. Returns how many went in. */
    public static int feed(NetworkBrainBlockEntity brain, Player player, ItemStack held, boolean all) {
        if (!brain.accepts(held) || !MachineAccess.canUse(brain, player)) {
            return 0;
        }
        ItemStack slot = brain.getItem(NetworkBrainBlockEntity.SLOT);
        if (!slot.isEmpty() && !ItemStack.isSameItemSameComponents(slot, held)) {
            return 0;
        }
        int room = held.getMaxStackSize() - slot.getCount();
        int moved = Math.min(room, all ? held.getCount() : 1);
        if (moved <= 0) {
            return 0;
        }
        brain.setItem(NetworkBrainBlockEntity.SLOT, held.copyWithCount(slot.getCount() + moved));
        if (!player.hasInfiniteMaterials()) {
            held.shrink(moved);
        }
        return moved;
    }

    /**
     * A typed chip in hand feeds the brain; anything else opens its screen. When no chip goes in (a capped brain, or
     * someone else's), the click carries on as an empty-hand click, so the screen or the no-access message still shows.
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!stack.is(JasmTags.TYPED_CHIPS)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof NetworkBrainBlockEntity brain
                && feed(brain, player, stack, player.isShiftKeyDown()) == 0) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The game skips a block's use while its player sneaks with something in hand; a sneak-click with chips still feeds,
     * on the brain or on a chamber of its cube.
     */
    @EventBusSubscriber(modid = Jasm.MODID)
    static final class SneakFeeding {
        private SneakFeeding() {}

        // Low, so claims and protection mods get their say first.
        @SubscribeEvent(priority = EventPriority.LOW)
        static void rightClick(PlayerInteractEvent.RightClickBlock event) {
            // Leave the click alone when another mod has already decided about it.
            if (event.isCanceled() || event.getUseBlock() != TriState.DEFAULT || !event.getItemStack().is(JasmTags.TYPED_CHIPS)) {
                return;
            }
            BlockState state = event.getLevel().getBlockState(event.getPos());
            if (state.getBlock() instanceof NetworkBrainBlock
                    || state.getBlock() instanceof NetworkChamberBlock && state.getValue(NetworkChamberBlock.FORMED)) {
                event.setUseBlock(TriState.TRUE);
            }
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkBrainBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, JasmBlocks.NETWORK_BRAIN_ENTITY.get(), NetworkBrainBlockEntity::serverTick);
    }
}
