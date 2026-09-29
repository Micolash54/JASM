package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.station.StationBitling;
import java.util.function.Supplier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmEntities {
    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(Jasm.MODID);

    /** The little Bitling that roams around its Bitling Station. */
    public static final Supplier<EntityType<StationBitling>> STATION_BITLING = ENTITIES.registerEntityType("station_bitling",
            StationBitling::new, MobCategory.MISC, builder -> builder.sized(0.4F, 0.65F).clientTrackingRange(8));

    private JasmEntities() {}
}
