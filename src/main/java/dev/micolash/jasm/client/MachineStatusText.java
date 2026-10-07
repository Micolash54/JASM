package dev.micolash.jasm.client;

import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.network.NetworkStatusSync;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** The line a machine screen shows when it can't work. */
public final class MachineStatusText {
    private MachineStatusText() {}

    /** What a machine screen says when it can't work: "Network full: 9 / 8 machines", or its usual "No power" line. */
    public static Component noPower(int containerId, String noPowerKey) {
        NetworkStatusSync.Full full = ClientNetworkStatus.full(containerId);
        return full == null
                ? Component.translatable(noPowerKey)
                : Component.translatable("screen.jasm.machine.network_full", full.count(), BrainBalance.shown(full.limit()));
    }

    /** As {@link #noPower}, but only "Network full" where the numbers don't fit in {@code width}. */
    public static Component noPower(int containerId, String noPowerKey, Font font, int width) {
        Component text = noPower(containerId, noPowerKey);
        return font.width(text) <= width || ClientNetworkStatus.full(containerId) == null
                ? text
                : Component.translatable("screen.jasm.server.pause.network_full");
    }
}
