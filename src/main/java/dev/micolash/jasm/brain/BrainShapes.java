package dev.micolash.jasm.brain;

import dev.micolash.jasm.core.BrainCube;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/** Forms and breaks the cube of chambers around a brain. */
public final class BrainShapes {
    /** A safety stop for chains of brains passing chambers on; real builds settle in a handful of steps. */
    private static final int MAX_SPOTS = 512;

    private BrainShapes() {}

    /** Looks for the biggest cube the brain can use now and takes its chambers, letting go of any it no longer needs. */
    public static void reshape(ServerLevel level, NetworkBrainBlockEntity brain) {
        settle(level, reshapeOnly(level, brain));
    }

    /** Lets go of every chamber the brain holds; brains nearby may take them up. */
    public static void release(ServerLevel level, NetworkBrainBlockEntity brain) {
        settle(level, letGo(level, brain));
    }

    /** A chamber came or went: every brain close enough to share a cube with it looks again. */
    public static void recheckAround(ServerLevel level, BlockPos changed) {
        settle(level, List.of(changed.immutable()));
    }

    /**
     * Rechecks the brains around each spot. A brain that shrinks frees chambers, which another brain nearby may now
     * complete a cube with, so the spots of freed chambers are checked in turn.
     */
    private static void settle(ServerLevel level, Collection<BlockPos> spots) {
        LinkedHashSet<BlockPos> waiting = new LinkedHashSet<>(spots);
        int left = MAX_SPOTS;
        while (!waiting.isEmpty() && left-- > 0) {
            Iterator<BlockPos> next = waiting.iterator();
            BlockPos spot = next.next();
            next.remove();
            for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-2, -2, -2), spot.offset(2, 2, 2))) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof NetworkBrainBlockEntity brain) {
                    waiting.addAll(reshapeOnly(level, brain));
                }
            }
        }
    }

    /** Reshapes one brain. Returns the chambers it let go of and didn't take back. */
    private static List<BlockPos> reshapeOnly(ServerLevel level, NetworkBrainBlockEntity brain) {
        if (brain.leaving()) {
            return List.of();
        }
        BlockPos at = brain.getBlockPos();
        BrainCube.@Nullable Box found = BrainCube.find(at.getX(), at.getY(), at.getZ(), (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof NetworkChamberBlockEntity chamber
                    && (chamber.brainPos() == null || chamber.brainPos().equals(at));
        });
        if (Objects.equals(found, brain.box())) {
            return List.of();
        }
        List<BlockPos> freed = letGo(level, brain);
        brain.setBox(found);
        if (found != null) {
            forEach(found, pos -> {
                if (!pos.equals(at) && level.getBlockEntity(pos) instanceof NetworkChamberBlockEntity chamber) {
                    chamber.claim(at);
                    level.invalidateCapabilities(pos);
                }
            });
            freed.removeIf(pos -> found.contains(pos.getX(), pos.getY(), pos.getZ()));
        }
        level.invalidateCapabilities(at);
        return freed;
    }

    /** Lets go of every chamber the brain holds and returns where they are. */
    private static List<BlockPos> letGo(ServerLevel level, NetworkBrainBlockEntity brain) {
        List<BlockPos> freed = new ArrayList<>();
        BrainCube.Box box = brain.box();
        if (box == null) {
            return freed;
        }
        forEach(box, pos -> {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof NetworkChamberBlockEntity chamber
                    && brain.getBlockPos().equals(chamber.brainPos())) {
                chamber.claim(null);
                level.invalidateCapabilities(pos);
                freed.add(pos);
            }
        });
        brain.setBox(null);
        return freed;
    }

    static void forEach(BrainCube.Box box, Consumer<BlockPos> action) {
        for (BlockPos pos : BlockPos.betweenClosed(box.x(), box.y(), box.z(), box.x() + box.side() - 1, box.y() + box.side() - 1,
                box.z() + box.side() - 1)) {
            action.accept(pos.immutable());
        }
    }
}
