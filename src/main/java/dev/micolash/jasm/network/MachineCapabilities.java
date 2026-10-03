package dev.micolash.jasm.network;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.transfer.energy.LimitingEnergyHandler;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;

/**
 * Crafting-network blocks and cables take FE on every side; nothing can pull it back out. Access Ports and Network Brains
 * also take items, and so do the chambers of a brain's cube, for their brain.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class MachineCapabilities {
    private MachineCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        powerIn(event, JasmBlocks.ENCODING_TERMINAL_ENTITY.get(),
                JasmBlocks.RECIPE_RACK_ENTITY.get(),
                JasmBlocks.CRAFTING_SERVER_ENTITY.get(),
                JasmBlocks.ACCESS_PORT_ENTITY.get(),
                JasmBlocks.CRYSTAL_RESONATOR_ENTITY.get(),
                JasmBlocks.CHIP_WORKSHOP_ENTITY.get(),
                JasmBlocks.BITLING_STATION_ENTITY.get(),
                JasmBlocks.CRYSTAL_FOUNDRY_ENTITY.get(),
                JasmBlocks.NETWORK_BRAIN_ENTITY.get(),
                JasmBlocks.NETWORK_CHAMBER_ENTITY.get());
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CHIP_WORKSHOP_ENTITY.get(), (workshop, side) -> workshop.automation());
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.CRYSTAL_FOUNDRY_ENTITY.get(), (foundry, side) -> foundry.automation());
        // Full ports accept buffered items on every side, and never give anything out.
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.ACCESS_PORT_ENTITY.get(),
                (port, side) -> new WorldlyContainerWrapper(port, side == null ? Direction.UP : side));
        event.registerBlockEntity(Capabilities.Item.BLOCK, JasmBlocks.DATA_CABLE_ENTITY.get(), DataCableBlockEntity::itemInput);
        // A cable takes FE into its network. It looks its network up on every push, so it never holds on to one that
        // was thrown away.
        event.registerBlock(Capabilities.Energy.BLOCK,
                (level, pos, state, entity, side) -> level instanceof ServerLevel serverLevel ? new CableInput(serverLevel, pos.immutable()) : null,
                JasmBlocks.cables().stream().map(DeferredBlock::get).toArray(Block[]::new));
    }

    /** These blocks take FE on every side, and nothing can pull it back out. */
    @SafeVarargs
    private static void powerIn(RegisterCapabilitiesEvent event, BlockEntityType<? extends MachineBlockEntity>... types) {
        for (BlockEntityType<? extends MachineBlockEntity> type : types) {
            powerIn(event, type);
        }
    }

    private static <T extends MachineBlockEntity> void powerIn(RegisterCapabilitiesEvent event, BlockEntityType<T> type) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, type,
                (machine, side) -> new LimitingEnergyHandler(machine.energy(), Integer.MAX_VALUE, 0));
    }
}
