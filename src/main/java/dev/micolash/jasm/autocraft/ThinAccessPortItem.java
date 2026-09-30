package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.network.DataCableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Places a port in the cable's block space. */
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
        if (!(context.getLevel().getBlockEntity(pos) instanceof DataCableBlockEntity cable)) return InteractionResult.PASS;
        if (context.getLevel().isClientSide()) return cable.port(side) == null ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        if (cable.attach(side, context.getItemInHand(), context.getPlayer())) {
            if (!context.getPlayer().getAbilities().instabuild) context.getItemInHand().shrink(1);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.FAIL;
    }
}
