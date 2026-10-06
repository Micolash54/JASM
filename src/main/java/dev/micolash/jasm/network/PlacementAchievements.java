package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.brain.BrainShapes;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.brain.NetworkChamberBlockEntity;
import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.registry.JasmTriggers;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/** Achievements for placing a block: Overcooked, Office Space, Bitling Condo. Only the player who placed it gets them. */
public final class PlacementAchievements {
    private PlacementAchievements() {}

    // after the block joined its network, or a port went on a cable
    public static void placed(ServerLevel level, BlockPos pos, @Nullable LivingEntity by) {
        if (!(by instanceof ServerPlayer player)) {
            return;
        }
        BlockEntity placed = level.getBlockEntity(pos);
        // with a player placing it, the block's onPlace runs after this (neoforge holds it back), so the floor isn't
        // formed yet and chamber-last floors never counted. forming it here comes to the same thing
        if (placed instanceof NetworkBrainBlockEntity brain) {
            BrainShapes.reshape(level, brain);
            brainFloor(level, pos, player);
        } else if (placed instanceof NetworkChamberBlockEntity) {
            BrainShapes.recheckAround(level, pos);
            brainFloor(level, pos, player);
        } else if (!(placed instanceof MachineBlockEntity machine && !machine.countsTowardLimit()) && !has(player, "overcooked")) {
            // the block's first tick would look the network up anyway
            CableNetwork network = Networks.at(level, pos);
            if (network != null && network.limitState().stopped()) {
                JasmTriggers.NETWORK_OVERFULL.get().trigger(player);
            }
        }
    }

    private static void brainFloor(ServerLevel level, BlockPos pos, ServerPlayer player) {
        int maxFloors = Math.max(1, BrainBalance.fromConfig().maxFloors());
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 0, 1))) {
            if (level.isLoaded(near) && level.getBlockEntity(near) instanceof NetworkBrainBlockEntity brain && brain.floor()) {
                JasmTriggers.BRAIN_FLOOR.get().trigger(player);
                if (brain.floors() >= maxFloors) {
                    JasmTriggers.BRAIN_TOWER.get().trigger(player);
                }
                return;
            }
        }
    }

    private static boolean has(ServerPlayer player, String name) {
        AdvancementHolder advancement = player.level().getServer().getAdvancements().get(Jasm.id("main/" + name));
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }
}
