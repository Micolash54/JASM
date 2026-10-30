package dev.micolash.jasm.wrench;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.bay.BayBlock;
import dev.micolash.jasm.registry.JasmTags;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Any wrench, ours or another mod's, turns JASM blocks with a front and picks JASM blocks up when sneaking. Picking up is
 * mining without the wait, so the block keeps what mining keeps. The block, then whatever its slots spill, goes into the
 * player's inventory; only what doesn't fit lands on the ground. Other mods' blocks are left to their own wrench handling.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Wrenching {
    /** The player whose wrench pick-up is breaking a block right now; what it drops goes to their inventory first. */
    private static @Nullable ServerPlayer pickingUp;
    /** The space of the block being picked up: items spilled inside it are its contents. */
    private static @Nullable AABB pickingUpAt;
    /**
     * What the block spilled while it was being removed, held back so the block itself gets the first free slot. Emptied
     * at the end of every pick-up.
     */
    private static final List<ItemEntity> spilled = new ArrayList<>();

    private Wrenching() {}

    /** Drops a picked-up part: into the wrenching player's inventory if there is room, else onto the ground. */
    public static void pop(Level level, BlockPos pos, ItemStack stack) {
        if (pickingUp != null) {
            give(pickingUp, stack);
        }
        if (!stack.isEmpty()) {
            Block.popResource(level, pos, stack);
        }
    }

    /** The block's own drops go to the inventory too; whatever doesn't fit drops as before. */
    @SubscribeEvent
    static void drops(BlockDropsEvent event) {
        ServerPlayer player = pickingUp;
        if (player == null || event.getBreaker() != player) {
            return;
        }
        event.getDrops().removeIf(drop -> {
            ItemStack stack = drop.getItem().copy();
            give(player, stack);
            drop.setItem(stack);
            return stack.isEmpty();
        });
    }

    /** Contents spilled by the block being picked up wait, unspawned, until the block's own drop is in the inventory. */
    @SubscribeEvent
    static void spill(EntityJoinLevelEvent event) {
        ServerPlayer player = pickingUp;
        AABB at = pickingUpAt;
        if (player != null && at != null && !event.loadedFromDisk() && event.getEntity() instanceof ItemEntity item
                && event.getLevel() == player.level() && at.contains(item.position())) {
            spilled.add(item);
            event.setCanceled(true);
        }
    }

    /**
     * Moves as much of {@code stack} as has room into the inventory, leaving the rest in it. Unlike the inventory's own
     * add, nothing is thrown away in creative when it doesn't fit.
     */
    private static void give(ServerPlayer player, ItemStack stack) {
        Inventory inventory = player.getInventory();
        while (!stack.isEmpty()) {
            int slot = inventory.getSlotWithRemainingSpace(stack);
            if (slot >= 0) {
                ItemStack there = inventory.getItem(slot);
                int moved = Math.min(stack.getCount(), inventory.getMaxStackSize(there) - there.getCount());
                there.grow(moved);
                stack.shrink(moved);
            } else {
                slot = inventory.getFreeSlot();
                if (slot < 0) break;
                inventory.setItem(slot, stack.split(Math.min(stack.getCount(), inventory.getMaxStackSize(stack))));
            }
        }
        inventory.setChanged();
    }

    /** The held-back contents go into the inventory as far as they fit; the rest spawns as it would have. */
    private static void giveSpilled(ServerPlayer player) {
        List<ItemEntity> held = List.copyOf(spilled);
        spilled.clear();
        for (ItemEntity item : held) {
            ItemStack stack = item.getItem().copy();
            give(player, stack);
            if (!stack.isEmpty()) {
                item.setItem(stack);
                item.level().addFreshEntity(item);
            }
        }
    }

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
                : !state.is(JasmTags.WRENCH_TURNABLE)
                        || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && !state.hasProperty(BlockStateProperties.FACING)) {
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
                pickingUp = serverPlayer;
                pickingUpAt = new AABB(pos).inflate(0.5);
                try {
                    if (serverPlayer.gameMode.destroyBlock(pos)) {
                        level.playSound(null, pos, SoundEvents.SLIME_JUMP_SMALL, SoundSource.BLOCKS, 1.0F, 1.0F);
                    }
                } finally {
                    pickingUp = null;
                    pickingUpAt = null;
                    giveSpilled(serverPlayer);
                }
            } else {
                level.setBlock(pos, turned(state, event.getFace()), Block.UPDATE_ALL);
                level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
        }
    }

    /**
     * The front turns to the clicked side. Clicking the front (or, for blocks that only face sideways, the top or bottom)
     * turns it on: a quarter clockwise, or to the next direction for blocks that can also face up and down.
     */
    private static BlockState turned(BlockState state, @Nullable Direction clicked) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            Direction front = state.getValue(BlockStateProperties.FACING);
            Direction to = clicked != null && clicked != front ? clicked : Direction.from3DDataValue((front.get3DDataValue() + 1) % 6);
            // Bays never face up: skip on to the next direction.
            if (to == Direction.UP && state.getBlock() instanceof BayBlock) {
                to = Direction.from3DDataValue((to.get3DDataValue() + 1) % 6);
                if (to == front) to = Direction.from3DDataValue((to.get3DDataValue() + 1) % 6);
            }
            return state.setValue(BlockStateProperties.FACING, to);
        }
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
