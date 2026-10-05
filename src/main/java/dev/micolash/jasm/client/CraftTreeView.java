package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** The crafting tree inside the request window's list area: boxes in rows, lines between them, drag to move. */
final class CraftTreeView {
    private static final int BOX = 24;
    private static final int STEP_X = BOX + 10;
    private static final int STEP_Y = BOX + 14;
    private static final int MARGIN = 6;
    private static final int LINE = 0xFF6C7086;

    private CraftPayloads.@Nullable TreeView tree;
    private @Nullable TreeLayout layout;
    private int shape;
    private int panX;
    private int panY;
    private boolean dragging;
    private double lastX;
    private double lastY;
    private int areaW;
    private int areaH;

    /** The newest tree. The view goes back to the top left only when the shape of the tree changed, not on every refresh. */
    void show(CraftPayloads.TreeView tree) {
        if (tree == this.tree) {
            return;
        }
        int newShape = 31 * tree.boxes().size() + tree.links().size();
        this.tree = tree;
        int[][] links = new int[tree.links().size()][];
        for (int i = 0; i < links.length; i++) {
            links[i] = new int[]{tree.links().get(i).from(), tree.links().get(i).to()};
        }
        layout = TreeLayout.of(tree.boxes().size(), tree.root(), links);
        if (newShape != shape) {
            shape = newShape;
            panX = 0;
            panY = 0;
        }
    }

    private int contentW() {
        return layout == null ? 0 : (int) Math.ceil(layout.width() * STEP_X) - 10 + MARGIN * 2;
    }

    private int contentH() {
        return layout == null ? 0 : layout.rows() * STEP_Y - 14 + MARGIN * 2;
    }

    private void clampPan() {
        panX = Math.clamp(panX, -Math.max(0, contentW() - areaW), 0);
        panY = Math.clamp(panY, -Math.max(0, contentH() - areaH), 0);
    }

    /** Screen x of the left edge of {@code box}; a tree narrower than the area is centred. */
    private int boxX(TreeLayout layout, int x, int box) {
        int free = Math.max(0, areaW - contentW());
        return x + MARGIN + free / 2 + panX + (int) Math.round(layout.column(box) * STEP_X);
    }

    private int boxY(TreeLayout layout, int y, int box) {
        return y + MARGIN + panY + layout.row(box) * STEP_Y;
    }

    void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h, int mouseX, int mouseY) {
        areaW = w;
        areaH = h;
        JasmGui.inset(graphics, x, y, w, h);
        CraftPayloads.TreeView shown = tree;
        TreeLayout placed = layout;
        if (shown == null || placed == null) {
            return;
        }
        clampPan();
        graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        for (CraftPayloads.TreeLink link : shown.links()) {
            int fromX = boxX(placed, x, link.from()) + BOX / 2;
            int fromY = boxY(placed, y, link.from()) + BOX;
            int toX = boxX(placed, x, link.to()) + BOX / 2;
            int toY = boxY(placed, y, link.to());
            int midY = fromY + (toY - fromY) / 2;
            graphics.fill(fromX, fromY, fromX + 1, midY + 1, LINE);
            graphics.fill(Math.min(fromX, toX), midY, Math.max(fromX, toX) + 1, midY + 1, LINE);
            graphics.fill(toX, midY, toX + 1, toY, LINE);
        }
        CraftPayloads.TreeBox hovered = null;
        boolean mouseIn = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        for (int i = 0; i < shown.boxes().size(); i++) {
            CraftPayloads.TreeBox box = shown.boxes().get(i);
            int bx = boxX(placed, x, i);
            int by = boxY(placed, y, i);
            if (bx + BOX < x || bx > x + w || by + BOX < y || by > y + h) {
                continue;
            }
            graphics.fill(bx, by, bx + BOX, by + BOX, 0xFF11111B);
            JasmGui.rim(graphics, bx, by, BOX, BOX, box.kind() == 1 ? JasmGui.GOOD : box.kind() == 2 ? JasmGui.BAD : JasmGui.SELECTED);
            ItemStack stack = box.key().toStack(1);
            FluidGrid.drawStack(graphics, stack, bx + 4, by + 4);
            JasmGui.fitCount(graphics, font, shortCount(stack, box.amount()), bx + 4, by + 4);
            if (mouseIn && mouseX >= bx && mouseX < bx + BOX && mouseY >= by && mouseY < by + BOX) {
                hovered = box;
            }
        }
        graphics.disableScissor();
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
        }
    }

    /** The count printed on the box: 12, 1.5K, or a fluid's amount. */
    private static String shortCount(ItemStack stack, long amount) {
        return FluidMarkerItem.isMarker(stack) ? FluidAmounts.label(amount) : GridEntries.abbreviate(amount);
    }

    private static List<FormattedCharSequence> tooltip(CraftPayloads.TreeBox box) {
        ItemStack stack = box.key().toStack(1);
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(stack.getHoverName().getVisualOrderText());
        String amount = shortCount(stack, box.amount());
        Component second = switch (box.kind()) {
            case 1 -> Component.translatable("screen.jasm.craft.tree_takes", amount);
            case 2 -> Component.translatable("screen.jasm.craft.tree_lacks", amount);
            default -> box.crafts() == 1
                    ? Component.translatable("screen.jasm.craft.tree_runs_once", amount)
                    : Component.translatable("screen.jasm.craft.tree_runs", GridEntries.abbreviate(box.crafts()), amount);
        };
        lines.add(second.getVisualOrderText());
        return lines;
    }

    // --- input ---

    boolean mouseClicked(MouseButtonEvent event, int x, int y, int w, int h) {
        if (event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + h) {
            dragging = true;
            lastX = event.x();
            lastY = event.y();
            return true;
        }
        return false;
    }

    boolean mouseDragged(MouseButtonEvent event) {
        if (!dragging) {
            return false;
        }
        panX += (int) Math.round(event.x() - lastX);
        panY += (int) Math.round(event.y() - lastY);
        lastX = event.x();
        lastY = event.y();
        clampPan();
        return true;
    }

    boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    /** The wheel moves up and down; with Shift, sideways. */
    boolean mouseScrolled(double scrollX, double scrollY, boolean shift) {
        int by = (int) Math.round((scrollY != 0 ? scrollY : scrollX) * STEP_Y);
        if (shift) {
            panX += by;
        } else {
            panY += by;
        }
        clampPan();
        return true;
    }
}
