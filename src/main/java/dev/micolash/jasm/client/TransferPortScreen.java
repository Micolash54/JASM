package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.LinkWindowCover;
import dev.micolash.jasm.transfer.PortOperations;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import dev.micolash.jasm.transfer.RedstoneMode;
import dev.micolash.jasm.transfer.TransferNetwork;
import dev.micolash.jasm.transfer.TransferPortKind;
import dev.micolash.jasm.transfer.TransferPortMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * Independent input/output filter editors above the inventory. A column on the right holds the Deck Link key above the
 * upgrade slots; the key opens a window with the link slots.
 */
public final class TransferPortScreen extends JasmScreen<TransferPortMenu> {
    private static final int KEY_X = PortUpgradeLayout.KEY_X;
    private static final JasmButton.Icon LINK = new JasmButton.Icon(Jasm.id("icon/deck_link"), 12, 12);
    private final List<ItemFilterEditor> editors = new ArrayList<>();
    private JasmButton link;
    private JasmButton redstone;
    private boolean frameRedstone;
    private @Nullable RedstoneMode shownRedstoneMode;
    private @Nullable DeckLinkWindow linkWindow;
    private JasmFrame frame;
    public TransferPortScreen(TransferPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KEY_X + JasmGui.SIDE_KEY_WIDTH + 3, menu.height());
        inventoryLabelX = (TransferPortMenu.WIDTH - 162) / 2;
        inventoryLabelY = menu.inventoryY() - 11;
    }
    @Override
    protected LinkWindowCover linkCover() {
        return menu.linkCover();
    }

    @Override
    protected void init() {
        super.init();
        addHelp(TransferPortMenu.WIDTH - 7, "items/item-ports.md");
        frameRedstone = menu.hasRedstoneUpgrade();
        frame = JasmFrame.rounded(new int[]{0, 0, TransferPortMenu.WIDTH, imageHeight},
                PortUpgradeLayout.column(TransferPortMenu.SIDE_KEYS, frameRedstone));
        editors.clear();
        boolean combined = menu.kind() == TransferPortKind.INPUT_OUTPUT;
        int rows = combined ? 1 : 2;
        if (menu.kind().exports()) createEditor(true, rows, TransferPortMenu.FILTER_TOP);
        if (menu.kind().imports()) createEditor(false, rows, combined ? TransferPortMenu.secondFilterTop() : TransferPortMenu.FILTER_TOP);
        Component linkLabel = Component.translatable("screen.jasm.deck_link");
        link = addRenderableWidget(JasmButton.icon(() -> LINK, linkLabel, b -> toggleLink(),
                leftPos + KEY_X, topPos + JasmGui.sideKeyY(0), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        link.setTooltip(Tooltip.create(linkLabel));
        if (linkWindow == null) {
            linkWindow = new DeckLinkWindow(font, menu.getSlot(TransferPortMenu.LINK_IN), menu.getSlot(TransferPortMenu.LINK_OUT),
                    menu.linkCover(), Component.translatable("screen.jasm.archive.reset_deck"),
                    () -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0));
        }
        link.setLatched(linkWindow.isOpen());
        redstone = addRenderableWidget(JasmButton.icon(
                () -> new JasmButton.Icon(Jasm.id("icon/redstone_" + menu.redstoneMode().getSerializedName()), 12, 12),
                redstoneLabel(), b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, TransferPortMenu.CYCLE_REDSTONE),
                leftPos + KEY_X, topPos + PortUpgradeLayout.redstoneY(TransferPortMenu.SIDE_KEYS), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        shownRedstoneMode = null;
        updateRedstoneButton();
    }
    private Component redstoneLabel() {
        return Component.translatable("screen.jasm.transfer.redstone",
                Component.translatable("screen.jasm.transfer.redstone_" + menu.redstoneMode().getSerializedName()));
    }
    private void updateRedstoneButton() {
        boolean installed = menu.hasRedstoneUpgrade();
        redstone.visible = installed;
        redstone.active = installed;
        redstone.setLatched(menu.redstoneMode() != RedstoneMode.IGNORE);
        if (shownRedstoneMode != menu.redstoneMode()) {
            shownRedstoneMode = menu.redstoneMode();
            Component label = redstoneLabel();
            redstone.setMessage(label);
            redstone.setTooltip(Tooltip.create(label.copy().append("\n").append(Component.translatable("screen.jasm.transfer.redstone_hint"))));
        }
        if (frameRedstone != installed) {
            frameRedstone = installed;
            frame = JasmFrame.rounded(new int[]{0, 0, TransferPortMenu.WIDTH, imageHeight},
                    PortUpgradeLayout.column(TransferPortMenu.SIDE_KEYS, installed));
        }
    }
    @Override
    protected void containerTick() {
        super.containerTick();
        updateRedstoneButton();
    }
    private void toggleLink() {
        linkWindow.toggle(leftPos + KEY_X, topPos);
        link.setLatched(linkWindow.isOpen());
    }
    private void createEditor(boolean output, int rows, int top) {
        var editor = new ItemFilterEditor(font, rows, false, menu::getCarried, TransferPortMenu.WIDTH - 16);
        editor.setSave(settings -> {
            menu.configure(output, settings);
            ClientPacketDistributor.sendToServer(new TransferNetwork.Configure(menu.containerId, output, settings));
        });
        editor.open(output ? menu.filters().output() : menu.filters().input(),
                Component.translatable(output ? "screen.jasm.transfer.output_filters" : "screen.jasm.transfer.input_filters"), Component.empty(),
                ItemStack.EMPTY, leftPos + 8, topPos + top, width, height);
        editors.add(editor);
    }
    /** Where the Deck Link window is, while it is open, so JEI's item list stays clear of it. */
    public List<Rect2i> extraAreas() {
        return linkWindow == null ? List.of() : linkWindow.area().map(List::of).orElse(List.of());
    }
    /** Clicks on the Deck Link slots belong to the slots, not to a key behind them. */
    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        return linkWindow != null && linkWindow.overSlot(mouseX, mouseY) ? Optional.empty() : super.getChildAt(mouseX, mouseY);
    }

    @Override
    protected boolean hasClickedOutside(double x, double y, int left, int top) {
        return !linkWindow.contains(x, y) && super.hasClickedOutside(x, y, left, top);
    }
    public List<Rect2i> filterSlots() { return editors.stream().map(ItemFilterEditor::slotArea).toList(); }
    public void setFilterMaterial(int index, net.minecraft.resources.Identifier id) { if (index >= 0 && index < editors.size()) editors.get(index).setMaterial(id); }
    public void setFilterItem(int index, Item item) { if (index >= 0 && index < editors.size()) editors.get(index).setItem(new ItemStack(item), false); }
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mx, int my, float a) {
        super.extractBackground(graphics, mx, my, a);
        frame.draw(graphics, leftPos, topPos);
        JasmGui.divider(graphics, leftPos + KEY_X, topPos + PortUpgradeLayout.dividerY(TransferPortMenu.SIDE_KEYS), JasmGui.SIDE_KEY_WIDTH);
        if (menu.kind() == TransferPortKind.INPUT_OUTPUT) {
            JasmGui.divider(graphics, leftPos + 8, topPos + TransferPortMenu.secondFilterTop() - 5, TransferPortMenu.WIDTH - 16);
        }
        // The Deck Link window draws its own two slots.
        for (var slot : menu.slots) {
            if (slot.isActive() && slot.index != TransferPortMenu.LINK_IN && slot.index != TransferPortMenu.LINK_OUT) {
                JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
    }
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mx, int my) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
    }
    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realX, int realY, float a) {
        linkWindow.sync(leftPos, topPos);
        link.setLatched(linkWindow.isOpen());
        // Under the Deck Link window nothing lights up or shows a tooltip, except its own slots.
        boolean hidden = linkWindow.hidesMouse(realX, realY);
        int mx = hidden ? -1000 : realX;
        int my = hidden ? -1000 : realY;
        super.extractContents(graphics, mx, my, a);
        linkWindow.setResetActive(menu.canReset());
        graphics.nextStratum();
        for (var editor : editors) editor.draw(graphics, mx, my, a, width, height);
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            if (hoveredSlot == menu.getSlot(i))
                graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.transfer.speed_hint"), mx, my);
        }
        if (hoveredSlot == menu.getSlot(TransferPortMenu.POWER)) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.port.power_hint"), 180), mx, my);
        }
        if (linkWindow.isOpen()) {
            graphics.nextStratum();
            linkWindow.draw(graphics, realX, realY, a, DeckLinkWindow.linkedTo(menu.linkedPlayer(), menu.linked()));
        }
    }
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (linkWindow.contains(event.x(), event.y())) {
            return linkWindow.mouseClicked(event, doubleClick) || super.mouseClicked(event, doubleClick);
        }
        for (var editor : editors) {
            if (editor.contains(event.x(), event.y())) {
                for (var other : editors) if (other != editor) other.unfocus();
                return editor.mouseClicked(event, doubleClick);
            }
        }
        editors.forEach(ItemFilterEditor::unfocus);
        return super.mouseClicked(event, doubleClick);
    }
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (linkWindow.mouseDragged(event, width, height)) return true;
        for (var editor : editors) if (editor.mouseDragged(event, width, height)) return true;
        return super.mouseDragged(event, dx, dy);
    }
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = linkWindow.mouseReleased();
        for (var editor : editors) handled |= editor.mouseReleased(event);
        return handled || super.mouseReleased(event);
    }
    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (linkWindow.contains(x, y)) return true;
        for (var editor : editors) if (editor.contains(x, y)) return editor.mouseScrolled(sy);
        return super.mouseScrolled(x, y, sx, sy);
    }
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (linkWindow.isOpen() && event.isEscape()) {
            linkWindow.close();
            link.setLatched(false);
            return true;
        }
        for (var editor : editors) if (editor.keyPressed(event)) return true;
        return super.keyPressed(event);
    }
    @Override
    public boolean charTyped(CharacterEvent event) {
        for (var editor : editors) if (editor.charTyped(event)) return true;
        return super.charTyped(event);
    }
}
