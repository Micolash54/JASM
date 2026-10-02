package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.transfer.energy.LimitingEnergyHandler;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.minecraft.core.Direction;

/**
 * Crafting-network blocks and cables take FE on every side; nothing can pull it back out. Access Ports and Network Brains
 * also take items, and so do the chambers of a brain's cube, for their brain.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class MachineCapabilities {
    private MachineCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.ENCODING_TERMINAL_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.RECIPE_RACK_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.CRAFTING_SERVER_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.ACCESS_PORT_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.CRYSTAL_RESONATOR_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.CHIP_WORKSHOP_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CHIP_WORKSHOP_ENTITY.get(), (workshop, side) -> workshop.automation());
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.BITLING_STATION_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.CRYSTAL_FOUNDRY_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.NETWORK_BRAIN_ENTITY.get(),
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
        // A formed chamber takes power and chips for its brain; a loose one takes nothing.
        event.registerBlockEntity(Capabilities.Energy.BLOCK, JasmBlocks.NETWORK_CHAMBER_ENTITY.get(),
                (chamber, side) -> new LimitingEnergyHandler(chamber.energy(), Integer.MAX_VALUE, 0));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.NETWORK_CHAMBER_ENTITY.get(),
                (chamber, side) -> chamber.brain() == null ? null : new WorldlyContainerWrapper(chamber.brain(), side == null ? Direction.UP : side));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CRYSTAL_FOUNDRY_ENTITY.get(), (foundry, side) -> foundry.automation());
        // Hoppers and pipes feed chips into a brain from any side; nothing comes back out.
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.NETWORK_BRAIN_ENTITY.get(),
                (brain, side) -> new WorldlyContainerWrapper(brain, side == null ? Direction.UP : side));
        // Full ports accept buffered items on every side, and never give anything out.
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.ACCESS_PORT_ENTITY.get(),
                (port, side) -> new WorldlyContainerWrapper(port, side == null ? Direction.UP : side));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.DATA_CABLE_ENTITY.get(), DataCableBlockEntity::itemInput);
        // A cable takes FE into its network. It looks its network up on every push, so it never holds on to one that
        // was thrown away.
        event.registerBlock(Capabilities.Energy.BLOCK, (level, pos, state, entity, side) ->
                        level instanceof ServerLevel serverLevel ? new CableInput(serverLevel, pos.immutable()) : null,
                JasmBlocks.cables().stream().map(DeferredBlock::get).toArray(Block[]::new));
    }
}
