package dev.micolash.jasm.compat.jade;

import dev.micolash.jasm.Jasm;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.Accessor;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;

/** The Creative Battery never runs out, so its energy bar is shown full and reads "Infinite" instead of a number. */
public enum InfiniteEnergy implements IServerExtensionProvider<EnergyView.Data>, IClientExtensionProvider<EnergyView.Data, EnergyView> {
    INSTANCE;

    @Override
    public List<ViewGroup<EnergyView.Data>> getGroups(Accessor<?> accessor) {
        return List.of(new ViewGroup<>(List.of(new EnergyView.Data(1, 1))));
    }

    @Override
    public List<ClientViewGroup<EnergyView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<EnergyView.Data>> groups) {
        return ClientViewGroup.map(groups, data -> {
            EnergyView view = new EnergyView("", "");
            view.ratio = 1;
            view.overrideText = Component.translatable("jade.jasm.infinite_energy");
            return view;
        }, null);
    }

    @Override
    public Identifier getUid() {
        return Jasm.id("creative_battery");
    }
}
