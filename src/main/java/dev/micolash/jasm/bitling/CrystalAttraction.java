package dev.micolash.jasm.bitling;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.WildRate;
import dev.micolash.jasm.registry.JasmEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** A growing Data Crystal now and then draws a wild Bitling over to have a look. */
public final class CrystalAttraction {
    private static final int PLAYER_RANGE = 48;
    private static final int CROWD_RANGE = 32;
    private static final int MIN_DISTANCE = 12;
    private static final int MAX_DISTANCE = 24;

    private CrystalAttraction() {}

    /** Called from a Seeded Amethyst's random tick: tries now and then, as often as the spawn rate says. */
    public static void tryAttract(ServerLevel level, BlockPos crystal, RandomSource random) {
        if (random.nextDouble() < WildRate.chancePerRandomTick(randomTickSpeed(level), JasmConfig.WILD_SPAWN_RATE.getAsInt())) {
            attract(level, crystal, random);
        }
    }

    /** Random ticks each chunk section gets per game tick, as the server reads it. */
    private static int randomTickSpeed(ServerLevel level) {
        return level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
    }

    public static @Nullable WildBitling attract(ServerLevel level, BlockPos crystal, RandomSource random) {
        Vec3 centre = Vec3.atCenterOf(crystal);
        if (level.getNearestPlayer(centre.x, centre.y, centre.z, PLAYER_RANGE, p -> !p.isSpectator()) == null
                || !level.getEntitiesOfClass(WildBitling.class, new AABB(crystal).inflate(CROWD_RANGE)).isEmpty()) {
            return null;
        }
        WildBitling bitling = JasmEntities.WILD_BITLING.get().create(level, EntitySpawnReason.EVENT);
        if (bitling == null) {
            return null;
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            BlockPos column = BlockPos.containing(centre.x + Math.cos(angle) * distance, crystal.getY() + 3, centre.z + Math.sin(angle) * distance);
            // Only where the world is loaded and running, so it never loads a chunk or stands frozen at the edge.
            if (!level.isPositionEntityTicking(column)) {
                continue;
            }
            BlockPos ground = BitlingBody.groundAt(level, column);
            if (ground == null || !safe(level, ground)) {
                continue;
            }
            bitling.snapTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, random.nextFloat() * 360, 0);
            if (!level.noCollision(bitling)) {
                continue;
            }
            bitling.visit(crystal);
            level.addFreshEntity(bitling);
            return bitling;
        }
        return null;
    }

    /** Dry, with room to stand, on top of a solid block: no ponds and no lava. */
    private static boolean safe(ServerLevel level, BlockPos ground) {
        BlockPos below = ground.below();
        return level.getFluidState(ground).isEmpty() && level.getFluidState(ground.above()).isEmpty()
                && level.getFluidState(below).isEmpty() && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }
}
