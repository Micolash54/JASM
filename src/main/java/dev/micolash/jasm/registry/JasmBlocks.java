package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.battery.CreativeBatteryBlock;
import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Jasm.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Jasm.MODID);

    public static final DeferredBlock<CreativeBatteryBlock> CREATIVE_BATTERY = BLOCKS.registerBlock("creative_battery",
            CreativeBatteryBlock::new, p -> p.mapColor(MapColor.COLOR_MAGENTA).strength(1.5F).sound(SoundType.METAL));

    public static final Supplier<BlockEntityType<CreativeBatteryBlockEntity>> CREATIVE_BATTERY_ENTITY = BLOCK_ENTITIES.register(
            "creative_battery", () -> new BlockEntityType<>(CreativeBatteryBlockEntity::new, CREATIVE_BATTERY.get()));

    private JasmBlocks() {}
}
