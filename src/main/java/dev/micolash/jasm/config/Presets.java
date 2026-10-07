package dev.micolash.jasm.config;

import dev.micolash.jasm.Jasm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** What each preset sets. Standard is every value's default; Relaxed and Hardcore are first guesses. */
public final class Presets {
    public static final int HARDCORE_ULTIMATE_SLOTS = 16;

    private record Row<T>(ModConfigSpec.ConfigValue<T> value, T relaxed, T hardcore) {
        void apply(Preset preset) {
            value.set(switch (preset) {
                case RELAXED -> relaxed;
                case HARDCORE -> hardcore;
                case STANDARD -> value.getDefault();
            });
        }
    }

    private static List<Row<?>> rows() {
        return List.of(
                new Row<Integer>(JasmConfig.DECK_SLOTS_STARTER, 2, 1),
                new Row<Integer>(JasmConfig.DECK_SLOTS_BASIC, 6, 2),
                new Row<Integer>(JasmConfig.DECK_SLOTS_ADVANCED, 12, 4),
                new Row<Integer>(JasmConfig.DECK_SLOTS_ELITE, 24, 8),
                new Row<Integer>(JasmConfig.DECK_SLOTS_ULTIMATE, 48, HARDCORE_ULTIMATE_SLOTS),
                new Row<Double>(JasmConfig.BRAIN_MULTIPLIER, 2.0, 0.5),
                new Row<Integer>(JasmConfig.WILD_SPAWN_RATE, 200, 50),
                new Row<Integer>(JasmConfig.DECK_ENERGY_PER_ITEM, 0, 2),
                new Row<Integer>(JasmConfig.CRAFT_TICKS, 5, 20),
                new Row<Integer>(JasmConfig.TRAINING_BITLING, 50, 200),
                new Row<Integer>(JasmConfig.TRAINING_NIBBLING, 150, 600));
    }

    private Presets() {}

    /** The settings a preset writes, so a test (or a screen) can tell which ones a preset reaches. */
    public static List<ModConfigSpec.ConfigValue<?>> covered() {
        return rows().stream().<ModConfigSpec.ConfigValue<?>>map(Row::value).toList();
    }

    private static Path appliedFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve("jasm-preset.txt");
    }

    /** The preset whose numbers were last written into this world; a world that never had one is on Standard. */
    private static Preset applied(MinecraftServer server) {
        try {
            return Preset.valueOf(Files.readString(appliedFile(server)).strip().toUpperCase(Locale.ROOT));
        } catch (IOException | IllegalArgumentException e) {
            return Preset.STANDARD;
        }
    }

    /**
     * If the chosen preset is not the one already written into this world, writes its numbers. The choice stays showing, and
     * the numbers can still be changed one by one afterwards. Only the logical server writes: a player who joins a server
     * gets the server's file and never changes it.
     */
    public static boolean applyIfChosen() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        // A new singleplayer world is about to get its own copy of the file, so the shared one is left as it is.
        if (!JasmConfig.SPEC.isLoaded() || server == null || WorldSettings.waitingForCopy(server)) {
            return false;
        }
        Preset preset = JasmConfig.PRESET.get();
        if (preset == applied(server)) {
            return false;
        }
        rows().forEach(row -> row.apply(preset));
        try {
            // Remembered before the file is saved, so the save's reload doesn't write the numbers a second time.
            Files.createDirectories(appliedFile(server).getParent());
            Files.writeString(appliedFile(server), preset.name());
        } catch (IOException e) {
            Jasm.LOGGER.warn("Could not remember which preset this world uses", e);
        }
        JasmConfig.SPEC.save();
        return true;
    }
}
