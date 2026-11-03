package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.RackPayloads;
import dev.micolash.jasm.autocraft.RecipeRackMenu;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Recipe Rack screen: a 4 × 4 grid of card slots under the title and charge bar, like the front of the block. A side
 * key opens the rename panel.
 */
public class RecipeRackScreen extends JasmScreen<RecipeRackMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = RecipeRackMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int KEY_X = WIDTH;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - HELP_ROOM - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final JasmButton.Icon RENAME = new JasmButton.Icon(Jasm.id("icon/rename"), 12, 12);

    private final JasmFrame frame = JasmFrame.rounded(new int[]{0, 0, WIDTH, HEIGHT}, JasmGui.sideStrip(KEY_X, 1));
    private JasmButton renameKey;
    private @Nullable RenameWindow renameWindow;
    /** The name as last set from this screen; the title follows it. */
    private String label;
    private Component shownTitle;

    public RecipeRackScreen(RecipeRackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KEY_X + JasmGui.SIDE_KEY_WIDTH + 3, HEIGHT);
        this.inventoryLabelY = RecipeRackMenu.INVENTORY_Y - 10;
        label = menu.label();
        shownTitle = title;
    }

    @Override
    protected void init() {
        super.init();
        addHelp(WIDTH - 7, "items/recipe-rack.md");
        Component renameLabel = Component.translatable("screen.jasm.port.rename");
        renameKey = addRenderableWidget(JasmButton.icon(() -> RENAME, renameLabel, b -> toggleRename(),
                leftPos + KEY_X, topPos + JasmGui.sideKeyY(0), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        renameKey.setTooltip(Tooltip.create(renameLabel));
        if (renameWindow == null) {
            renameWindow = new RenameWindow(font, Component.translatable("screen.jasm.rack.name_hint"), name -> {
                ClientPacketDistributor.sendToServer(new RackPayloads.RackName(menu.containerId, name));
                renamed(name);
            });
        }
        renameKey.setLatched(renameWindow.isOpen());
    }

    private void toggleRename() {
        renameWindow.toggle(leftPos + KEY_X, topPos, label);
        renameKey.setLatched(renameWindow.isOpen());
    }

    /** The name was sent: the title shows it now, or the rack's own name for an empty one. */
    private void renamed(String name) {
        label = name;
        shownTitle = name.isEmpty() ? JasmBlocks.RECIPE_RACK.get().getName() : Component.literal(name);
    }

    /** Where the rename panel lies, so JEI's lists stay clear of it. */
    public List<Rect2i> extraAreas() {
        List<Rect2i> areas = new ArrayList<>();
        if (renameWindow != null) renameWindow.area().ifPresent(areas::add);
        return areas;
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        for (Rect2i area : extraAreas()) {
            if (area.contains((int) mouseX, (int) mouseY)) return false;
        }
        return super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        frame.draw(graphics, leftPos, topPos);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
            if (menu.machineMissing(slot.index)) {
                // A red well: this card's machine can't be reached.
                graphics.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0x80F38BA8);
            }
        }
        JasmGui.bar(graphics, leftPos + BAR_X, topPos + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        renameKey.setLatched(renameWindow.isOpen());
        // Under the rename panel nothing lights up or shows a tooltip.
        boolean hidden = renameWindow.contains(realMouseX, realMouseY);
        int mouseX = hidden ? -1000 : realMouseX;
        int mouseY = hidden ? -1000 : realMouseY;
        super.extractContents(graphics, mouseX, mouseY, a);
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, List.of(
                    Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                            String.format("%,d", menu.capacity())).getVisualOrderText(),
                    Component.translatable("screen.jasm.archive.drain", menu.drain())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText()),
                    mouseX, mouseY);
        }
        if (renameWindow.isOpen()) {
            graphics.nextStratum();
            renameWindow.draw(graphics, realMouseX, realMouseY, a);
        }
    }

    /** A card whose machine can't be reached says so at the top of its tooltip. */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = super.getTooltipFromContainerItem(stack);
        if (hoveredSlot != null && menu.machineMissing(hoveredSlot.index)) {
            lines = new ArrayList<>(lines);
            lines.add(1, Component.translatable("screen.jasm.rack.machine_missing").withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        String title = font.plainSubstrByWidth(shownTitle.getString(), BAR_X - 4 - titleLabelX);
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        if (!menu.running()) {
            // Between the cards and the inventory, wrapped to the panel.
            List<FormattedCharSequence> lines = font.split(MachineStatusText.noPower(menu.containerId, "screen.jasm.rack.no_power"), WIDTH - 16);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.text(font, lines.get(i), (WIDTH - font.width(lines.get(i))) / 2, RecipeRackMenu.CARDS_BOTTOM + 3 + i * 9, JasmGui.BAD,
                        false);
            }
        }
    }

    // --- input ---

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (renameWindow.contains(event.x(), event.y())) {
            renameWindow.mouseClicked(event, doubleClick);
            renameKey.setLatched(renameWindow.isOpen());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return renameWindow.mouseDragged(event, width, height) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return renameWindow.mouseReleased() || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return renameWindow.contains(x, y) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (renameWindow.isOpen() && renameWindow.keyPressed(event)) {
            renameKey.setLatched(renameWindow.isOpen());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return renameWindow.isOpen() && renameWindow.charTyped(event) || super.charTyped(event);
    }
}
