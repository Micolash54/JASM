package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlockEntity;
import java.util.Collection;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import org.jspecify.annotations.Nullable;

/** Only combustion generators are fallback sources. Machine buffers and other acceptors never belong here. */
final class AcceptorGenerators {
    // Owned by one acceptor; old networks are dropped when its connections change.
    private final Map<CableNetwork, Sources> known = new IdentityHashMap<>();

    boolean changed() {
        for (Sources sources : known.values()) {
            if (sources.dirty) return true;
            for (CombustionGeneratorBlockEntity generator : sources.generators) if (generator.isRemoved()) return true;
        }
        return false;
    }

    List<CombustionGeneratorBlockEntity> find(ServerLevel level, Collection<CableNetwork> networks) {
        known.keySet().retainAll(networks);
        Set<CombustionGeneratorBlockEntity> found = new LinkedHashSet<>();
        for (CableNetwork network : networks) {
            if (network == null) continue;
            Sources sources = known.get(network);
            if (sources == null) {
                sources = new Sources(level, network);
                known.put(network, sources);
            }
            found.addAll(sources.generators(level));
        }
        return List.copyOf(found);
    }

    private record Watch(BlockPos pos, BlockCapabilityCache<EnergyHandler, @Nullable Direction> capability) {}

    /** Capabilities notify us when a source is placed, removed or replaced, even though it isn't a network member. */
    private final class Sources {
        private final List<Watch> watches = new ArrayList<>();
        private List<CombustionGeneratorBlockEntity> generators = List.of();
        private boolean dirty = true;

        Sources(ServerLevel level, CableNetwork network) {
            Set<BlockPos> seen = new LinkedHashSet<>();
            for (BlockPos pos : network.cables()) {
                if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof DataCableBlockEntity cable)) continue;
                for (Direction side : Direction.values()) {
                    if (cable.port(side) != null || cable.acceptor(side) != null) continue;
                    BlockPos next = pos.relative(side);
                    if (seen.add(next)) {
                        watches.add(new Watch(next, BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, next, null,
                                () -> known.get(network) == this, () -> dirty = true)));
                    }
                }
            }
        }

        List<CombustionGeneratorBlockEntity> generators(ServerLevel level) {
            for (CombustionGeneratorBlockEntity generator : generators) if (generator.isRemoved()) dirty = true;
            if (!dirty) return generators;
            dirty = false;
            List<CombustionGeneratorBlockEntity> found = new ArrayList<>();
            for (Watch watch : watches) {
                if (!level.isLoaded(watch.pos())) continue;
                // Asking arms the invalidation listener; the entity type decides whether it is a generator.
                watch.capability().getCapability();
                if (level.getBlockEntity(watch.pos()) instanceof CombustionGeneratorBlockEntity generator && !generator.isRemoved()) {
                    found.add(generator);
                }
            }
            generators = List.copyOf(found);
            return generators;
        }
    }
}
