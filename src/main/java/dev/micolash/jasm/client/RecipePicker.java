package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.crafting.PickerPayloads;
import dev.micolash.jasm.crafting.RecipePicking;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The recipe picker on a crafting screen. When more than one recipe fits the grid, a key shows above the result; it
 * opens a row of every result under the result slot, the one in use with a green rim. Clicking one makes it the result.
 */
final class RecipePicker {
    private static final JasmButton.Icon ICON = new JasmButton.Icon(Jasm.id("icon/pick_recipe"), 7, 7);
    /** Results per row; more wrap onto further rows. */
    private static final int PER_ROW = 9;
    /** Around the slots in the row. */
    private static final int PAD = 3;

    private final RecipePicking.View view;
    private final int containerId;
    private final JasmButton key;
    /** The result slot's item corner, on the screen. */
    private int resultX;
    private int resultY;
    private boolean open;

    /** The key goes at ({@code keyX}, {@code keyY}), {@code keySize} square; add {@link #key()} to the screen. */
    RecipePicker(RecipePicking.View view, int containerId, int keyX, int keyY, int keySize, int resultX, int resultY) {
        this.view = view;
        this.containerId = containerId;
        this.resultX = resultX;
        this.resultY = resultY;
        Component label = Component.translatable("screen.jasm.picker.key");
        key = JasmButton.icon(() -> ICON, label, b -> open = !open, keyX, keyY, keySize, keySize);
        key.setTooltip(Tooltip.create(label));
        update(true);
    }

    JasmButton key() {
        return key;
    }

    /** Moves the key and the row, for screens that lay themselves out again. */
    void moveTo(int keyX, int keyY, int resultX, int resultY) {
        key.setX(keyX);
        key.setY(keyY);
        this.resultX = resultX;
        this.resultY = resultY;
    }

    /** Every frame: the key shows only while there is a choice ({@code shown} lets the screen hide it too). */
    void update(boolean shown) {
        key.visible = shown && view.choosable();
        if (!key.visible) {
            open = false;
        }
        key.setLatched(open);
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
        key.setLatched(false);
    }

    private int count() {
        return view.entries().size();
    }

    private int width() {
        return Math.min(count(), PER_ROW) * 18 + 2 * PAD;
    }

    private int height() {
        return (count() + PER_ROW - 1) / PER_ROW * 18 + 2 * PAD;
    }

    /** Right edge a little past the result slot's, top just under it. */
    private int left() {
        return resultX + 17 - width() + PAD + 1;
    }

    private int top() {
        return resultY + 18;
    }

    /** Whether the open row lies under the mouse; the screen then keeps slots under it from lighting up. */
    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= left() && mouseX < left() + width() && mouseY >= top() && mouseY < top() + height();
    }

    private int at(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return -1;
        }
        int column = (int) Math.floor((mouseX - left() - PAD) / 18);
        int row = (int) Math.floor((mouseY - top() - PAD) / 18);
        if (column < 0 || column >= PER_ROW || row < 0) {
            return -1;
        }
        int index = row * PER_ROW + column;
        return index < count() ? index : -1;
    }

    /**
     * A click while the row is open: one of the results picks it; anywhere else in the row does nothing; outside the row
     * the row closes and the click goes on to the screen. The key handles its own clicks.
     */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (!open || key.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (!contains(mouseX, mouseY)) {
            close();
            return false;
        }
        int index = at(mouseX, mouseY);
        if (index >= 0) {
            if (index != view.current()) {
                ClientPacketDistributor.sendToServer(new PickerPayloads.Pick(containerId, view.entries().get(index).id()));
            }
            close();
        }
        return true;
    }

    /** Draws the open row over everything else, with the hovered result's name and mod as a tooltip. */
    void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (!open || count() < 2) {
            return;
        }
        graphics.nextStratum();
        int x0 = left();
        int y0 = top();
        JasmGui.panel(graphics, x0, y0, width(), height());
        int hovered = at(mouseX, mouseY);
        @Nullable ItemStack tip = null;
        for (int i = 0; i < count(); i++) {
            int ix = x0 + PAD + 1 + i % PER_ROW * 18;
            int iy = y0 + PAD + 1 + i / PER_ROW * 18;
            JasmGui.slot(graphics, ix, iy);
            if (i == hovered) {
                JasmGui.slotHover(graphics, ix, iy);
            }
            ItemStack result = view.entries().get(i).result();
            graphics.item(result, ix, iy);
            graphics.itemDecorations(font, result, ix, iy);
            if (i == view.current()) {
                JasmGui.rim(graphics, ix - 1, iy - 1, 18, 18, JasmGui.GOOD);
            } else if (i == hovered) {
                JasmGui.slotHoverFrame(graphics, ix, iy);
            }
            if (i == hovered) {
                tip = result;
            }
        }
        if (tip != null) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), tip));
            ItemModName.add(lines, lines.size(), ItemResource.of(tip));
            graphics.setTooltipForNextFrame(font, lines, tip.getTooltipImage(), mouseX, mouseY);
        }
    }
}
