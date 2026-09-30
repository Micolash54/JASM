package dev.micolash.jasm.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** A power source follows the same connection and ownership rules as other network blocks. */
public abstract class PowerSourceBlock extends BaseEntityBlock {
    protected PowerSourceBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof NetworkPowerSource source
                && source instanceof MenuProvider menu) {
            if (source.networkOwnership().canUse(player)) {
                serverPlayer.openMenu(menu);
            } else {
                serverPlayer.sendOverlayMessage(Component.translatable("message.jasm.machine.no_access", source.networkOwnership().name()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof NetworkPowerSource source) {
            if (source.networkOwnership().owner() == null && by instanceof Player player) {
                source.networkOwnership().adopt(player.getUUID(), player.getPlainTextName());
            }
            Networks.placedMachine(server, pos);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level instanceof ServerLevel server && !old.is(this)) {
            Networks.invalidate(server);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        Networks.invalidate(level);
    }
}
