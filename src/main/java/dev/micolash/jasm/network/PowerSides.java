package dev.micolash.jasm.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * What a power source has beside it. It is worked out again only after a neighbour changes, so a tick just reads the
 * result. A side counts as JASM when it holds a block that takes network power or another power source.
 */
public final class PowerSides {
    private final PowerReceiver[] receivers = new PowerReceiver[6];
    private final boolean[] jasm = new boolean[6];
    private boolean dirty = true;

    /** A neighbour changed: look again on the next tick. */
    public void changed() {
        dirty = true;
    }

    public void refresh(ServerLevel level, BlockPos origin) {
        // Blocks placed without a neighbour update (commands, structures, editing tools) are caught within a second.
        if ((level.getGameTime() + origin.asLong()) % 20 == 0) {
            dirty = true;
        }
        if (!dirty) {
            for (PowerReceiver receiver : receivers) {
                // A receiver whose chunk unloaded is a different block entity when the chunk comes back.
                if (receiver != null && !receiver.live()) {
                    dirty = true;
                    break;
                }
            }
            if (!dirty) return;
        }
        dirty = false;
        for (Direction side : Direction.values()) {
            int i = side.ordinal();
            receivers[i] = null;
            jasm[i] = false;
            BlockPos next = origin.relative(side);
            if (!level.isLoaded(next)) {
                dirty = true;
                continue;
            }
            BlockEntity entity = level.getBlockEntity(next);
            if (entity == null) continue;
            receivers[i] = PowerReceiver.of(entity);
            jasm[i] = receivers[i] != null || entity instanceof NetworkPowerSource;
            // A line of bare cables has no machine to wake its network, and the network is what moves power along it.
            if (entity instanceof DataCableBlockEntity) Networks.at(level, next);
        }
    }

    public @Nullable PowerReceiver receiver(Direction side) {
        return receivers[side.ordinal()];
    }

    public boolean jasm(Direction side) {
        return jasm[side.ordinal()];
    }
}
