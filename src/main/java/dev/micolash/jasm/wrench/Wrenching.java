package dev.micolash.jasm.wrench;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jspecify.annotations.Nullable;

/**
 * Any wrench, ours or another mod's, turns JASM blocks with a front and picks JASM blocks up when sneaking. Picking up is
 * mining without the wait, so the block keeps what mining keeps. Other mods' blocks are left to their own wrench handling.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Wrenching {
    private Wrenching() {}

    @SubscribeEvent
    static void use(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getItemStack().is(Tags.Items.TOOLS_WRENCH)) {
            return;
        }
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        boolean pickUp = player.isSecondaryUseActive();
        if (pickUp ? !state.is(JasmTags.WRENCH_PICKUP)
                : !state.is(JasmTags.WRENCH_TURNABLE) || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return;
        }
        if (!player.mayBuild() || !level.mayInteract(player, pos)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player instanceof ServerPlayer serverPlayer) {
            if (pickUp) {
                // The normal break: claim mods can stop it, ports come off one at a time, slots spill.
                if (serverPlayer.gameMode.destroyBlock(pos)) {
                    level.playSound(null, pos, SoundEvents.SLIME_JUMP_SMALL, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
            } else {
                level.setBlock(pos, turned(state, event.getFace()), Block.UPDATE_ALL);
                level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
        }
    }

    /** The front turns to the clicked side. Clicking the front, the top or the bottom turns it on a quarter, clockwise. */
    private static BlockState turned(BlockState state, @Nullable Direction clicked) {
        Direction front = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Direction to = clicked != null && clicked.getAxis().isHorizontal() && clicked != front ? clicked : front.getClockWise();
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, to);
    }

    /** A wrench gets the block back even where mining needs a pickaxe. */
    @SubscribeEvent
    static void harvest(PlayerEvent.HarvestCheck event) {
        if (!event.canHarvest() && event.getEntity().getMainHandItem().is(Tags.Items.TOOLS_WRENCH)
                && event.getTargetBlock().is(JasmTags.WRENCH_PICKUP)) {
            event.setCanHarvest(true);
        }
    }
}
