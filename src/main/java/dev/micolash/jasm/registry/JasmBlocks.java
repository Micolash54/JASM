package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveBlock;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.autocraft.AccessPortBlock;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.autocraft.CraftingServerBlock;
import dev.micolash.jasm.autocraft.CraftingServerBlockEntity;
import dev.micolash.jasm.autocraft.EncodingTerminalBlock;
import dev.micolash.jasm.autocraft.EncodingTerminalBlockEntity;
import dev.micolash.jasm.autocraft.RecipeRackBlock;
import dev.micolash.jasm.autocraft.RecipeRackBlockEntity;
import dev.micolash.jasm.battery.CreativeBatteryBlock;
import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.generator.CombustionGeneratorBlock;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.network.DataCableBlock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

public final class JasmBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Jasm.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Jasm.MODID);

    public static final DeferredBlock<CreativeBatteryBlock> CREATIVE_BATTERY = BLOCKS.registerBlock("creative_battery",
            CreativeBatteryBlock::new, p -> p.mapColor(MapColor.COLOR_MAGENTA).strength(1.5F).sound(SoundType.METAL));

    private static final Map<GeneratorTier, DeferredBlock<CombustionGeneratorBlock>> GENERATORS = new EnumMap<>(GeneratorTier.class);

    static {
        for (GeneratorTier tier : GeneratorTier.values()) {
            GENERATORS.put(tier, BLOCKS.registerBlock(tier.registryName(), p -> new CombustionGeneratorBlock(p, tier),
                    p -> p.mapColor(MapColor.STONE).requiresCorrectToolForDrops().strength(3.5F).sound(SoundType.METAL)
                            .lightLevel(state -> state.getValue(CombustionGeneratorBlock.LIT) ? 13 : 0)));
        }
    }

    private static final Map<ArchiveTier, DeferredBlock<ArchiveBlock>> ARCHIVES = new EnumMap<>(ArchiveTier.class);

    static {
        for (ArchiveTier tier : ArchiveTier.values()) {
            ARCHIVES.put(tier, BLOCKS.registerBlock(tier.registryName(), p -> new ArchiveBlock(p, tier),
                    p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE).strength(3.0F, 3_600_000.0F).pushReaction(PushReaction.IMMOVEABLE).sound(SoundType.METAL)));
        }
    }

    public static final Supplier<BlockEntityType<ArchiveBlockEntity>> ARCHIVE_ENTITY = BLOCK_ENTITIES.register("archive",
            () -> new BlockEntityType<>(ArchiveBlockEntity::new, ARCHIVES.values().stream().map(DeferredBlock::get).collect(Collectors.toSet())));

    public static final Supplier<BlockEntityType<CreativeBatteryBlockEntity>> CREATIVE_BATTERY_ENTITY = BLOCK_ENTITIES.register(
            "creative_battery", () -> new BlockEntityType<>(CreativeBatteryBlockEntity::new, CREATIVE_BATTERY.get()));

    public static final Supplier<BlockEntityType<CombustionGeneratorBlockEntity>> COMBUSTION_GENERATOR_ENTITY = BLOCK_ENTITIES.register(
            "combustion_generator", () -> new BlockEntityType<>(CombustionGeneratorBlockEntity::new,
                    GENERATORS.values().stream().map(DeferredBlock::get).collect(Collectors.toSet())));

    public static final DeferredBlock<EncodingTerminalBlock> ENCODING_TERMINAL = BLOCKS.registerBlock("encoding_terminal",
            EncodingTerminalBlock::new, p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.METAL));

    public static final Supplier<BlockEntityType<EncodingTerminalBlockEntity>> ENCODING_TERMINAL_ENTITY = BLOCK_ENTITIES.register(
            "encoding_terminal", () -> new BlockEntityType<>(EncodingTerminalBlockEntity::new, ENCODING_TERMINAL.get()));

    public static final DeferredBlock<RecipeRackBlock> RECIPE_RACK = BLOCKS.registerBlock("recipe_rack",
            RecipeRackBlock::new, p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.METAL));

    public static final Supplier<BlockEntityType<RecipeRackBlockEntity>> RECIPE_RACK_ENTITY = BLOCK_ENTITIES.register(
            "recipe_rack", () -> new BlockEntityType<>(RecipeRackBlockEntity::new, RECIPE_RACK.get()));

    public static final DeferredBlock<CraftingServerBlock> CRAFTING_SERVER = BLOCKS.registerBlock("crafting_server",
            CraftingServerBlock::new, p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.5F).sound(SoundType.METAL));

    public static final Supplier<BlockEntityType<CraftingServerBlockEntity>> CRAFTING_SERVER_ENTITY = BLOCK_ENTITIES.register(
            "crafting_server", () -> new BlockEntityType<>(CraftingServerBlockEntity::new, CRAFTING_SERVER.get()));

    public static final DeferredBlock<AccessPortBlock> ACCESS_PORT = BLOCKS.registerBlock("access_port",
            AccessPortBlock::new, p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.METAL)
                    .noOcclusion());

    public static final Supplier<BlockEntityType<AccessPortBlockEntity>> ACCESS_PORT_ENTITY = BLOCK_ENTITIES.register(
            "access_port", () -> new BlockEntityType<>(AccessPortBlockEntity::new, ACCESS_PORT.get()));

    /** Undyed first, then one per dye colour. */
    private static final Map<Optional<DyeColor>, DeferredBlock<DataCableBlock>> CABLES = new LinkedHashMap<>();

    static {
        CABLES.put(Optional.empty(), BLOCKS.registerBlock("data_cable", p -> new DataCableBlock(p, null), JasmBlocks::cableProperties));
        for (DyeColor color : DyeColor.values()) {
            CABLES.put(Optional.of(color), BLOCKS.registerBlock(color.getSerializedName() + "_data_cable", p -> new DataCableBlock(p, color),
                    JasmBlocks::cableProperties));
        }
    }

    private static BlockBehaviour.Properties cableProperties(BlockBehaviour.Properties p) {
        return p.mapColor(MapColor.COLOR_GRAY).strength(0.5F).sound(SoundType.METAL).noOcclusion();
    }

    /** The cable of {@code color}, or the undyed one for null. */
    public static DeferredBlock<DataCableBlock> cable(@Nullable DyeColor color) {
        return CABLES.get(Optional.ofNullable(color));
    }

    public static List<DeferredBlock<DataCableBlock>> cables() {
        return List.copyOf(CABLES.values());
    }

    public static DeferredBlock<CombustionGeneratorBlock> generator(GeneratorTier tier) {
        return GENERATORS.get(tier);
    }

    public static DeferredBlock<ArchiveBlock> archive(ArchiveTier tier) {
        return ARCHIVES.get(tier);
    }

    private JasmBlocks() {}
}
