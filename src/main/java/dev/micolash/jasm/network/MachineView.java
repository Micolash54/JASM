package dev.micolash.jasm.network;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** A screen that belongs to one network block, so the server can tell it when the network is full. */
public interface MachineView {
    /** The block's position on the server; null on the client. */
    @Nullable
    BlockPos machinePos();
}
