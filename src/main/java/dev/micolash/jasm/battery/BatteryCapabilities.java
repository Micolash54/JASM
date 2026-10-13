package dev.micolash.jasm.battery;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Hoppers and pipes reach a Battery block's charging slot from any side. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class BatteryCapabilities {
    private BatteryCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.BATTERY_ENTITY.get(), (battery, side) -> battery.automation());
    }
}
