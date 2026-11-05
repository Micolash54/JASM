package dev.micolash.jasm.bay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jspecify.annotations.Nullable;

/**
 * The Deployment Bay's Interact mode: one right-click on what is in front, as its owner would make it with the first
 * grid slot's stack in hand (an empty hand if there is none). A mob or other entity in the front space comes first, then
 * the block there. Block items are never placed. What the click hands back (a filled bucket, milk) or drops nearby
 * (wool, berries) is gathered up as its results.
 */
final class BayInteraction {
    /** How far round the front space new items count as the click's results: sheared wool lands a block up. */
    private static final double DROPS_REACH = 1.0;

    private BayInteraction() {}

    /** What one click did: its status, the stack now in hand, and everything else it gave. */
    record Outcome(BayStatus status, ItemStack hand, List<ItemStack> results) {}

    /** The entity in the front space a click would reach: the nearest one a player could aim at, never another player. */
    static @Nullable Entity target(ServerLevel level, BlockPos front) {
        Vec3 middle = Vec3.atCenterOf(front);
        Entity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity entity : level.getEntities((Entity) null, new AABB(front),
                e -> e.isAlive() && e.isPickable() && !e.isSpectator() && !(e instanceof Player))) {
            double distance = entity.getBoundingBox().getCenter().distanceToSqr(middle);
            if (distance < best) {
                best = distance;
                nearest = entity;
            }
        }
        return nearest;
    }

    /** Whether anything is in front to click on: a block, a fluid or an entity. */
    static boolean anythingInFront(ServerLevel level, BlockPos front) {
        return !level.getBlockState(front).isAir() || target(level, front) != null;
    }

    /** Clicks once on the front with a copy of {@code held}. Nothing in the bay changes here; the bay takes the outcome. */
    static Outcome click(BayBlockEntity bay, ServerLevel level, BlockPos front, ItemStack held) {
        FakePlayer player = bay.fakePlayer(level);
        // Eyes in the middle of the bay, so items that look where the player looks (a bucket on water) see the front.
        player.snapTo(player.getX(), player.getY() - player.getEyeHeight(), player.getZ(), player.getYRot(), player.getXRot());
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        AABB around = new AABB(front).inflate(DROPS_REACH);
        Set<ItemEntity> lying = Collections.newSetFromMap(new IdentityHashMap<>());
        lying.addAll(level.getEntitiesOfClass(ItemEntity.class, around));
        player.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
        InteractionResult done = InteractionResult.PASS;
        boolean refused = false;
        Entity target = target(level, front);
        try {
            if (target != null) {
                Vec3 at = new Vec3(0, target.getBbHeight() / 2, 0);
                InteractionResult cancelled = CommonHooks.onInteractEntityAt(player, target, at, InteractionHand.MAIN_HAND);
                if (cancelled == null) cancelled = CommonHooks.onInteractEntity(player, target, InteractionHand.MAIN_HAND);
                if (cancelled == null) done = useOn(player, target, at);
                else if (cancelled.consumesAction()) done = cancelled;
                else refused = !bay.remember(level, front, false);  // a claim mod said no
            }
            BlockState state = level.getBlockState(front);
            if (!done.consumesAction() && !refused && !state.isAir()) {
                if (!bay.clickAllowedNow(level, player, front)) refused = true;
                else done = state.liquid() ? useOnFluid(level, player) : useOn(level, player, front, state, bay.facing());
            }
            // Nothing the click started may linger on the stand-in player, who never ticks. Food or a bow only starts
            // being used and would need the button held, so that counts as nothing happening.
            if (player.isUsingItem()) {
                player.stopUsingItem();
                done = InteractionResult.PASS;
            }
            if (player.containerMenu != player.inventoryMenu) player.closeContainer();
            if (target instanceof AbstractVillager villager && villager.getTradingPlayer() == player) villager.setTradingPlayer(null);
            ItemStack hand = player.getMainHandItem().copy();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            List<ItemStack> results = new ArrayList<>();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()) results.add(stack.copy());
            }
            for (ItemEntity dropped : level.getEntitiesOfClass(ItemEntity.class, around, ItemEntity::isAlive)) {
                if (lying.contains(dropped)) continue;
                results.add(dropped.getItem().copy());
                dropped.discard();
            }
            boolean happened = done.consumesAction() || !results.isEmpty() || !ItemStack.matches(hand, held);
            BayStatus status = happened ? BayStatus.WORKING : refused ? BayStatus.CLAIMED : BayStatus.NOTHING_HAPPENED;
            return new Outcome(status, hand, results);
        } finally {
            inventory.clearContent();
        }
    }

    /** A player's click on an entity: the entity's own answer first, then the item's (shears, a name tag, a bucket). */
    private static InteractionResult useOn(FakePlayer player, Entity target, Vec3 at) {
        InteractionResult result = target.interact(player, InteractionHand.MAIN_HAND, at);
        if (result.consumesAction()) return result;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || !(target instanceof LivingEntity living)) return InteractionResult.PASS;
        result = stack.interactLivingEntity(player, living, InteractionHand.MAIN_HAND);
        if (result.consumesAction()) {
            player.level().gameEvent(GameEvent.ENTITY_INTERACT, target.position(), GameEvent.Context.of(player));
            if (stack.isEmpty()) player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        return result;
    }

    /**
     * A player's click on a block, as the game makes it once claim mods have had their say: the block's answer with the
     * item, then with an empty hand (a lever, a berry bush), then the item's own use on it (bone meal). A block item is
     * only offered to the block (a flower to a pot, seeds to a composter), never placed.
     */
    private static InteractionResult useOn(ServerLevel level, FakePlayer player, BlockPos front, BlockState state, Direction look) {
        BlockHitResult aim = BayPlaceContext.aimAt(front, look);
        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND, aim);
        ItemStack stack = player.getMainHandItem();
        boolean blockItem = stack.getItem() instanceof BlockItem;
        if (!stack.isEmpty() && !blockItem) {
            InteractionResult first = stack.onItemUseFirst(context);
            if (first != InteractionResult.PASS) return first;
        }
        InteractionResult result = state.useItemOn(stack, level, player, InteractionHand.MAIN_HAND, aim);
        if (result.consumesAction()) return result;
        if (result instanceof InteractionResult.TryEmptyHandInteraction) {
            result = state.useWithoutItem(level, player, aim);
            if (result.consumesAction()) return result;
        }
        stack = player.getMainHandItem();
        if (stack.isEmpty() || blockItem) return InteractionResult.PASS;
        return stack.useOn(context);
    }

    /** A fluid in front can't be clicked as a block; items that work on it (a bucket, a bottle) look for it themselves. */
    private static InteractionResult useOnFluid(ServerLevel level, FakePlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || stack.getItem() instanceof BlockItem) return InteractionResult.PASS;
        InteractionResult result = stack.use(level, player, InteractionHand.MAIN_HAND);
        if (result instanceof InteractionResult.Success success && success.heldItemTransformedTo() != null) {
            player.setItemInHand(InteractionHand.MAIN_HAND, success.heldItemTransformedTo());
        }
        return result;
    }
}
