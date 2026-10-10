package dev.micolash.jasm.bay;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.FluidAmounts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import org.jspecify.annotations.Nullable;

/** Places what its grid holds in front of it, as its owner would. */
public class DeploymentBayBlockEntity extends BayBlockEntity {
    /** Half the size of a dropped item, the gap it keeps from the bay's face, and its push per tick. */
    private static final double THROW_HALF = 0.125;
    private static final double THROW_GAP = 0.03;
    private static final double THROW_SPEED = 0.10;

    private DeployMode mode = DeployMode.PLACE;

    public DeploymentBayBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.DEPLOYMENT_BAY_ENTITY.get(), pos, state);
    }

    @Override
    public BayKind kind() {
        return BayKind.DEPLOYMENT;
    }

    @Override
    protected Plan plan(ServerLevel level, BlockPos front) {
        boolean anyItem = false;
        boolean refused = false;
        for (int slot = 0; slot < GRID; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;
            if (passes(stack)) anyItem = true;
            else refused = true;
        }
        boolean fluid = mode == DeployMode.PLACE && tank.getAmountAsLong(0) >= FluidAmounts.PER_BUCKET;
        boolean anyFluid = fluid && passes(tank.getResource(0).getFluid());
        refused |= fluid && !anyFluid;
        if (!anyItem && !anyFluid) return Plan.idle(refused ? BayStatus.FILTERED : BayStatus.SLEEPING);
        if (mode == DeployMode.DROP) {
            return crowded(level) ? Plan.idle(BayStatus.TOO_MANY_ITEMS) : Plan.work(JasmConfig.DEPLOYMENT_COST.getAsInt());
        }
        BlockState there = level.getBlockState(front);
        if (!there.canBeReplaced() && !(anyFluid && holdsFluid(level, front, there))) return Plan.idle(BayStatus.BLOCKED);
        // A player or mob in the way: a block can't go there, but a fluid still can.
        if (anyItem && !anyFluid && standsInFront(level, front)) return Plan.idle(BayStatus.OBSTRUCTED);
        if (!level.mayInteract(fakePlayer(level), front)) return Plan.idle(BayStatus.NOT_ALLOWED);
        return Plan.work(JasmConfig.DEPLOYMENT_COST.getAsInt());
    }

    /** A block the tank's fluid can go into without replacing it, such as a slab that gets waterlogged. */
    private boolean holdsFluid(ServerLevel level, BlockPos front, BlockState there) {
        return there.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(null, level, front, there, tank.getResource(0).getFluid());
    }

    @Override
    protected BayStatus act(ServerLevel level, BlockPos front) {
        Plan plan = plan(level, front);
        if (plan.status() != BayStatus.WORKING) return plan.status();
        if (!canPay(plan.cost())) return BayStatus.NO_POWER;
        if (mode == DeployMode.DROP) {
            for (int slot = 0; slot < GRID; slot++) {
                ItemStack stack = items.get(slot);
                if (stack.isEmpty() || !passes(stack)) continue;
                throwOut(level, stack.split(stack.getMaxStackSize()));
                gridChanged();
                pay(plan.cost());
                return BayStatus.WORKING;
            }
            return BayStatus.SLEEPING;
        }
        FakePlayer player = fakePlayer(level);
        for (int slot = 0; slot < GRID; slot++) {
            if (!items.get(slot).isEmpty() && passes(items.get(slot)) && placeFrom(level, player, front, slot)) {
                pay(plan.cost());
                return BayStatus.WORKING;
            }
        }
        if (placeFluid(level, player, front)) {
            pay(plan.cost());
            return BayStatus.WORKING;
        }
        return standsInFront(level, front) ? BayStatus.OBSTRUCTED : BayStatus.NOTHING_TO_PLACE;
    }

    /** One bucket from the tank as a source block, by vanilla's bucket rules (Nether water boils away, slabs get waterlogged). */
    private boolean placeFluid(ServerLevel level, FakePlayer player, BlockPos front) {
        FluidResource fluid = tank.getResource(0);
        if (fluid.isEmpty() || tank.getAmountAsLong(0) < FluidAmounts.PER_BUCKET || !fluid.isComponentsPatchEmpty()
                || !passes(fluid.getFluid())) return false;
        // Water is replaceable by water: never pour onto a source of the same fluid.
        if (level.getFluidState(front).isSourceOfType(fluid.getFluid())) return false;
        return !FluidUtil.tryPlaceFluid(tank, player, level, front, true, null).isEmpty();
    }

    private static boolean standsInFront(ServerLevel level, BlockPos front) {
        return !level.getEntities((Entity) null, new AABB(front), entity -> entity.blocksBuilding).isEmpty();
    }

    private boolean crowded(ServerLevel level) {
        int radius = JasmConfig.BAY_DROP_ENTITY_RADIUS.getAsInt();
        return level.getEntitiesOfClass(Entity.class, new AABB(front()).inflate(radius)).size()
                >= JasmConfig.BAY_DROP_ENTITY_LIMIT.getAsInt();
    }

    /**
     * Drops the stack just outside the bay's face, at a random spot across it, drifting slowly straight out. When a
     * full block fills the front space, it goes to the first open space beside that block instead, never back into the
     * bay.
     */
    private void throwOut(ServerLevel level, ItemStack stack) {
        Direction out = facing();
        BlockPos front = front();
        if (level.getBlockState(front).isCollisionShapeFullBlock(level, front)) {
            BlockPos spare = spareSpace(level, front, out.getOpposite());
            if (spare != null) {
                spawnThrown(level, stack, out, spare.getX() + 0.5, spare.getY() + 0.5 - THROW_HALF, spare.getZ() + 0.5);
                return;
            }
        }
        RandomSource random = level.getRandom();
        double[] middle = new double[3];
        for (Direction.Axis axis : Direction.Axis.values()) {
            double spot;
            if (axis != out.getAxis()) spot = THROW_HALF + random.nextDouble() * (1 - 2 * THROW_HALF);
            else if (out.getAxisDirection() == Direction.AxisDirection.POSITIVE) spot = THROW_GAP + THROW_HALF;
            else spot = 1 - THROW_GAP - THROW_HALF;
            middle[axis.ordinal()] = spot;
        }
        spawnThrown(level, stack, out, front.getX() + middle[0], front.getY() + middle[1] - THROW_HALF, front.getZ() + middle[2]);
    }

    /** Above the front block first, then round its sides, skipping the bay. Only spaces with nothing solid in them. */
    private static @Nullable BlockPos spareSpace(ServerLevel level, BlockPos front, Direction toBay) {
        if (toBay != Direction.UP && open(level, front.above())) return front.above();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (side != toBay && open(level, front.relative(side))) return front.relative(side);
        }
        return null;
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static void spawnThrown(ServerLevel level, ItemStack stack, Direction out, double x, double y, double z) {
        level.addFreshEntity(new ItemEntity(level, x, y, z, stack,
                out.getStepX() * THROW_SPEED, out.getStepY() * THROW_SPEED, out.getStepZ() * THROW_SPEED));
    }

    /** Uses the slot's stack on the front space; true if any of it was used. */
    private boolean placeFrom(ServerLevel level, FakePlayer player, BlockPos front, int slot) {
        ItemStack stack = items.get(slot);
        ItemStack hand = stack.copy();
        BlockState before = level.getBlockState(front);
        player.setItemInHand(InteractionHand.MAIN_HAND, hand);
        try {
            // Through the stack, not the item, so claim mods hear about the placement.
            hand.useOn(new BayPlaceContext(level, player, front, facing(), hand));
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        int used = stack.getCount() - hand.getCount();
        if (used <= 0) return level.getBlockState(front) != before;
        stack.shrink(used);
        gridChanged();
        return true;
    }

    public DeployMode mode() {
        return mode;
    }

    public void toggleMode() {
        mode = mode.toggle();
        setChanged();
        wake();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("mode", DeployMode.CODEC, mode);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        mode = input.read("mode", DeployMode.CODEC).orElse(DeployMode.PLACE);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (mode != DeployMode.PLACE) components.set(JasmComponents.DEPLOY_MODE.get(), mode);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        mode = components.getOrDefault(JasmComponents.DEPLOY_MODE.get(), DeployMode.PLACE);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("mode");
    }
}
