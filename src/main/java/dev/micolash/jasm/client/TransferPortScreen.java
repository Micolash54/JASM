package dev.micolash.jasm.client;

import dev.micolash.jasm.network.DeckLinkLayout;
import dev.micolash.jasm.transfer.TransferNetwork;
import dev.micolash.jasm.transfer.TransferPortKind;
import dev.micolash.jasm.transfer.TransferPortMenu;
import dev.micolash.jasm.transfer.PortOperations;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Independent input/output filter editors beside the shared Deck linking panel. */
public final class TransferPortScreen extends AbstractContainerScreen<TransferPortMenu> {
    private final List<ItemFilterEditor> editors = new ArrayList<>();
    private Button reset;
    public TransferPortScreen(TransferPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TransferPortMenu.WIDTH, menu.height());
        inventoryLabelX = (imageWidth - 162) / 2;
        inventoryLabelY = menu.inventoryY() - 11;
    }
    @Override protected void init() {
        super.init();
        int panelX = DeckLinkLayout.PORT.panelX();
        leftPos = (width - imageWidth + panelX) / 2 - panelX;
        editors.clear();
        boolean combined = menu.kind() == TransferPortKind.INPUT_OUTPUT;
        int rows = combined ? 1 : 2;
        if (menu.kind().imports()) createEditor(false, rows, 24);
        if (menu.kind().exports()) createEditor(true, rows, combined ? 144 : 24);
        reset = addRenderableWidget(JasmButton.wrappedText(Component.translatable("screen.jasm.port.reset_deck"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0), leftPos + DeckLinkLayout.PORT.panelX() + 8,
                topPos + DeckLinkLayout.RESET_Y, DeckLinkLayout.PORT.width() - 16, 22));
    }
    private void createEditor(boolean output, int rows, int top) {
        var editor = new ItemFilterEditor(font, rows, false, menu::getCarried, imageWidth - 16);
        editor.setSave(settings -> {
            menu.configure(output, settings);
            ClientPacketDistributor.sendToServer(new TransferNetwork.Configure(menu.containerId, output, settings));
        });
        editor.open(output ? menu.filters().output() : menu.filters().input(),
                Component.translatable(output ? "screen.jasm.transfer.output_filters" : "screen.jasm.transfer.input_filters"), Component.empty(),
                ItemStack.EMPTY, leftPos + 8, topPos + top, width, height);
        editors.add(editor);
    }
    public List<Rect2i> extraAreas() { return List.of(new Rect2i(leftPos + DeckLinkLayout.PORT.panelX(), topPos, DeckLinkLayout.PORT.width(), DeckLinkLayout.PORT.height()), new Rect2i(leftPos + PortUpgradeLayout.PANEL_X, topPos + PortUpgradeLayout.PANEL_Y, PortUpgradeLayout.PANEL_WIDTH, PortUpgradeLayout.SPEED_PANEL_HEIGHT)); }
    @Override protected boolean hasClickedOutside(double x, double y, int left, int top) {
        boolean overPanel = extraAreas().stream().anyMatch(area -> x >= area.getX() && x < area.getX() + area.getWidth() && y >= area.getY() && y < area.getY() + area.getHeight());
        return !overPanel && super.hasClickedOutside(x, y, left, top);
    }
    public List<Rect2i> filterSlots() { return editors.stream().map(ItemFilterEditor::slotArea).toList(); }
    public void setFilterItem(int index, Item item) { if (index >= 0 && index < editors.size()) editors.get(index).setItem(new ItemStack(item)); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mx, int my, float a) {
        super.extractBackground(graphics, mx, my, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        DeckLinkPanel.draw(graphics, font, leftPos, topPos, DeckLinkLayout.PORT);
        if (menu.kind() == TransferPortKind.INPUT_OUTPUT) {
            graphics.fill(leftPos + 8, topPos + 140, leftPos + imageWidth - 8, topPos + 141, JasmGui.SELECTED);
        }
        JasmGui.panel(graphics, leftPos + PortUpgradeLayout.PANEL_X, topPos + PortUpgradeLayout.PANEL_Y, PortUpgradeLayout.PANEL_WIDTH, PortUpgradeLayout.SPEED_PANEL_HEIGHT);
        for (var slot : menu.slots) JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
    }
    @Override protected void extractLabels(GuiGraphicsExtractor graphics, int mx, int my) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        DeckLinkPanel.message(graphics, font, Component.translatable(menu.linked() ? "screen.jasm.terminal.linked" : menu.defaultDeck() ? "screen.jasm.port.owner_deck" : "screen.jasm.port.override_deck"), menu.linked() ? JasmGui.GOOD : JasmGui.SUBTEXT, DeckLinkLayout.PORT);
    }
    @Override public void extractContents(GuiGraphicsExtractor graphics, int mx, int my, float a) {
        super.extractContents(graphics, mx, my, a);
        reset.active = menu.canReset();
        graphics.nextStratum();
        for (var editor : editors) editor.draw(graphics, mx, my, a, width, height);
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            if (hoveredSlot == menu.getSlot(i)) graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.transfer.speed_hint"), mx, my);
        }
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (var editor : editors) {
            if (editor.contains(event.x(), event.y())) {
                for (var other : editors) if (other != editor) other.unfocus();
                return editor.mouseClicked(event, doubleClick);
            }
        }
        editors.forEach(ItemFilterEditor::unfocus);
        return super.mouseClicked(event, doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        for (var editor : editors) if (editor.mouseDragged(event, width, height)) return true;
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = false;
        for (var editor : editors) handled |= editor.mouseReleased(event);
        return handled || super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        for (var editor : editors) if (editor.contains(x, y)) return editor.mouseScrolled(sy);
        return super.mouseScrolled(x, y, sx, sy);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        for (var editor : editors) if (editor.keyPressed(event)) return true;
        return super.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) {
        for (var editor : editors) if (editor.charTyped(event)) return true;
        return super.charTyped(event);
    }
}
