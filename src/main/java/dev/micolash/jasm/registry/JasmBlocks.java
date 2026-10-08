package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.acceptor.PowerAcceptorBlock;
import dev.micolash.jasm.acceptor.PowerAcceptorBlockEntity;
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
import dev.micolash.jasm.bay.BayBlock;
import dev.micolash.jasm.bay.BayKind;
import dev.micolash.jasm.bay.DemolitionBayBlockEntity;
import dev.micolash.jasm.bay.DeploymentBayBlockEntity;
import dev.micolash.jasm.brain.NetworkBrainBlock;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.brain.NetworkChamberBlock;
import dev.micolash.jasm.brain.NetworkChamberBlockEntity;
import dev.micolash.jasm.crystal.CrystalFoundryBlock;
import dev.micolash.jasm.crystal.CrystalFoundryBlockEntity;
import dev.micolash.jasm.crystal.ResonatorBlock;
import dev.micolash.jasm.crystal.ResonatorBlockEntity;
import dev.micolash.jasm.crystal.SeededAmethystBlock;
import dev.micolash.jasm.generator.CombustionGeneratorBlock;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import dev.micolash.jasm.generator.GeneratorTier;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.station.BitlingStationBlock;
import dev.micolash.jasm.station.BitlingStationBlockEntity;
import dev.micolash.jasm.workshop.ChipWorkshopBlock;
import dev.micolash.jasm.workshop.ChipWorkshopBlockEntity;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Jasm.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Jasm.MODID);

    public static final DeferredBlock<CreativeBatteryBlock> CREATIVE_BATTERY = BLOCKS.registerBlock("creative_battery",
            CreativeBatteryBlock::new, p -> p.mapColor(MapColor.COLOR_MAGENTA).strength(1.5F).sound(SoundType.METAL));

    public static final DeferredBlock<PowerAcceptorBlock> POWER_ACCEPTOR = BLOCKS.registerBlock("power_acceptor",
            PowerAcceptorBlock::new, p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.METAL));

    public static final Supplier<BlockEntityType<PowerAcceptorBlockEntity>> POWER_ACCEPTOR_ENTITY = BLOCK_ENTITIES.register(
            "power_acceptor", () -> new BlockEntityType<>(PowerAcceptorBlockEntity::new, POWER_ACCEPTOR.get()));

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
                    p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE).strength(3.0F, 3_600_000.0F).pushReaction(PushReaction.BLOCK).sound(SoundType.METAL)));
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

    // Seeded Amethyst wears Seeded -> Worn -> Cracked -> Block of Amethyst, so the chain is registered back to front.
    public static final DeferredBlock<SeededAmethystBlock> CRACKED_SEEDED_AMETHYST = BLOCKS.registerBlock("cracked_seeded_amethyst",
            p -> new SeededAmethystBlock(p, () -> Blocks.AMETHYST_BLOCK), JasmBlocks::seededProperties);
    public static final DeferredBlock<SeededAmethystBlock> WORN_SEEDED_AMETHYST = BLOCKS.registerBlock("worn_seeded_amethyst",
            p -> new SeededAmethystBlock(p, CRACKED_SEEDED_AMETHYST), JasmBlocks::seededProperties);
    public static final DeferredBlock<SeededAmethystBlock> SEEDED_AMETHYST = BLOCKS.registerBlock("seeded_amethyst",
            p -> new SeededAmethystBlock(p, WORN_SEEDED_AMETHYST), JasmBlocks::seededProperties);

    public static final DeferredBlock<AmethystClusterBlock> DATA_CRYSTAL_CLUSTER = BLOCKS.registerBlock("data_crystal_cluster",
            p -> new AmethystClusterBlock(7.0F, 10.0F, p), p -> budProperties(p, SoundType.AMETHYST_CLUSTER, 5));
    public static final DeferredBlock<AmethystClusterBlock> LARGE_DATA_CRYSTAL_BUD = BLOCKS.registerBlock("large_data_crystal_bud",
            p -> new AmethystClusterBlock(5.0F, 10.0F, p), p -> budProperties(p, SoundType.MEDIUM_AMETHYST_BUD, 4));
    public static final DeferredBlock<AmethystClusterBlock> MEDIUM_DATA_CRYSTAL_BUD = BLOCKS.registerBlock("medium_data_crystal_bud",
            p -> new AmethystClusterBlock(4.0F, 10.0F, p), p -> budProperties(p, SoundType.LARGE_AMETHYST_BUD, 2));
    public static final DeferredBlock<AmethystClusterBlock> SMALL_DATA_CRYSTAL_BUD = BLOCKS.registerBlock("small_data_crystal_bud",
            p -> new AmethystClusterBlock(3.0F, 8.0F, p), p -> budProperties(p, SoundType.SMALL_AMETHYST_BUD, 1));

    public static final DeferredBlock<ResonatorBlock> CRYSTAL_RESONATOR = BLOCKS.registerBlock("crystal_resonator",
            ResonatorBlock::new, p -> p.mapColor(MapColor.COLOR_PURPLE).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.METAL));

    public static final Supplier<BlockEntityType<ResonatorBlockEntity>> CRYSTAL_RESONATOR_ENTITY = BLOCK_ENTITIES.register(
            "crystal_resonator", () -> new BlockEntityType<>(ResonatorBlockEntity::new, CRYSTAL_RESONATOR.get()));

    public static final DeferredBlock<CrystalFoundryBlock> CRYSTAL_FOUNDRY = BLOCKS.registerBlock("crystal_foundry",
            CrystalFoundryBlock::new, p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.METAL));

    public static final Supplier<BlockEntityType<CrystalFoundryBlockEntity>> CRYSTAL_FOUNDRY_ENTITY = BLOCK_ENTITIES.register(
            "crystal_foundry", () -> new BlockEntityType<>(CrystalFoundryBlockEntity::new, CRYSTAL_FOUNDRY.get()));

    public static final DeferredBlock<ChipWorkshopBlock> CHIP_WORKSHOP = BLOCKS.registerBlock("chip_workshop",
            ChipWorkshopBlock::new, p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.METAL)
                    // The lamp at the back: bright while the Bitling works, dim while it naps.
                    .lightLevel(state -> switch (state.getValue(ChipWorkshopBlock.STATUS)) {
                        case WORKING -> 12;
                        case NAPPING -> 5;
                        case IDLE -> 0;
                    }));

    public static final Supplier<BlockEntityType<ChipWorkshopBlockEntity>> CHIP_WORKSHOP_ENTITY = BLOCK_ENTITIES.register(
            "chip_workshop", () -> new BlockEntityType<>(ChipWorkshopBlockEntity::new, CHIP_WORKSHOP.get()));

    public static final DeferredBlock<BayBlock> DEPLOYMENT_BAY = BLOCKS.registerBlock("deployment_bay",
            BayBlock.of(BayKind.DEPLOYMENT), p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F)
                    .sound(SoundType.METAL).noOcclusion());

    public static final Supplier<BlockEntityType<DeploymentBayBlockEntity>> DEPLOYMENT_BAY_ENTITY = BLOCK_ENTITIES.register(
            "deployment_bay", () -> new BlockEntityType<>(DeploymentBayBlockEntity::new, DEPLOYMENT_BAY.get()));

    public static final DeferredBlock<BayBlock> DEMOLITION_BAY = BLOCKS.registerBlock("demolition_bay",
            BayBlock.of(BayKind.DEMOLITION), p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F)
                    .sound(SoundType.METAL).noOcclusion());

    public static final Supplier<BlockEntityType<DemolitionBayBlockEntity>> DEMOLITION_BAY_ENTITY = BLOCK_ENTITIES.register(
            "demolition_bay", () -> new BlockEntityType<>(DemolitionBayBlockEntity::new, DEMOLITION_BAY.get()));

    public static final DeferredBlock<BitlingStationBlock> BITLING_STATION = BLOCKS.registerBlock("bitling_station",
            BitlingStationBlock::new,
            p -> p.mapColor(MapColor.COLOR_GRAY).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.METAL).noOcclusion());

    public static final Supplier<BlockEntityType<BitlingStationBlockEntity>> BITLING_STATION_ENTITY = BLOCK_ENTITIES.register(
            "bitling_station", () -> new BlockEntityType<>(BitlingStationBlockEntity::new, BITLING_STATION.get()));

    public static final DeferredBlock<NetworkBrainBlock> NETWORK_BRAIN = BLOCKS.registerBlock("network_brain",
            NetworkBrainBlock::new,
            p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.GLASS).noOcclusion()
                    .lightLevel(state -> 15));

    public static final Supplier<BlockEntityType<NetworkBrainBlockEntity>> NETWORK_BRAIN_ENTITY = BLOCK_ENTITIES.register(
            "network_brain", () -> new BlockEntityType<>(NetworkBrainBlockEntity::new, NETWORK_BRAIN.get()));

    public static final DeferredBlock<NetworkChamberBlock> NETWORK_CHAMBER = BLOCKS.registerBlock("network_chamber",
            NetworkChamberBlock::new,
            p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE).requiresCorrectToolForDrops().strength(3.0F).sound(SoundType.GLASS).noOcclusion());

    public static final Supplier<BlockEntityType<NetworkChamberBlockEntity>> NETWORK_CHAMBER_ENTITY = BLOCK_ENTITIES.register(
            "network_chamber", () -> new BlockEntityType<>(NetworkChamberBlockEntity::new, NETWORK_CHAMBER.get()));

    private static BlockBehaviour.Properties seededProperties(BlockBehaviour.Properties p) {
        return p.mapColor(MapColor.COLOR_PURPLE).randomTicks().strength(1.5F).sound(SoundType.AMETHYST).requiresCorrectToolForDrops()
                .pushReaction(PushReaction.DESTROY);
    }

    // Same as vanilla's buds, which swap the medium and large sounds too.
    private static BlockBehaviour.Properties budProperties(BlockBehaviour.Properties p, SoundType sound, int light) {
        return p.mapColor(MapColor.COLOR_PURPLE).forceSolidOn().noOcclusion().sound(sound).strength(1.5F).lightLevel(state -> light)
                .pushReaction(PushReaction.DESTROY);
    }

    public static final Supplier<BlockEntityType<DataCableBlockEntity>> DATA_CABLE_ENTITY = BLOCK_ENTITIES.register(
            "data_cable", () -> new BlockEntityType<>(DataCableBlockEntity::new, cables().stream().map(DeferredBlock::get).toArray(Block[]::new)));

    public static final DeferredBlock<DataCableBlock> DATA_CABLE = BLOCKS.registerBlock("data_cable", DataCableBlock::new, JasmBlocks::cableProperties);

    static {
        // Dyed cables were removed: those in saved worlds become plain Data Cables.
        for (DyeColor color : DyeColor.values()) {
            BLOCKS.addAlias(Jasm.id(color.getSerializedName() + "_data_cable"), Jasm.id("data_cable"));
        }
    }

    private static BlockBehaviour.Properties cableProperties(BlockBehaviour.Properties p) {
        // never a full block. without this the game built the whole cable shape just to ask
        return p.mapColor(MapColor.COLOR_GRAY).strength(0.5F).sound(SoundType.METAL).noOcclusion().dynamicShape()
                .isRedstoneConductor((state, level, pos) -> false);
    }

    public static List<DeferredBlock<DataCableBlock>> cables() {
        return List.of(DATA_CABLE);
    }

    public static DeferredBlock<CombustionGeneratorBlock> generator(GeneratorTier tier) {
        return GENERATORS.get(tier);
    }

    public static DeferredBlock<ArchiveBlock> archive(ArchiveTier tier) {
        return ARCHIVES.get(tier);
    }

    private JasmBlocks() {}
}
