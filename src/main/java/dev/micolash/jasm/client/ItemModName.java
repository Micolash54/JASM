package dev.micolash.jasm.client;

import dev.micolash.jasm.core.ModNameLine;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Names the mod an item comes from in the Deck's item tooltips. */
final class ItemModName {
    private ItemModName() {}

    /** Adds the mod name line at {@code index}, unless a line there already says it. */
    static void add(List<Component> lines, int index, ItemResource item) {
        String namespace = ModNameLine.namespaceOf(item.typeHolder().getRegisteredName());
        String modName = ModList.get().getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace);
        ModNameLine.add(lines, index, modName, namespace);
    }
}
