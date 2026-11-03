package dev.micolash.jasm.config;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;

/**
 * Saving world rules from the settings screen. The screen sends what the player changed; the server checks who is asking,
 * checks every value, and only then writes them, so one bad value saves nothing. Written the way editing the file by hand
 * is: values that can change live do so at once, the ones that need the world to reload wait for that.
 */
public final class SettingsSave {
    /** What came of a save, sent back to the screen. */
    public enum Status {
        SAVED,
        /** The player isn't the owner of this world or an operator. */
        NOT_ALLOWED,
        /** A name or value wasn't one the world rules accept, so nothing was saved. */
        REFUSED
    }

    private SettingsSave() {}

    /** The owner of a singleplayer world, or anyone with operator rights, may change the world rules. */
    public static boolean mayEdit(PermissionSet permissions, boolean worldOwner) {
        return worldOwner || permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static boolean mayEdit(ServerPlayer player) {
        return mayEdit(player.permissions(), player.level().getServer().isSingleplayerOwner(player.nameAndId()));
    }

    /** The whole request from a player: the rights check, then {@link #apply}. */
    public static Status handle(ServerPlayer player, Map<String, String> changes) {
        return mayEdit(player) ? apply(changes) : Status.NOT_ALLOWED;
    }

    /**
     * Writes these changes (setting id to value text) into the world rules and saves the file. A preset is applied first, so
     * the numbers sent along with it stay as the player left them. Returns REFUSED, changing nothing, if any entry is unknown or invalid.
     */
    public static Status apply(Map<String, String> changes) {
        if (!JasmConfig.SPEC.isLoaded()) return Status.REFUSED;
        Map<Setting, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Setting setting = SettingsCatalog.WORLD.find(change.getKey());
            Object value = setting == null ? null : setting.parse(change.getValue());
            if (value == null) return Status.REFUSED;
            parsed.put(setting, value);
        }
        if (parsed.isEmpty()) return Status.SAVED;
        Setting preset = SettingsCatalog.WORLD.find("preset");
        Object chosen = preset == null ? null : parsed.remove(preset);
        if (chosen != null) {
            preset.write(chosen);
            Presets.applyIfChosen();
        }
        parsed.forEach(Setting::write);
        JasmConfig.SPEC.save();
        return Status.SAVED;
    }
}
