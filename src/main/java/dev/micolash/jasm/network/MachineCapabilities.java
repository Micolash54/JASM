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

/** Crafting-network blocks and cables take FE on every side; nothing can pull it back out. */
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
        // A cable takes FE into its network. It looks its network up on every push, so it never holds on to one that
        // was thrown away.
        event.registerBlock(Capabilities.Energy.BLOCK, (level, pos, state, entity, side) ->
                        level instanceof ServerLevel serverLevel ? new CableInput(serverLevel, pos.immutable()) : null,
                JasmBlocks.cables().stream().map(DeferredBlock::get).toArray(Block[]::new));
    }
}
