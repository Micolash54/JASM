package dev.micolash.jasm.network;

import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * Who may use a crafting-network block: its owner, and the players an Encoding Terminal of that same owner, on the
 * same network, trusts. Joining a cable to someone else's blocks never gives access to them.
 */
public final class MachineAccess {
    private MachineAccess() {}

    public static boolean canUse(MachineBlockEntity machine, Player player) {
        if (machine.isOwner(player)) {
            return true;
        }
        if (machine instanceof EncodingTerminalBlockEntity terminal && terminal.trust().contains(player.getUUID())) {
            return true;
        }
        if (!(machine.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        CableNetwork network = Networks.at(level, machine.getBlockPos());
        return network != null && trustedBy(network, machine.owner(), player.getUUID());
    }

    /** Whether an Encoding Terminal on {@code network} that belongs to {@code owner} trusts {@code player}. */
    public static boolean trustedBy(CableNetwork network, UUID owner, UUID player) {
        for (EncodingTerminalBlockEntity terminal : network.machines(EncodingTerminalBlockEntity.class)) {
            if (owner.equals(terminal.owner()) && terminal.trust().contains(player)) {
                return true;
            }
        }
        return false;
    }
}
