package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.CraftingJob;
import dev.micolash.jasm.autocraft.CraftingServerMenu;
import dev.micolash.jasm.core.GridEntries;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Crafting Server screen: Processors and Storage Modules in a side panel on the left; in
 * the main panel, the job (what it makes, how far along, what holds it up) with buttons to cancel it or collect its
 * results, and the player's inventory. Opened from a Crafting Deck, it also has a button back to the Deck.
 */
public class CraftingServerScreen extends AbstractContainerScreen<CraftingServerMenu> {
    private static final int MAIN_X = CraftingServerMenu.MAIN_X;
    private static final int MAIN_WIDTH = CraftingServerMenu.MAIN_WIDTH;
    private static final int WIDTH = MAIN_X + MAIN_WIDTH;
    private static final int HEIGHT = CraftingServerMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = MAIN_X + MAIN_WIDTH - 8 - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int PANEL_X = MAIN_X + 8;
    private static final int PANEL_Y = CraftingServerMenu.JOB_Y - 4;
    private static final int PANEL_W = MAIN_WIDTH - 16;
    private static final int PANEL_H = 62;
    private static final int BUTTONS_Y = PANEL_Y + PANEL_H + 4;

    private Button cancel;
    private Button collect;
    private Button back;

    public CraftingServerScreen(CraftingServerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.titleLabelX = MAIN_X + 8;
        this.inventoryLabelX = MAIN_X + 8;
        this.inventoryLabelY = CraftingServerMenu.INVENTORY_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        int y = topPos + BUTTONS_Y;
        back = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.server.back"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, CraftingServerMenu.BUTTON_BACK),
                leftPos + PANEL_X, y, 40, 14));
        cancel = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.server.cancel"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, CraftingServerMenu.BUTTON_CANCEL),
                leftPos + PANEL_X + PANEL_W - 44 - 2 - 44, y, 44, 14));
        collect = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.server.collect"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, CraftingServerMenu.BUTTON_COLLECT),
                leftPos + PANEL_X + PANEL_W - 44, y, 44, 14));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        JasmGui.panel(graphics, x, y, CraftingServerMenu.SIDE_WIDTH, CraftingServerMenu.SIDE_HEIGHT);
        JasmGui.panel(graphics, x + MAIN_X, y, MAIN_WIDTH, imageHeight);
        for (Slot slot : menu.slots) {
            if (slot.index != CraftingServerMenu.SLOT_SHOWN) {
                JasmGui.slot(graphics, x + slot.x, y + slot.y);
            }
        }
        JasmGui.inset(graphics, x + PANEL_X, y + PANEL_Y, PANEL_W, PANEL_H);
        JasmGui.bar(graphics, x + BAR_X, y + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
        if (menu.busy()) {
            JasmGui.bar(graphics, x + PANEL_X + 4, y + PANEL_Y + PANEL_H - 10, PANEL_W - 8, 6, menu.progress());
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        CraftingJob.Phase phase = menu.phase();
        cancel.active = phase == CraftingJob.Phase.CRAFTING;
        collect.active = phase == CraftingJob.Phase.RETURNING;
        back.visible = menu.opensFromDeck();
        super.extractContents(graphics, mouseX, mouseY, a);
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
    }

    /** Below the side panel is outside the screen, so items dropped there fall out as usual. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return mouseX < left + MAIN_X && mouseY >= top + CraftingServerMenu.SIDE_HEIGHT || super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        int tx = PANEL_X + 4;
        int room = PANEL_W - 8;
        CraftingJob.Phase phase = menu.phase();
        if (phase == null) {
            Component idle = Component.translatable(menu.running() ? "screen.jasm.server.idle" : "screen.jasm.machine.no_power");
            graphics.text(font, idle, tx + 20, CraftingServerMenu.JOB_Y + 4, menu.running() ? JasmGui.MUTED : JasmGui.BAD, false);
            return;
        }
        ItemStack target = menu.getSlot(CraftingServerMenu.SLOT_SHOWN).getItem();
        if (!target.isEmpty()) {
            String what = GridEntries.abbreviate(menu.amount()) + " × " + target.getHoverName().getString();
            graphics.text(font, trim(what, room - 20), tx + 20, CraftingServerMenu.JOB_Y + 4, JasmGui.TEXT, false);
        }
        Component state = switch (phase) {
            case CRAFTING -> Component.translatable("screen.jasm.server.crafting", Math.round(menu.progress() * 100));
            case CANCELLING -> Component.translatable("screen.jasm.server.cancelling");
            case RETURNING -> Component.translatable("screen.jasm.server.returning");
        };
        graphics.text(font, trim(state.getString(), room), tx, CraftingServerMenu.JOB_Y + 20, JasmGui.SUBTEXT, false);
        Component detail = switch (menu.pause()) {
            case 1 -> Component.translatable("screen.jasm.server.pause.no_power");
            case 2 -> Component.translatable("screen.jasm.server.pause.no_card");
            case 3 -> Component.translatable("screen.jasm.server.pause.waiting_player");
            case 4 -> Component.translatable("screen.jasm.server.pause.waiting_space");
            case 5 -> Component.translatable("screen.jasm.server.pause.no_network");
            default -> phase == CraftingJob.Phase.RETURNING ? Component.empty()
                    : Component.translatable("screen.jasm.server.active", menu.active());
        };
        // Up to two lines, so a long reason is read, not cut.
        List<FormattedCharSequence> lines = font.split(detail, room);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            graphics.text(font, lines.get(i), tx, CraftingServerMenu.JOB_Y + 30 + i * 9, menu.pause() == 0 ? JasmGui.MUTED : JasmGui.BAD, false);
        }
    }

    private String trim(String text, int room) {
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("...")) + "...";
    }
}
