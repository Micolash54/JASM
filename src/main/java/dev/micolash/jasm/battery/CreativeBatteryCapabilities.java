package dev.micolash.jasm.battery;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.energy.InfiniteEnergyHandler;

/**
 * Blocks that pull power can take as much as they like from any side; nothing can be pushed into the battery.
 * Hoppers and pipes reach the charging slot from any side.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class CreativeBatteryCapabilities {
    private CreativeBatteryCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.CREATIVE_BATTERY_ENTITY.get(), (battery, side) -> InfiniteEnergyHandler.INSTANCE);
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CREATIVE_BATTERY_ENTITY.get(), (battery, side) -> battery.automation());
    }
}
