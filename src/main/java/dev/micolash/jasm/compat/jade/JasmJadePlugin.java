package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.archive.ArchiveBlock;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.autocraft.AccessPortBlock;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.autocraft.RecipeRackBlockEntity;
import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.brain.NetworkBrainBlock;
import dev.micolash.jasm.brain.NetworkBrainBlockEntity;
import dev.micolash.jasm.brain.NetworkChamberBlock;
import dev.micolash.jasm.brain.NetworkChamberBlockEntity;
import dev.micolash.jasm.crystal.CrystalFoundryBlock;
import dev.micolash.jasm.crystal.CrystalFoundryBlockEntity;
import dev.micolash.jasm.generator.CombustionGeneratorBlock;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.bay.BayBlock;
import dev.micolash.jasm.bay.BayBlockEntity;
import dev.micolash.jasm.workshop.ChipWorkshopBlock;
import dev.micolash.jasm.workshop.ChipWorkshopBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.JadeIds;
import snownee.jade.api.WailaPlugin;

/**
 * Jade support: who owns an Archive and how many wafers it protects, what a generator is doing, the endless battery,
 * the crafting-network blocks (owner, power, cards, the running job), and the Network Brain.
 */
@WailaPlugin
public class JasmJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(ArchiveInfo.INSTANCE, ArchiveBlockEntity.class);
        registration.registerBlockDataProvider(GeneratorInfo.INSTANCE, CombustionGeneratorBlockEntity.class);
        registration.registerEnergyStorage(InfiniteEnergy.INSTANCE, CreativeBatteryBlockEntity.class);
        registration.registerEnergyStorage(CableEnergy.INSTANCE, DataCableBlockEntity.class);
        registration.registerBlockDataProvider(MachineInfo.INSTANCE, MachineBlockEntity.class);
        registration.registerBlockDataProvider(MachineInfo.INSTANCE, DataCableBlockEntity.class);
        registration.registerBlockDataProvider(WorkshopInfo.INSTANCE, ChipWorkshopBlockEntity.class);
        registration.registerBlockDataProvider(BayInfo.INSTANCE, BayBlockEntity.class);
        registration.registerBlockDataProvider(FoundryInfo.INSTANCE, CrystalFoundryBlockEntity.class);
        registration.registerBlockDataProvider(BrainInfo.INSTANCE, NetworkBrainBlockEntity.class);
        registration.registerBlockDataProvider(BrainInfo.INSTANCE, NetworkChamberBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ArchiveInfo.Client.INSTANCE, ArchiveBlock.class);
        registration.registerBlockComponent(GeneratorInfo.Client.INSTANCE, CombustionGeneratorBlock.class);
        registration.registerEnergyStorageClient(InfiniteEnergy.INSTANCE);
        registration.registerEnergyStorageClient(CableEnergy.INSTANCE);
        registration.registerBlockComponent(MachineInfo.Client.INSTANCE, MachineBlock.class);
        registration.registerBlockComponent(MachineInfo.Client.INSTANCE, DataCableBlock.class);
        registration.registerBlockComponent(WorkshopInfo.Client.INSTANCE, ChipWorkshopBlock.class);
        registration.registerBlockComponent(MachineInfo.Client.INSTANCE, BayBlock.class);
        registration.registerBlockComponent(BayInfo.Client.INSTANCE, BayBlock.class);
        registration.registerBlockComponent(FoundryInfo.Client.INSTANCE, CrystalFoundryBlock.class);
        registration.registerBlockComponent(MachineInfo.Client.INSTANCE, AccessPortBlock.class);
        registration.registerBlockComponent(BrainInfo.Client.INSTANCE, NetworkBrainBlock.class);
        registration.registerBlockComponent(BrainInfo.Client.INSTANCE, NetworkChamberBlock.class);
        // A Recipe Rack says how many cards it holds; the list of every card would only crowd the box.
        registration.addTooltipCollectedCallback((box, accessor) -> {
            if (accessor instanceof BlockAccessor block && block.getBlock() instanceof DataCableBlock
                    && MachineInfo.machine(block) instanceof AccessPortBlockEntity port) {
                box.getTooltip().replace(JadeIds.CORE_OBJECT_NAME, port.getDisplayName());
            }
            if (accessor instanceof BlockAccessor block && block.getBlockEntity() instanceof RecipeRackBlockEntity) {
                box.getTooltip().remove(JadeIds.UNIVERSAL_ITEM_STORAGE);
            }
        });
    }
}
