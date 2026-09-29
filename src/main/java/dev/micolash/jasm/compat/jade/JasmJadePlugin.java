package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.workshop.ChipWorkshopBlock;
import dev.micolash.jasm.workshop.ChipWorkshopBlockEntity;
import dev.micolash.jasm.archive.ArchiveBlock;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.autocraft.RecipeRackBlockEntity;
import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.generator.CombustionGeneratorBlock;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import dev.micolash.jasm.network.MachineBlock;
import dev.micolash.jasm.network.MachineBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.JadeIds;
import snownee.jade.api.WailaPlugin;

/**
 * Jade support: who owns an Archive and how many wafers it protects, what a generator is doing, the endless battery,
 * and the crafting-network blocks (owner, power, cards, the running job).
 */
@WailaPlugin
public class JasmJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(ArchiveInfo.INSTANCE, ArchiveBlockEntity.class);
        registration.registerBlockDataProvider(GeneratorInfo.INSTANCE, CombustionGeneratorBlockEntity.class);
        registration.registerEnergyStorage(InfiniteEnergy.INSTANCE, CreativeBatteryBlockEntity.class);
        registration.registerBlockDataProvider(MachineInfo.INSTANCE, MachineBlockEntity.class);
        registration.registerBlockDataProvider(WorkshopInfo.INSTANCE, ChipWorkshopBlockEntity.class);
        registration.registerBlockDataProvider(FoundryInfo.INSTANCE, dev.micolash.jasm.crystal.CrystalFoundryBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ArchiveInfo.Client.INSTANCE, ArchiveBlock.class);
        registration.registerBlockComponent(GeneratorInfo.Client.INSTANCE, CombustionGeneratorBlock.class);
        registration.registerEnergyStorageClient(InfiniteEnergy.INSTANCE);
        registration.registerBlockComponent(MachineInfo.Client.INSTANCE, MachineBlock.class);
        registration.registerBlockComponent(WorkshopInfo.Client.INSTANCE, ChipWorkshopBlock.class);
        registration.registerBlockComponent(FoundryInfo.Client.INSTANCE, dev.micolash.jasm.crystal.CrystalFoundryBlock.class);
        registration.registerBlockComponent(MachineInfo.Client.INSTANCE, dev.micolash.jasm.autocraft.AccessPortBlock.class);
        // A Recipe Rack says how many cards it holds; the list of every card would only crowd the box.
        registration.addTooltipCollectedCallback((box, accessor) -> {
            if (accessor instanceof BlockAccessor block && block.getBlockEntity() instanceof RecipeRackBlockEntity) {
                box.getTooltip().remove(JadeIds.UNIVERSAL_ITEM_STORAGE);
            }
        });
    }
}
