package dev.micolash.jasm.client;

import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.storage.WaferSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** A draggable instance of the shared item filter editor for the selected wafer. */
final class WaferFilterWindow extends ItemFilterEditor {
    private int index = -1;
    private final DeckMenu menu;
    private final Button empty;
    private final Font font;
    WaferFilterWindow(DeckMenu menu, Font font) {
        super(font, 4, true, menu::getCarried);
        this.menu = menu;
        this.font = font;
        setSave(settings -> ClientPacketDistributor.sendToServer(new DeckPayloads.Configure(menu.containerId, index, settings)));
        // In the title row, left of the slot number.
        Component text = Component.translatable("screen.jasm.filter.empty");
        int width = font.width(text) + 10;
        empty = place(JasmButton.text(text, b -> ClientPacketDistributor.sendToServer(new DeckPayloads.EmptyWafer(menu.containerId, index)),
                0, 0, width, 13), WIDTH - 71 - width, 4);
        empty.setTooltip(Tooltip.create(Component.translatable("screen.jasm.filter.empty_tip")));
    }
    int selected() { return index; }
    void open(int slot, int left, int top, int width, int height) {
        index = slot;
        menu.view().setEmptied(null);
        var settings = slot < menu.view().slots().size() ? menu.view().slots().get(slot).settings() : WaferSettings.DEFAULT;
        super.open(settings, Component.translatable("screen.jasm.filter.title"), Component.translatable("screen.jasm.deck.settings.slot", slot + 1),
                menu.slots.get(slot).getItem(), left, top, width, height);
    }
    @Override
    void close() { index = -1; menu.view().setEmptied(null); super.close(); }

    /** Only when this wafer holds something and another wafer of the same kind sits in the Deck. */
    private boolean canEmpty() {
        var slots = menu.view().slots();
        if (index < 0 || index >= slots.size() || slots.get(index).used() <= 0) {
            return false;
        }
        boolean fluid = slots.get(index).fluid();
        for (int i = 0; i < slots.size(); i++) {
            if (i != index && slots.get(i).present() && slots.get(i).fluid() == fluid) {
                return true;
            }
        }
        return false;
    }

    @Override
    void draw(GuiGraphicsExtractor graphics, int mx, int my, float a, int width, int screenHeight) {
        empty.active = canEmpty();
        super.draw(graphics, mx, my, a, width, screenHeight);
    }

    /** While the bar runs, the window is dimmed and takes no clicks. */
    @Override
    boolean locked() { return menu.view().emptying(index) != null; }

    @Override
    void drawOverlay(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        var progress = menu.view().emptying(index);
        if (progress == null) return;
        graphics.nextStratum();
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, JasmGui.SHADE);
        Component title = Component.translatable("screen.jasm.filter.emptying");
        Component count = progress.fluid()
                ? Component.translatable("screen.jasm.filter.progress.buckets", GridEntries.abbreviateBuckets(progress.moved()), GridEntries.abbreviateBuckets(progress.total()))
                : Component.translatable("screen.jasm.filter.progress.items", String.format("%,d", progress.moved()), String.format("%,d", progress.total()));
        int barWidth = width * 3 / 5;
        int barY = y + height / 2;
        graphics.text(font, title, x + (width - font.width(title)) / 2, barY - 13, JasmGui.TEXT, false);
        double fraction = progress.total() <= 0 ? 1 : progress.moved() / (double) progress.total();
        JasmGui.labelledBar(graphics, font, count, x + (width - barWidth) / 2, barY, barWidth, 13, fraction);
    }

    /** The last emptying result takes the place of the "Filters" line until the window closes. */
    @Override
    Component rulesLine() {
        var done = menu.view().emptied();
        if (done == null || done.slot() != index) {
            return super.rulesLine();
        }
        var r = done.result();
        Component moved = amount(r.moved(), r.fluid());
        Component left = amount(r.left(), r.fluid());
        return switch (r.outcome()) {
            case DONE -> Component.translatable("screen.jasm.filter.emptied.done", moved);
            case NO_ROOM -> Component.translatable("screen.jasm.filter.emptied.no_room", moved, left);
            case NO_CHARGE -> Component.translatable("screen.jasm.filter.emptied.no_charge", moved, left);
            case NOTHING -> Component.translatable("screen.jasm.filter.emptied.nothing");
        };
    }

    private static Component amount(long value, boolean fluid) {
        return fluid ? Component.translatable("screen.jasm.filter.emptied.buckets", GridEntries.abbreviateBuckets(value))
                : Component.translatable("screen.jasm.filter.emptied.items", GridEntries.abbreviate(value));
    }
}
