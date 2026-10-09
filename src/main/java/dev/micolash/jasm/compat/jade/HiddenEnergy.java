package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import java.util.List;
import net.minecraft.resources.Identifier;
import snownee.jade.api.Accessor;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;

/** A Power Acceptor, full or thin, holds nothing and passes power straight on, so its energy bar is left out. */
public enum HiddenEnergy implements IServerExtensionProvider<EnergyView.Data>, IClientExtensionProvider<EnergyView.Data, EnergyView> {
    INSTANCE;

    @Override
    public List<ViewGroup<EnergyView.Data>> getGroups(Accessor<?> accessor) {
        return List.of();
    }

    @Override
    public List<ClientViewGroup<EnergyView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<EnergyView.Data>> groups) {
        return List.of();
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("power_acceptor");
    }
}
