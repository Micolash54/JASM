package dev.micolash.jasm.wafer;

import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Wafer numbers are for admins: they appear in tooltips and the Archive list only for players allowed to use the
 * {@code /jasm} commands, which take them. Anyone else tells wafers apart by name, size and contents.
 */
public final class WaferNumbers {
    private WaferNumbers() {}

    public static boolean visibleTo(@Nullable Player player) {
        return player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
