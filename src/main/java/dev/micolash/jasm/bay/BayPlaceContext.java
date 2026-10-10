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
 * The Deployment Bay using an item on the space in front: aimed at the middle of that space's face toward the bay, as
 * if its owner stood inside the bay looking straight out. Block items build their own context from this aim and the
 * bay's stand-in player; spawn eggs, bone meal and armor stands read this one directly.
 */
final class BayPlaceContext extends BlockPlaceContext {
    BayPlaceContext(Level level, Player player, BlockPos pos, Direction look, ItemStack stack) {
        super(level, player, InteractionHand.MAIN_HAND, stack, aimAt(pos, look));
    }

    private static BlockHitResult aimAt(BlockPos front, Direction look) {
        Direction face = look.getOpposite();
        return new BlockHitResult(Vec3.atCenterOf(front).relative(face, 0.5), face, front, false);
    }

    /** Read back from the aim, so it works while the parent constructor is still running. */
    private Direction look() {
        return getClickedFace().getOpposite();
    }

    // Always the front space, never the bay behind it.
    @Override
    public BlockPos getClickedPos() {
        return getHitResult().getBlockPos();
    }

    @Override
    public boolean canPlace() {
        return getLevel().getBlockState(getClickedPos()).canBeReplaced(this);
    }

    @Override
    public Direction getNearestLookingDirection() {
        return look();
    }

    /** Straight ahead first and back toward the bay last. A sideways bay tries the floor before its side walls. */
    @Override
    public Direction[] getNearestLookingDirections() {
        Direction look = look();
        if (look.getAxis() == Direction.Axis.Y) {
            return new Direction[]{look, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, look.getOpposite()};
        }
        return new Direction[]{look, Direction.DOWN, look.getClockWise(), look.getCounterClockWise(), Direction.UP, look.getOpposite()};
    }

    /** Up and down bays turn things as if looking south. */
    @Override
    public Direction getHorizontalDirection() {
        Direction look = look();
        return look.getAxis().isHorizontal() ? look : Direction.SOUTH;
    }

    @Override
    public boolean isSecondaryUseActive() {
        return false;
    }

    @Override
    public float getRotation() {
        return getHorizontalDirection().toYRot();
    }
}
