package dev.micolash.jasm.brain;

import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.core.BrainFloor;
import dev.micolash.jasm.core.BrainTower;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/** Forms and breaks the floor of chambers round a brain, and stacks floors into towers. */
public final class BrainShapes {
    /** A safety stop for chains of brains passing chambers on; real builds settle in a handful of steps. */
    private static final int MAX_SPOTS = 512;

    private BrainShapes() {}

    /** Looks again at the brain's floor, and at its tower. */
    public static void reshape(ServerLevel level, NetworkBrainBlockEntity brain) {
        settle(level, reshapeOnly(level, brain));
        restack(level, brain.getBlockPos());
    }

    /** Lets go of every chamber the brain holds; brains nearby may take them up. The floors above and below it part. */
    public static void release(ServerLevel level, NetworkBrainBlockEntity brain) {
        handOnPool(level, brain);
        settle(level, letGo(level, brain));
        restack(level, brain.getBlockPos());
    }

    /** A lowest floor holds its tower's pool. When it goes, the floor above it takes the pool on. */
    private static void handOnPool(ServerLevel level, NetworkBrainBlockEntity leaving) {
        NetworkBrainBlockEntity heir = floorAt(level, leaving.getBlockPos().above());
        if (heir != null && leaving.floor() && leaving.leadsItsTower() && leaving.stored() > 0) {
            heir.store(heir.stored() + leaving.stored());
            leaving.store(0);
        }
    }

    /** A chamber came or went: every brain close enough to share a floor with it looks again. */
    public static void recheckAround(ServerLevel level, BlockPos changed) {
        settle(level, List.of(changed.immutable()));
    }

    /**
     * Rechecks the brains round each spot. A brain that loses its floor frees chambers, which another brain nearby may now
     * complete a floor with, so the spots of freed chambers are checked in turn.
     */
    private static void settle(ServerLevel level, Collection<BlockPos> spots) {
        LinkedHashSet<BlockPos> waiting = new LinkedHashSet<>(spots);
        int left = MAX_SPOTS;
        while (!waiting.isEmpty() && left-- > 0) {
            Iterator<BlockPos> next = waiting.iterator();
            BlockPos spot = next.next();
            next.remove();
            for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-1, 0, -1), spot.offset(1, 0, 1))) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof NetworkBrainBlockEntity brain) {
                    waiting.addAll(reshapeOnly(level, brain));
                }
            }
        }
    }

    /** Reshapes one brain's floor. Returns the chambers it let go of. */
    private static List<BlockPos> reshapeOnly(ServerLevel level, NetworkBrainBlockEntity brain) {
        if (brain.leaving()) {
            return List.of();
        }
        BlockPos at = brain.getBlockPos();
        boolean complete = BrainFloor.complete(at.getX(), at.getY(), at.getZ(), (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof NetworkChamberBlockEntity chamber
                    && (chamber.brainPos() == null || chamber.brainPos().equals(at));
        });
        if (complete == brain.floor()) {
            return List.of();
        }
        List<BlockPos> freed = complete ? List.of() : letGo(level, brain);
        if (complete) {
            brain.setFloor(true);
            forEachChamber(at, pos -> {
                if (level.getBlockEntity(pos) instanceof NetworkChamberBlockEntity chamber) {
                    chamber.claim(at);
                    level.invalidateCapabilities(pos);
                }
            });
        }
        level.invalidateCapabilities(at);
        restack(level, at);
        return freed;
    }

    /** Lets go of every chamber the brain holds and returns where they are. */
    private static List<BlockPos> letGo(ServerLevel level, NetworkBrainBlockEntity brain) {
        List<BlockPos> freed = new ArrayList<>();
        if (!brain.floor()) {
            return freed;
        }
        BlockPos at = brain.getBlockPos();
        forEachChamber(at, pos -> {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof NetworkChamberBlockEntity chamber
                    && at.equals(chamber.brainPos())) {
                chamber.claim(null);
                level.invalidateCapabilities(pos);
                freed.add(pos);
            }
        });
        brain.setFloor(false);
        return freed;
    }

    /**
     * Works out the towers in the brain column through {@code at}: the brain there, and the runs of floors right above and
     * below it. Every brain in them learns its tower's lowest floor and how many floors it has. Brains of one column share
     * a chunk, so they are all loaded or none.
     */
    static void restack(ServerLevel level, BlockPos at) {
        int maxFloors = Math.max(1, BrainBalance.fromConfig().maxFloors());
        int stamp = BrainBalance.stamp();
        List<NetworkBrainBlockEntity> moved = new ArrayList<>();
        NetworkBrainBlockEntity self = floorAt(level, at);
        if (self != null) {
            stackRun(level, at, maxFloors, stamp, moved);
        } else {
            if (level.isLoaded(at) && level.getBlockEntity(at) instanceof NetworkBrainBlockEntity lone && !lone.leaving()) {
                retower(lone, at, 0, stamp, moved);
            }
            if (floorAt(level, at.below()) != null) {
                stackRun(level, at.below(), maxFloors, stamp, moved);
            }
            if (floorAt(level, at.above()) != null) {
                stackRun(level, at.above(), maxFloors, stamp, moved);
            }
        }
        shareOutPools(moved);
    }

    /** Puts a brain in its tower. A brain whose tower changed goes in {@code moved}; one that didn't is only trimmed to its pool's size. */
    private static void retower(NetworkBrainBlockEntity brain, BlockPos base, int floors, int stamp, List<NetworkBrainBlockEntity> moved) {
        if (brain.setTower(base, floors, stamp)) {
            moved.add(brain);
        } else {
            brain.foldIntoPool();
            brain.store(Math.min(brain.stored(), brain.capacity()));
        }
    }

    /**
     * Each tower keeps one pool, in its lowest floor. Where towers were cut or joined, the power their brains held is
     * shared out again by floors, and what a smaller pool can't hold is lost.
     */
    private static void shareOutPools(List<NetworkBrainBlockEntity> moved) {
        long total = 0;
        long weights = 0;
        for (NetworkBrainBlockEntity brain : moved) {
            total += brain.stored();
            if (brain.leadsItsTower()) {
                weights += Math.max(1, brain.floors());
            }
        }
        for (NetworkBrainBlockEntity brain : moved) {
            int wanted = brain.leadsItsTower() && weights > 0
                    ? (int) Math.min(Integer.MAX_VALUE, total * Math.max(1, brain.floors()) / weights) : 0;
            brain.store(Math.min(wanted, brain.capacity()));
        }
    }

    /** Hands out the towers of the run of floors through {@code from}, cut by the height limit from the bottom up. */
    private static void stackRun(ServerLevel level, BlockPos from, int maxFloors, int stamp, List<NetworkBrainBlockEntity> moved) {
        BlockPos bottom = from;
        while (floorAt(level, bottom.below()) != null) {
            bottom = bottom.below();
        }
        List<NetworkBrainBlockEntity> run = new ArrayList<>();
        for (BlockPos pos = bottom; ; pos = pos.above()) {
            NetworkBrainBlockEntity brain = floorAt(level, pos);
            if (brain == null) {
                break;
            }
            run.add(brain);
        }
        for (int i = 0; i < run.size(); i++) {
            BrainTower.Tower tower = BrainTower.inRun(bottom.getY() + i, bottom.getY(), bottom.getY() + run.size() - 1, maxFloors);
            retower(run.get(i), bottom.atY(tower.baseY()), tower.floors(), stamp, moved);
        }
    }

    /** The brain at a spot, if it is loaded, staying, and the middle of a complete floor. */
    private static @Nullable NetworkBrainBlockEntity floorAt(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof NetworkBrainBlockEntity brain && brain.floor() && !brain.leaving() ? brain : null;
    }

    /** The 8 spots round a brain on its layer. */
    static void forEachChamber(BlockPos at, Consumer<BlockPos> action) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    action.accept(at.offset(dx, 0, dz));
                }
            }
        }
    }
}
