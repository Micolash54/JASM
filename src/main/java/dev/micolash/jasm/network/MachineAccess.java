package dev.micolash.jasm.network;

import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.storage.ArchiveRecord;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * Who may use a crafting-network block or an Archive: its owner, and the players an Encoding Terminal of that same
 * owner, on the same network, trusts. Joining a cable to someone else's blocks never gives access to them.
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

    /** Server side. */
    public static boolean canUse(ArchiveBlockEntity archive, Player player) {
        ArchiveRecord record = archive.record();
        if (record == null) {
            return false;
        }
        if (record.owner().equals(player.getUUID())) {
            return true;
        }
        if (!(archive.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        CableNetwork network = Networks.at(level, archive.getBlockPos());
        return network != null && trustedBy(network, record.owner(), player.getUUID());
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
