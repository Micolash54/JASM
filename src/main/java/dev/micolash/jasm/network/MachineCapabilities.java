package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Access, Input and Input Output Ports and Network Brains also take items, and so do the chambers of a brain's cube, for
 * their brain. Those ports take fluid too. None of these blocks offers a power input: network power comes from JASM power
 * sources only.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class MachineCapabilities {
    private MachineCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CHIP_WORKSHOP_ENTITY.get(), (workshop, side) -> workshop.itemsThrough(side));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CRYSTAL_FOUNDRY_ENTITY.get(), (foundry, side) -> foundry.itemsThrough(side));
        // Full Access, Input and Input Output Ports take pushed items and fluid on every side, and never give anything
        // out; the other full ports take none. Thin ones take them through their mounted face.
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.ACCESS_PORT_ENTITY.get(), AccessPortBlockEntity::itemInput);
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.FULL_PORT_ENTITY.get(), AccessPortBlockEntity::itemInput);
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.DATA_CABLE_ENTITY.get(), DataCableBlockEntity::itemInput);
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, JasmBlocks.ACCESS_PORT_ENTITY.get(), AccessPortBlockEntity::fluidInput);
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, JasmBlocks.FULL_PORT_ENTITY.get(), AccessPortBlockEntity::fluidInput);
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, JasmBlocks.DATA_CABLE_ENTITY.get(), DataCableBlockEntity::fluidInput);
    }
}
