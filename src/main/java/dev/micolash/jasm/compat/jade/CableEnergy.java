package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.DataCableBlockEntity;
import java.util.List;
import net.minecraft.resources.Identifier;
import snownee.jade.api.Accessor;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;

/** The power a single Data Cable holds while passing it on. */
public enum CableEnergy implements IServerExtensionProvider<EnergyView.Data>, IClientExtensionProvider<EnergyView.Data, EnergyView> {
    INSTANCE;

    @Override
    public List<ViewGroup<EnergyView.Data>> getGroups(Accessor<?> accessor) {
        if (!(accessor.getTarget() instanceof DataCableBlockEntity cable)) return List.of();
        return List.of(new ViewGroup<>(List.of(new EnergyView.Data(cable.energy().getAmountAsLong(), cable.energy().getCapacityAsLong()))));
    }

    @Override
    public List<ClientViewGroup<EnergyView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<EnergyView.Data>> groups) {
        return ClientViewGroup.map(groups, data -> EnergyView.read(data, "FE"), null);
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("cable_energy");
    }
}
