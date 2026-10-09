package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.CraftingJob;
import dev.micolash.jasm.autocraft.CraftingServerMenu;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.autocraft.MaterialMarkerItem;
import dev.micolash.jasm.autocraft.PauseReason;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.core.MaterialKey;
import dev.micolash.jasm.wafer.FluidAmounts;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Crafting Server screen: Processors and Storage Modules in a side panel on the left; in
 * the main panel, the job (what it makes, how far along, what holds it up, and what is crafting right now) with buttons to
 * cancel it or collect its results, and the player's inventory. Opened from a Crafting Deck, it also has a button back to the Deck.
 */
public class CraftingServerScreen extends JasmScreen<CraftingServerMenu> {
    private static final int MAIN_X = CraftingServerMenu.MAIN_X;
    private static final int MAIN_WIDTH = CraftingServerMenu.MAIN_WIDTH;
    private static final int WIDTH = MAIN_X + MAIN_WIDTH;
    private static final int HEIGHT = CraftingServerMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = MAIN_X + MAIN_WIDTH - 8 - HELP_ROOM - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int PANEL_X = MAIN_X + 8;
    private static final int PANEL_Y = CraftingServerMenu.JOB_Y - 4;
    private static final int PANEL_W = MAIN_WIDTH - 16;
    private static final int PANEL_H = 108;
    private static final int BUTTONS_Y = PANEL_Y + PANEL_H + 4;
    /** The list of what is crafting right now, under a line below the job's state. */
    private static final int LIST_X = CraftingServerMenu.JOB_X;
    private static final int LIST_Y = CraftingServerMenu.JOB_Y + 34;
    private static final int ROW_H = 19;
    private static final int ROWS = 3;
    private static final int LIST_RIGHT = PANEL_X + 143;
    private static final int SCROLL_X = PANEL_X + 146;
    private static final int SCROLL_W = 10;
    private static final int HANDLE_HEIGHT = 12;
    private static final int TEXT_X = LIST_X + 19;
    private static final int ROW_BAR_W = 70;
    /** The name of the machine a craft is out in. */
    private static final int MACHINE = 0xFF89B4FA;

    private Button cancel;
    private Button collect;
    private Button back;
    private JasmFrame frame;
    private int scroll;
    private boolean draggingHandle;

    public CraftingServerScreen(CraftingServerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.titleLabelX = MAIN_X + 8;
        this.inventoryLabelX = MAIN_X + 8;
        this.inventoryLabelY = CraftingServerMenu.INVENTORY_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        addHelp(MAIN_X + MAIN_WIDTH - 7, "items/crafting-server.md");
        int y = topPos + BUTTONS_Y;
        back = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.server.back"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, CraftingServerMenu.BUTTON_BACK),
                leftPos + PANEL_X, y, 40, 17));
        cancel = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.server.cancel"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, CraftingServerMenu.BUTTON_CANCEL),
                leftPos + PANEL_X + PANEL_W - 44 - 2 - 44, y, 44, 17));
        collect = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.server.collect"),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, CraftingServerMenu.BUTTON_COLLECT),
                leftPos + PANEL_X + PANEL_W - 44, y, 44, 17));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        if (frame == null)
            frame = JasmFrame.rounded(new int[]{0, 0, CraftingServerMenu.SIDE_WIDTH, CraftingServerMenu.SIDE_HEIGHT},
                    new int[]{MAIN_X, 0, MAIN_WIDTH, imageHeight});
        frame.draw(graphics, x, y);
        for (Slot slot : menu.slots) {
            if (slot.index != CraftingServerMenu.SLOT_SHOWN) {
                JasmGui.slot(graphics, x + slot.x, y + slot.y);
            }
        }
        JasmGui.inset(graphics, x + PANEL_X, y + PANEL_Y, PANEL_W, PANEL_H);
        JasmGui.bar(graphics, x + BAR_X, y + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
        if (menu.busy()) {
            JasmGui.divider(graphics, x + PANEL_X + 2, y + CraftingServerMenu.JOB_Y + 30, PANEL_W - 4);
            int track = visibleRows() * ROW_H - 1;
            int offset = maxScroll() == 0 ? 0 : Math.round((track - 2 - HANDLE_HEIGHT) * Math.clamp(scroll, 0, maxScroll()) / (float) maxScroll());
            JasmGui.scrollBar(graphics, x + SCROLL_X, y + listTop() - 1, SCROLL_W, track, offset, HANDLE_HEIGHT, maxScroll() > 0);
            JasmGui.bar(graphics, x + PANEL_X + 4, y + PANEL_Y + PANEL_H - 10, PANEL_W - 8, 6, menu.progress());
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        CraftingJob.Phase phase = menu.phase();
        cancel.active = menu.canControlJob() && phase == CraftingJob.Phase.CRAFTING;
        collect.active = menu.canControlJob() && phase == CraftingJob.Phase.RETURNING;
        back.visible = menu.opensFromDeck();
        super.extractContents(graphics, mouseX, mouseY, a);
        // The last message (collected, cancelling...) over the bottom of the job panel for a few seconds.
        Component notice = menu.notices().current(minecraft.level.getGameTime());
        if (notice != null) {
            graphics.nextStratum();
            JasmGui.notice(graphics, font, notice, menu.notices().ok(), leftPos + PANEL_X + 1, topPos + PANEL_Y + PANEL_H - 1, PANEL_W - 2);
        }
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
        // A row's full name, and on a machine's row what the job waits on there.
        int row = (mouseY - topPos - listTop()) / ROW_H;
        if (menu.busy() && mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_RIGHT && mouseY >= topPos + listTop() && row < visibleRows()
                && scroll + row < menu.now().size()) {
            CraftingJob.Now now = menu.now().get(scroll + row);
            List<FormattedCharSequence> tip = new ArrayList<>();
            tip.add(name(now.what().toStack(1)).getVisualOrderText());
            if (now.machine().isPresent() && menu.waiting() != null) tip.addAll(font.split(menu.waiting().copy().withColor(JasmGui.SUBTEXT), 170));
            graphics.setTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
        if (menu.phase() == CraftingJob.Phase.CRAFTING) {
            int right = leftPos + PANEL_X + 4 + PANEL_W - 8;
            int top = topPos + CraftingServerMenu.JOB_Y + 20;
            if (mouseX >= right - pipsWidth() && mouseX < right && mouseY >= top - 1 && mouseY < top + 9) {
                List<FormattedCharSequence> tip = new ArrayList<>();
                tip.add(Component.translatable("screen.jasm.server.processors", menu.active(), menu.parallel()).getVisualOrderText());
                tip.addAll(font.split(Component.translatable("screen.jasm.server.processors_hint"), 170));
                graphics.setTooltipForNextFrame(font, tip, mouseX, mouseY);
            }
        }
    }

    private boolean onScrollBar(double mouseX, double mouseY) {
        return mouseX >= leftPos + SCROLL_X - 1 && mouseX < leftPos + SCROLL_X + SCROLL_W + 1 && mouseY >= topPos + listTop() - 1
                && mouseY < topPos + listTop() - 1 + visibleRows() * ROW_H - 1;
    }

    private void scrollToMouse(double mouseY) {
        double travel = visibleRows() * ROW_H - 3 - HANDLE_HEIGHT;
        double fraction = Math.clamp((mouseY - topPos - listTop() - HANDLE_HEIGHT / 2.0) / travel, 0.0, 1.0);
        scroll = (int) Math.round(fraction * maxScroll());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (menu.busy() && mouseX >= leftPos + LIST_X && mouseX < leftPos + SCROLL_X + SCROLL_W && mouseY >= topPos + listTop()
                && mouseY < topPos + listTop() + visibleRows() * ROW_H) {
            scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (menu.busy() && maxScroll() > 0 && onScrollBar(event.x(), event.y())) {
            draggingHandle = true;
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingHandle) {
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingHandle) {
            draggingHandle = false;
            return true;
        }
        return super.mouseReleased(event);
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
            Component idle = menu.running()
                    ? Component.translatable("screen.jasm.server.idle")
                    : MachineStatusText.noPower(menu.containerId, "screen.jasm.machine.no_power", font, room - 20);
            graphics.text(font, idle, tx + 20, CraftingServerMenu.JOB_Y + 4, menu.running() ? JasmGui.MUTED : JasmGui.BAD, false);
            return;
        }
        ItemStack target = menu.getSlot(CraftingServerMenu.SLOT_SHOWN).getItem();
        if (!target.isEmpty()) {
            FluidResource fluid = FluidMarkerItem.fluidOf(target);
            if (fluid != null) {
                FluidGrid.draw(graphics, fluid, CraftingServerMenu.JOB_X, CraftingServerMenu.JOB_Y);
            }
            String what = FluidGrid.describe(target, menu.amount());
            graphics.text(font, trim(what, room - 20), tx + 20, CraftingServerMenu.JOB_Y + 4, JasmGui.TEXT, false);
        }
        Component state = switch (phase) {
            case CRAFTING -> Component.translatable("screen.jasm.server.crafting", Math.round(menu.progress() * 100));
            case CANCELLING -> Component.translatable("screen.jasm.server.cancelling");
            case RETURNING -> Component.translatable("screen.jasm.server.returning");
        };
        int stateRoom = room;
        if (phase == CraftingJob.Phase.CRAFTING) {
            // A light per Processor on the right, lit while it works: all of them lit is why other machines wait.
            int width = pipsWidth();
            pips(graphics, tx + room - width, CraftingServerMenu.JOB_Y + 21);
            stateRoom = room - width - 6;
        }
        graphics.text(font, trim(state.getString(), stateRoom), tx, CraftingServerMenu.JOB_Y + 20, JasmGui.SUBTEXT, false);
        // What holds the job up, over the list: up to two lines, so a long reason is read, not cut.
        PauseReason pause = menu.pause();
        if (pause != PauseReason.NONE) {
            List<FormattedCharSequence> lines = font.split(Component.translatable(pause.key()), room);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.text(font, lines.get(i), tx, LIST_Y + i * 9, JasmGui.BAD, false);
            }
        }
        // What is crafting right now, a row each: how far its step is, and how many run at once or the machine they are in.
        List<CraftingJob.Now> rows = menu.now();
        scroll = Math.clamp(scroll, 0, maxScroll());
        for (int row = 0; row < visibleRows() && scroll + row < rows.size(); row++) {
            CraftingJob.Now now = rows.get(scroll + row);
            int y = listTop() + row * ROW_H;
            ItemStack stack = now.what().toStack(1);
            icon(graphics, stack, LIST_X, y);
            Component right = now.machine().orElse(Component.literal("×" + now.running()));
            String label = trim(right.getString(), 60);
            int labelWidth = font.width(label);
            graphics.text(font, label, LIST_RIGHT - labelWidth, y, now.machine().isPresent() ? MACHINE : JasmGui.GOOD, false);
            graphics.text(font, trim(name(stack).getString(), LIST_RIGHT - TEXT_X - labelWidth - 4), TEXT_X, y, JasmGui.TEXT, false);
            String count = amount(stack, now.made()) + "/" + amount(stack, now.total());
            int countWidth = font.width(count);
            graphics.text(font, count, LIST_RIGHT - countWidth, y + 9, JasmGui.MUTED, false);
            JasmGui.bar(graphics, TEXT_X, y + 10, Math.min(ROW_BAR_W, LIST_RIGHT - TEXT_X - countWidth - 4), 5,
                    now.total() == 0 ? 0 : now.made() / (double) now.total());
        }
    }

    /** The list starts a row lower while a reason is shown over it. */
    private int listTop() {
        return LIST_Y + (menu.pause() != PauseReason.NONE ? ROW_H : 0);
    }

    private int visibleRows() {
        return menu.pause() != PauseReason.NONE ? ROWS - 1 : ROWS;
    }

    private int maxScroll() {
        return Math.max(0, menu.now().size() - visibleRows());
    }

    private static void icon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        FluidResource fluid = FluidMarkerItem.fluidOf(stack);
        MaterialKey material = MaterialMarkerItem.materialOf(stack);
        if (fluid != null) {
            FluidGrid.draw(graphics, fluid, x, y);
        } else if (material != null) {
            MaterialIcons.draw(graphics, material, x, y);
        } else {
            graphics.item(stack, x, y);
        }
    }

    private static Component name(ItemStack stack) {
        MaterialKey material = MaterialMarkerItem.materialOf(stack);
        return material != null ? MaterialIcons.name(material) : stack.getHoverName();
    }

    private static String amount(ItemStack stack, long count) {
        return FluidMarkerItem.isMarker(stack) ? FluidAmounts.label(count) : GridEntries.abbreviate(count);
    }

    private static final int PIP = 3;
    private static final int MAX_PIPS = 12;

    private int pipsWidth() {
        return Math.min(menu.parallel(), MAX_PIPS) * (PIP + 1) - 1;
    }

    /** One small light per Processor slot that can work, green while in use; past twelve it reads as a count. */
    private void pips(GuiGraphicsExtractor graphics, int x, int y) {
        int count = Math.min(menu.parallel(), MAX_PIPS);
        int lit = menu.parallel() <= MAX_PIPS ? menu.active() : Math.round(menu.active() * MAX_PIPS / (float) menu.parallel());
        for (int i = 0; i < count; i++) {
            int px = x + i * (PIP + 1);
            graphics.fill(px, y, px + PIP, y + 7, 0xFF11111B);
            graphics.fill(px, y, px + PIP, y + 1, 0xFF45475A);
            if (i < lit) graphics.fill(px, y + 1, px + PIP, y + 7, JasmGui.GOOD);
        }
    }

    private String trim(String text, int room) {
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("...")) + "...";
    }
}
