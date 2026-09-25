package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveBlock;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.archive.ArchiveTier;
import dev.micolash.jasm.battery.CreativeBatteryBlock;
import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.generator.CombustionGeneratorBlock;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Jasm.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Jasm.MODID);

    public static final DeferredBlock<CreativeBatteryBlock> CREATIVE_BATTERY = BLOCKS.registerBlock("creative_battery",
            CreativeBatteryBlock::new, p -> p.mapColor(MapColor.COLOR_MAGENTA).strength(1.5F).sound(SoundType.METAL));

    public static final DeferredBlock<CombustionGeneratorBlock> COMBUSTION_GENERATOR = BLOCKS.registerBlock("combustion_generator",
            CombustionGeneratorBlock::new, p -> p.mapColor(MapColor.STONE).requiresCorrectToolForDrops().strength(3.5F).sound(SoundType.METAL)
                    .lightLevel(state -> state.getValue(CombustionGeneratorBlock.LIT) ? 13 : 0));

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
            "combustion_generator", () -> new BlockEntityType<>(CombustionGeneratorBlockEntity::new, COMBUSTION_GENERATOR.get()));

    public static DeferredBlock<ArchiveBlock> archive(ArchiveTier tier) {
        return ARCHIVES.get(tier);
    }

    private JasmBlocks() {}
}
