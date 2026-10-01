package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** A cable placed into a space that only holds thin ports fills that space instead of failing. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class PortSpaceFilling {
    private PortSpaceFilling() {}

    @SubscribeEvent
    static void place(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHitVec() == null || !DataCableBlock.isCable(event.getItemStack())
                || DataCableBlock.coreless(event.getLevel().getBlockState(event.getPos()))) {
            return;
        }
        BlockPos target = event.getPos().relative(event.getHitVec().getDirection());
        if (!DataCableBlock.coreless(event.getLevel().getBlockState(target))) {
            return;
        }
        boolean filled = DataCableBlock.fill(event.getLevel(), target, event.getItemStack(), event.getEntity());
        event.setCanceled(true);
        event.setCancellationResult(filled ? InteractionResult.SUCCESS : InteractionResult.FAIL);
    }
}
