package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.archive.ArchiveBlock;
import dev.micolash.jasm.archive.ArchiveBlockEntity;
import dev.micolash.jasm.battery.CreativeBatteryBlockEntity;
import dev.micolash.jasm.generator.CombustionGeneratorBlock;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade support: who owns an Archive and how many wafers it protects, what a generator is doing, and the endless battery. */
@WailaPlugin
public class JasmJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(ArchiveInfo.INSTANCE, ArchiveBlockEntity.class);
        registration.registerBlockDataProvider(GeneratorInfo.INSTANCE, CombustionGeneratorBlockEntity.class);
        registration.registerEnergyStorage(InfiniteEnergy.INSTANCE, CreativeBatteryBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ArchiveInfo.Client.INSTANCE, ArchiveBlock.class);
        registration.registerBlockComponent(GeneratorInfo.Client.INSTANCE, CombustionGeneratorBlock.class);
        registration.registerEnergyStorageClient(InfiniteEnergy.INSTANCE);
    }
}
