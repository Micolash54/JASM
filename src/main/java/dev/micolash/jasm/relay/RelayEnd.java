package dev.micolash.jasm.relay;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * One end of a Relay Chatterbox pair, as the pairing sees it: the block entity of the block's lower half. Everything
 * here is read live each time the pairing looks, so it must come from the block's saved state, never from something
 * worked out on a tick: a pair is put back after a restart from these answers alone.
 */
public interface RelayEnd {
    /** No frequency set. */
    int NO_FREQUENCY = -1;
    /** Highest frequency; the lowest is 0. */
    int MAX_FREQUENCY = 999;

    /** The block entity's own world. */
    @Nullable Level getLevel();

    /** The block entity's own position. */
    BlockPos getBlockPos();

    /** Whether the block entity has left the world. */
    boolean isRemoved();

    /** Who owns the block; only ends with the same owner pair up. */
    @Nullable UUID owner();

    /** The frequency set on the block, 0 to {@link #MAX_FREQUENCY}, or {@link #NO_FREQUENCY}. */
    int frequency();

    /** Whether a Dimension Upgrade sits in this end. One end having it lets a pair link across dimensions. */
    boolean hasDimensionUpgrade();

    /** Whether this end can run the link right now: its Link Byteling has charge. Both must, to start a link. */
    boolean canRun();

    /** FE in this end's battery, which pays for the link. An end without a battery pays nothing. */
    default int charge() {
        return 0;
    }

    /** Moves the power this end's block was given into its battery. Must not call back into {@link RelayLinks}. */
    default void topUp() {}

    /** Takes {@code amount} FE, no more than {@link #charge()}, from this end's battery. Must not call back into {@link RelayLinks}. */
    default void spend(int amount) {}

    /** Called after this end's pairing or status may have changed. Must not call back into {@link RelayLinks}. */
    default void pairingChanged() {}
}
