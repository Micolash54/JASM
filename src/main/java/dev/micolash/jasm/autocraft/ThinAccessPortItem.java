package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.PlacementAchievements;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Places a port in the cable's block space, or on its own in an empty space against the clicked block. */
public class ThinAccessPortItem extends Item {
    public ThinAccessPortItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();
        Direction side = context.getClickedFace();
        if (!(context.getLevel().getBlockEntity(pos) instanceof DataCableBlockEntity)) {
            pos = pos.relative(side);
            side = side.getOpposite();
        }
        if (!(context.getLevel().getBlockEntity(pos) instanceof DataCableBlockEntity cable)) return placeAlone(context);
        if (context.getLevel().isClientSide()) return cable.port(side) == null ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        if (cable.attach(side, context.getItemInHand(), context.getPlayer())) {
            if (context.getLevel() instanceof ServerLevel serverLevel) PlacementAchievements.placed(serverLevel, pos, context.getPlayer());
            if (!context.getPlayer().getAbilities().instabuild) context.getItemInHand().shrink(1);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.FAIL;
    }

    /** No cable here: the port goes in the empty space in front of the clicked face, facing back at that block. */
    private static InteractionResult placeAlone(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        Direction side = context.getClickedFace().getOpposite();
        if (player == null || !level.getBlockState(pos).canBeReplaced()
                || !player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand())) {
            return InteractionResult.FAIL;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        BlockState host = JasmBlocks.DATA_CABLE.get().defaultBlockState().setValue(DataCableBlock.CORE, false);
        if (!level.setBlock(pos, host, Block.UPDATE_ALL) || !(level.getBlockEntity(pos) instanceof DataCableBlockEntity cable)) {
            return InteractionResult.FAIL;
        }
        if (!cable.attach(side, context.getItemInHand(), player)) {
            level.removeBlock(pos, false);
            return InteractionResult.FAIL;
        }
        if (level instanceof ServerLevel serverLevel) {
            DataCableBlock.refreshConnectionsAround(serverLevel, pos);
            PlacementAchievements.placed(serverLevel, pos, player);
        }
        if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        var sound = JasmBlocks.ACCESS_PORT.get().defaultBlockState().getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return InteractionResult.SUCCESS;
    }
}
