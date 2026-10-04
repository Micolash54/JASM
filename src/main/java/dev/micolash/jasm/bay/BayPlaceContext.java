package dev.micolash.jasm.bay;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Placing as someone standing in the bay and looking out of its front, so blocks turn the way they would for a player.
 * It never reaches for the clicked-on block, which can loop forever on some replaceable blocks.
 */
final class BayPlaceContext extends BlockPlaceContext {
    private final Direction look;

    BayPlaceContext(Level level, Player player, BlockPos pos, Direction look, ItemStack stack) {
        super(level, player, InteractionHand.MAIN_HAND, stack, new BlockHitResult(Vec3.atBottomCenterOf(pos), look.getOpposite(), pos, false));
        this.look = look;
    }

    @Override
    public BlockPos getClickedPos() {
        return getHitResult().getBlockPos();
    }

    @Override
    public boolean canPlace() {
        return getLevel().getBlockState(getClickedPos()).canBeReplaced(this);
    }

    // Down first for every look, so torches, buttons and the like go on the floor when they can.
    @Override
    public Direction getNearestLookingDirection() {
        return Direction.DOWN;
    }

    @Override
    public Direction[] getNearestLookingDirections() {
        return switch (look) {
            case DOWN -> new Direction[]{Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
            case UP -> new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
            case NORTH -> new Direction[]{Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.SOUTH};
            case SOUTH -> new Direction[]{Direction.DOWN, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.NORTH};
            case WEST -> new Direction[]{Direction.DOWN, Direction.WEST, Direction.SOUTH, Direction.UP, Direction.NORTH, Direction.EAST};
            case EAST -> new Direction[]{Direction.DOWN, Direction.EAST, Direction.SOUTH, Direction.UP, Direction.NORTH, Direction.WEST};
        };
    }

    @Override
    public Direction getHorizontalDirection() {
        return look.getAxis() == Direction.Axis.Y ? Direction.NORTH : look;
    }

    @Override
    public boolean isSecondaryUseActive() {
        return false;
    }

    @Override
    public float getRotation() {
        return look.get2DDataValue() * 90;
    }
}
