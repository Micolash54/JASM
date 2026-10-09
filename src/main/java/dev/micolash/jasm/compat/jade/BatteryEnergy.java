package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.battery.BatteryBlockEntity;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import snownee.jade.api.Accessor;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;

/** A Battery shows the power of the whole battery it is part of, whichever of its blocks is looked at. */
public enum BatteryEnergy implements IServerExtensionProvider<EnergyView.Data>, IClientExtensionProvider<EnergyView.Data, EnergyView> {
    INSTANCE;

    @Override
    public List<ViewGroup<EnergyView.Data>> getGroups(Accessor<?> accessor) {
        if (!(accessor.getTarget() instanceof BatteryBlockEntity battery) || battery.isRemoved()
                || !(battery.getLevel() instanceof ServerLevel)) {
            return List.of();
        }
        EnergyHandler all = battery.groupEnergy();
        return List.of(new ViewGroup<>(List.of(new EnergyView.Data(all.getAmountAsLong(), all.getCapacityAsLong()))));
    }

    @Override
    public List<ClientViewGroup<EnergyView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<EnergyView.Data>> groups) {
        return ClientViewGroup.map(groups, data -> EnergyView.read(data, "FE"), null);
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("battery");
    }
}
