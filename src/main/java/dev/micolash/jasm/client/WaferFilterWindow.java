package dev.micolash.jasm.client;

import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.storage.WaferSettings;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** A draggable instance of the shared item filter editor for the selected wafer. */
final class WaferFilterWindow extends ItemFilterEditor {
    private int index = -1;
    private final DeckMenu menu;
    WaferFilterWindow(DeckMenu menu, Font font) {
        super(font, 4, true, menu::getCarried);
        this.menu = menu;
        setSave(settings -> ClientPacketDistributor.sendToServer(new DeckPayloads.Configure(menu.containerId, index, settings)));
    }
    int selected() { return index; }
    void open(int slot, int left, int top, int width, int height) {
        index = slot;
        var settings = slot < menu.view().slots().size() ? menu.view().slots().get(slot).settings() : WaferSettings.DEFAULT;
        boolean fluid = slot < menu.view().slots().size() && menu.view().slots().get(slot).fluid();
        setModes(fluid ? List.of(WaferSettings.Mode.FLUID, WaferSettings.Mode.TAG, WaferSettings.Mode.MOD_ID)
                : List.of(WaferSettings.Mode.ITEM, WaferSettings.Mode.TAG, WaferSettings.Mode.MOD_ID));
        super.open(settings, Component.translatable("screen.jasm.filter.title"), Component.translatable("screen.jasm.deck.settings.slot", slot + 1),
                menu.slots.get(slot).getItem(), left, top, width, height);
    }
    @Override
    void close() { index = -1; super.close(); }
}
