package dev.micolash.jasm.station;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

/** Gives the station's Bitling its attributes. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class StationEvents {
    private StationEvents() {}

    @SubscribeEvent
    static void attributes(EntityAttributeCreationEvent event) {
        event.put(JasmEntities.STATION_BITLING.get(), StationBitling.createAttributes().build());
    }
}
