package dev.micolash.jasm.bay;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Wakes a sleeping Demolition Bay when an item appears in its front space: a Deployment Bay's throw, a dropper, a block
 * broken there. Items that fall in are heard by the bay itself as they land, and a sleeping bay also looks once per
 * action for items that float or slide in. Only the six blocks around a new item are looked at, and only when an item
 * appears.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class BayItemWake {
    private BayItemWake() {}

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof ItemEntity item) || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos at = item.blockPosition();
        for (Direction side : Direction.values()) {
            BlockPos next = at.relative(side);
            if (!level.isLoaded(next)) continue;
            BlockState state = level.getBlockState(next);
            if (state.is(JasmBlocks.DEMOLITION_BAY.get()) && state.getValue(BayBlock.FACING) == side.getOpposite()
                    && level.getBlockEntity(next) instanceof DemolitionBayBlockEntity bay) {
                bay.wake();
            }
        }
    }
}
