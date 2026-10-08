package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;

/**
 * Access Ports and Network Brains also take items, and so do the chambers of a brain's cube, for their brain. None of
 * these blocks offers a power input: network power comes from JASM power sources only.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class MachineCapabilities {
    private MachineCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CHIP_WORKSHOP_ENTITY.get(), (workshop, side) -> workshop.itemsThrough(side));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CRYSTAL_FOUNDRY_ENTITY.get(), (foundry, side) -> foundry.itemsThrough(side));
        // Full ports accept buffered items on every side, and never give anything out.
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.ACCESS_PORT_ENTITY.get(),
                (port, side) -> new WorldlyContainerWrapper(port, side == null ? Direction.UP : side));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.DATA_CABLE_ENTITY.get(), DataCableBlockEntity::itemInput);
    }
}
