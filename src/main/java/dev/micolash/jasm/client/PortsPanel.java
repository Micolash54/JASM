package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.PortList;
import dev.micolash.jasm.registry.JasmItems;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The port list in a panel left of the main one, on the Advanced Encoder and the Rack Controller: every Access Port on
 * the network the player may use, with the machine it serves and its name. A click opens that port's own screen. Ports
 * in another dimension are greyed and can't be opened; a port whose machine another port keeps is tinted red. The list
 * is as tall as the main panel and shows as many whole rows as fit. It folds away to a thin strip, like the Access
 * Port's recipe list, and each screen remembers whether it was folded.
 */
final class PortsPanel {
    /** The panel, left of the main one, and its rows. */
    private static final int LIST = 113;
    private static final int LIST_X = -LIST;
    private static final int ROWS_X = LIST_X + 8;
    private static final int ROWS_Y = 18;
    private static final int ROW_WIDTH = 90;
    private static final int ROW_HEIGHT = 18;
    private static final int TRACK_X = LIST_X + 100;
    private static final int KNOB = 18;
    /** The strip holding the fold key, on the left edge of the list, or of the main panel while the list is folded. */
    private static final int[] STRIP = {-13, 25, 16, 30};
    /** How far left of the main panel the open list and its fold key reach. */
    static final int REACH = -(LIST_X + STRIP[0]);
    private static final int TAKEN_TINT = 0x44F38BA8;
    private static final int ELSEWHERE_DIM = 0xAA1E1E2E;
    private static final JasmButton.Icon FOLD = new JasmButton.Icon(Jasm.id("icon/triangle_right"), 4, 6);
    private static final JasmButton.Icon UNFOLD = new JasmButton.Icon(Jasm.id("icon/triangle_left"), 4, 6);

    private final AbstractContainerMenu container;
    private final PortList.Menu menu;
    private final Font font;
    private final BooleanSupplier foldedSetting;
    private final Consumer<Boolean> keepFolded;
    private final Runnable changed;
    private int height;
    /** The list's well, under the title: whole rows, then any room left over. */
    private int well;
    private int rows;
    private int left;
    private int top;
    private int first;
    private boolean dragging;
    private JasmButton fold;

    /**
     * {@code foldedSetting} reads and {@code keepFolded} stores the screen's own folded setting; {@code changed} runs when
     * the list folds or opens, so the screen redraws its frame.
     */
    <M extends AbstractContainerMenu & PortList.Menu> PortsPanel(M menu, Font font, BooleanSupplier foldedSetting,
            Consumer<Boolean> keepFolded, Runnable changed) {
        this.container = menu;
        this.menu = menu;
        this.font = font;
        this.foldedSetting = foldedSetting;
        this.keepFolded = keepFolded;
        this.changed = changed;
    }

    private boolean folded() {
        return foldedSetting.getAsBoolean();
    }

    /**
     * The fold key, for the screen to add; one key in the same spot either way, on the left edge of whatever is leftmost.
     * {@code height} is the main panel's, which the list matches.
     */
    JasmButton foldKey(int left, int top, int height) {
        this.left = left;
        this.top = top;
        this.height = height;
        this.well = Math.max(ROW_HEIGHT, height - ROWS_Y - 6);
        this.rows = well / ROW_HEIGHT;
        fold = JasmButton.icon(() -> folded() ? UNFOLD : FOLD, Component.translatable("screen.jasm.terminal.ports_fold"),
                b -> setFolded(!folded()), 0, top + 29, 11, 22);
        showFold();
        return fold;
    }

    private void setFolded(boolean folded) {
        keepFolded.accept(folded);
        dragging = false;
        showFold();
        changed.run();
    }

    private void showFold() {
        boolean open = !folded();
        fold.setX(left + (open ? LIST_X : 0) - 11);
        Component label = Component.translatable(open ? "screen.jasm.terminal.ports_fold" : "screen.jasm.terminal.ports_unfold");
        fold.setMessage(label);
        fold.setTooltip(Tooltip.create(label));
    }

    /** The panel's shapes for the screen's frame, relative to the main panel's corner. */
    List<int[]> frameParts() {
        int edge = folded() ? 0 : LIST_X;
        int[] strip = {edge + STRIP[0], STRIP[1], STRIP[2], STRIP[3]};
        return folded() ? List.of(strip) : List.of(new int[]{LIST_X, 0, LIST + 3, height}, strip);
    }

    /** Where the list (or the strip that brings it back) is on screen, so JEI's lists stay clear of it. */
    List<Rect2i> areas() {
        List<Rect2i> areas = new ArrayList<>();
        int edge = folded() ? 0 : LIST_X;
        areas.add(new Rect2i(left + edge + STRIP[0], top + STRIP[1], -STRIP[0], STRIP[3]));
        if (!folded()) areas.add(new Rect2i(left + LIST_X, top, LIST, height));
        return areas;
    }

    private int max() {
        return Math.max(0, menu.ports().size() - rows);
    }

    /** The row under the mouse, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        if (folded()) return -1;
        int x = (int) Math.floor(mouseX) - left - ROWS_X;
        int y = (int) Math.floor(mouseY) - top - ROWS_Y;
        if (x < 0 || y < 0 || x >= ROW_WIDTH || y >= rows * ROW_HEIGHT) return -1;
        int index = first + y / ROW_HEIGHT;
        return index < menu.ports().size() ? index : -1;
    }

    // --- drawing ---

    void drawBackground(GuiGraphicsExtractor graphics) {
        if (folded()) return;
        first = Math.clamp(first, 0, max());
        JasmGui.inset(graphics, left + ROWS_X, top + ROWS_Y, ROW_WIDTH, well);
        int offset = max() == 0 ? 0 : Math.round((well - KNOB) * first / (float) max());
        JasmGui.track(graphics, left + TRACK_X, top + ROWS_Y, well, offset, KNOB, max() > 0);
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (folded()) return;
        List<PortList.Row> ports = menu.ports();
        graphics.text(font, Component.translatable("screen.jasm.terminal.ports"), left + LIST_X + 8, top + 6, JasmGui.TEXT, false);
        String count = String.valueOf(ports.size());
        graphics.text(font, count, left + LIST_X + 110 - font.width(count) + 1, top + 6, JasmGui.MUTED, false);
        int x = left + ROWS_X;
        if (ports.isEmpty()) {
            boolean power = menu.running();
            Component empty = power ? Component.translatable("screen.jasm.terminal.ports_none")
                    : MachineStatusText.noPower(container.containerId, "screen.jasm.terminal.ports_no_power");
            List<FormattedCharSequence> lines = font.split(empty, ROW_WIDTH - 6);
            int y = top + ROWS_Y + well / 2 - lines.size() * 5;
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(font, lines.get(i), x + (ROW_WIDTH - font.width(lines.get(i))) / 2, y + i * 10,
                        power ? JasmGui.MUTED : JasmGui.BAD, false);
            }
            return;
        }
        int hovered = rowAt(mouseX, mouseY);
        int last = Math.min(ports.size(), first + rows);
        for (int i = first; i < last; i++) {
            PortList.Row row = ports.get(i);
            int y = top + ROWS_Y + (i - first) * ROW_HEIGHT;
            if (row.taken()) graphics.fill(x + 1, y + 1, x + ROW_WIDTH - 1, y + ROW_HEIGHT - 1, TAKEN_TINT);
            if (i == hovered && row.here()) graphics.fill(x + 1, y + 1, x + ROW_WIDTH - 1, y + ROW_HEIGHT - 1, JasmGui.HOVER);
            graphics.item(row.icon(), x + 1, y + 1);
            int color = !row.here() ? JasmGui.MUTED : row.taken() ? JasmGui.BAD : JasmGui.TEXT;
            String name = row.name().getString();
            int room = ROW_WIDTH - 22;
            if (font.width(name) > room) name = font.plainSubstrByWidth(name, room - font.width("...")) + "...";
            graphics.text(font, name, x + 19, y + 5, color, false);
        }
        // Ports in another dimension: their machine greyed over.
        graphics.nextStratum();
        for (int i = first; i < last; i++) {
            if (ports.get(i).here()) continue;
            int y = top + ROWS_Y + (i - first) * ROW_HEIGHT;
            graphics.fill(x + 1, y + 1, x + 17, y + 17, ELSEWHERE_DIM);
        }
        if (hovered >= 0) graphics.setTooltipForNextFrame(font, tooltip(ports.get(hovered)), mouseX, mouseY);
    }

    /** The port's name, the machine it serves, then why it can't be opened, or how to open it. */
    private List<FormattedCharSequence> tooltip(PortList.Row row) {
        List<Component> lines = new ArrayList<>();
        lines.add(row.name().copy().withColor(JasmGui.TEXT & 0xFFFFFF));
        if (!row.icon().is(JasmItems.ACCESS_PORT.get()) && !row.icon().is(JasmItems.THIN_ACCESS_PORT.get())) {
            lines.add(Component.translatable("screen.jasm.terminal.port_machine", row.icon().getHoverName()).withColor(JasmGui.SUBTEXT & 0xFFFFFF));
        }
        if (row.taken()) {
            lines.add(Component.translatable("screen.jasm.port.machine_taken").withColor(JasmGui.BAD & 0xFFFFFF));
        }
        if (!row.here()) {
            String dimension = RackControllerScreen.dimensionName(row.at().dimension().identifier().getPath());
            lines.add(Component.translatable("screen.jasm.terminal.port_elsewhere", dimension).withColor(JasmGui.WARN & 0xFFFFFF));
        } else {
            lines.add(Component.translatable("screen.jasm.terminal.port_open").withColor(JasmGui.MUTED & 0xFFFFFF));
        }
        List<FormattedCharSequence> split = new ArrayList<>();
        for (Component line : lines) split.addAll(font.split(line, 180));
        return split;
    }

    // --- input ---

    /** A click on a row opens its port, unless it stands in another dimension or something is held; on the track it scrolls. */
    boolean mouseClicked(double mouseX, double mouseY, boolean left) {
        if (folded()) return false;
        int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            PortList.Row port = menu.ports().get(row);
            if (left && port.here() && container.getCarried().isEmpty()) {
                ClientPacketDistributor.sendToServer(new CraftPayloads.OpenPort(container.containerId, port.at(), port.face()));
            }
            return true;
        }
        if (left && onTrack(mouseX, mouseY) && max() > 0) {
            dragging = true;
            dragTo(mouseY);
            return true;
        }
        return contains(mouseX, mouseY);
    }

    boolean mouseDragged(double mouseY) {
        if (!dragging) return false;
        dragTo(mouseY);
        return true;
    }

    boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!contains(mouseX, mouseY)) return false;
        first = Math.clamp(first - (int) Math.signum(scrollY), 0, max());
        return true;
    }

    /** Whether the mouse is over the open list's panel. */
    private boolean contains(double mouseX, double mouseY) {
        return !folded() && mouseX >= left + LIST_X && mouseX < left && mouseY >= top && mouseY < top + height;
    }

    private boolean onTrack(double x, double y) {
        return x >= left + TRACK_X - 1 && x < left + TRACK_X + 11 && y >= top + ROWS_Y && y < top + ROWS_Y + well;
    }

    private void dragTo(double y) {
        double fraction = Math.clamp((y - top - ROWS_Y - KNOB / 2.0) / (well - KNOB), 0.0, 1.0);
        first = (int) Math.round(fraction * max());
    }
}
