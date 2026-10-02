package dev.micolash.jasm.bitling;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmEntities;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

/** Gives the wild Bitling its attributes, and says where it may spawn. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class WildBitlingEvents {
    private WildBitlingEvents() {}

    @SubscribeEvent
    static void attributes(EntityAttributeCreationEvent event) {
        event.put(JasmEntities.WILD_BITLING.get(), WildBitling.createAttributes().build());
    }

    @SubscribeEvent
    static void placements(RegisterSpawnPlacementsEvent event) {
        event.register(JasmEntities.WILD_BITLING.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                WildBitling::checkSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }
}
