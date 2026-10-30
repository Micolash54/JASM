package dev.micolash.jasm.client;

import dev.micolash.jasm.wafer.FluidAmounts;
import java.math.BigDecimal;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A small number box that opens under one processing slot of the Encoding Terminal, to type its amount. Items take whole
 * numbers; fluids take buckets with up to three decimals ("1.25" is 1,250 mB). Enter sets the amount, Escape or a click
 * elsewhere leaves it as it was.
 */
final class SlotAmountEntry {
    /** The narrowest the box gets; it widens for slots that take longer amounts. */
    private static final int BOX_WIDTH = 40;
    private static final int BOX_HEIGHT = 12;
    private static final int UNIT_GAP = 3;

    private final Font font;
    private final JasmField box;
    private final BiConsumer<Integer, Integer> apply;
    private int slot = -1;
    private int x;
    private int y;
    private int width;
    private int boxWidth = BOX_WIDTH;
    private boolean fluid;
    private int max;
    private Component unit = Component.empty();

    /** {@code apply} gets the slot and the typed amount (millibuckets for a fluid). */
    SlotAmountEntry(Font font, BiConsumer<Integer, Integer> apply) {
        this.font = font;
        this.apply = apply;
        box = new JasmField(font, 0, 0, BOX_WIDTH, BOX_HEIGHT, Component.translatable("screen.jasm.terminal.amount"));
    }

    boolean isOpen() {
        return slot >= 0;
    }

    /**
     * Opens under processing slot {@code slot}, whose top-left corner is at {@code slotX, slotY} on screen, showing
     * {@code current}: millibuckets for a fluid, shown and typed in buckets with the unit beside the box. Nothing over
     * {@code max} is set. The box stays inside {@code left} to {@code right}.
     */
    void open(int slot, int slotX, int slotY, int current, int max, boolean fluid, int left, int right) {
        this.slot = slot;
        this.fluid = fluid;
        this.max = max;
        unit = fluid ? Component.translatable("screen.jasm.terminal.amount_unit_b") : Component.empty();
        int whole = String.valueOf(fluid ? max / FluidAmounts.PER_BUCKET : max).length();
        int length = fluid ? whole + 4 : whole;
        // Room for the longest amount the slot takes, plus the box's own padding.
        boxWidth = Math.max(BOX_WIDTH, font.width("8".repeat(length)) + 10);
        width = boxWidth + (fluid ? UNIT_GAP + font.width(unit) : 0);
        x = Math.clamp(slotX + 8 - width / 2, left, Math.max(left, right - width));
        y = slotY + 19;
        box.setX(x);
        box.setY(y);
        box.setWidth(boxWidth);
        String allowed = fluid ? "\\d{0," + whole + "}(\\.\\d{0,3})?" : "\\d{0," + whole + "}";
        box.setFilter(text -> text.matches(allowed));
        box.setMaxLength(length);
        box.setValue(fluid ? BigDecimal.valueOf(current, 3).stripTrailingZeros().toPlainString() : String.valueOf(current));
        box.setFocused(true);
        // Everything selected, so typing replaces the amount.
        box.moveCursorToEnd(false);
        box.setHighlightPos(0);
    }

    int slot() {
        return slot;
    }

    void close() {
        slot = -1;
        box.setFocused(false);
    }

    boolean contains(double mouseX, double mouseY) {
        return isOpen() && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BOX_HEIGHT;
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return box.mouseClicked(event, doubleClick);
    }

    boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return box.mouseDragged(event, dx, dy);
    }

    boolean mouseReleased(MouseButtonEvent event) {
        return box.mouseReleased(event);
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!isOpen()) {
            return;
        }
        box.extractRenderState(graphics, mouseX, mouseY, a);
        if (width > boxWidth) {
            graphics.text(font, unit, x + boxWidth + UNIT_GAP, y + 2, JasmGui.SUBTEXT, false);
        }
    }

    /** Handles every key while the box is open, so none reaches the screen behind it. */
    boolean keyPressed(KeyEvent event) {
        if (!isOpen()) {
            return false;
        }
        if (event.isEscape()) {
            close();
        } else if (event.isConfirmation()) {
            int target = slot;
            close();
            int amount = Math.min(typed(), max);
            // Nothing typed, or nothing at all: the amount stays as it was.
            if (amount > 0) {
                apply.accept(target, amount);
            }
        } else {
            box.keyPressed(event);
        }
        return true;
    }

    /** The typed amount: a count, or for a fluid its buckets in millibuckets. 0 when nothing is typed. */
    private int typed() {
        String text = box.getValue();
        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        int amount = whole.isEmpty() ? 0 : Integer.parseInt(whole);
        if (!fluid) {
            return amount;
        }
        String part = dot < 0 ? "" : text.substring(dot + 1);
        return amount * FluidAmounts.PER_BUCKET + (part.isEmpty() ? 0 : Integer.parseInt((part + "00").substring(0, 3)));
    }

    /** Only digits reach the box, and for a fluid one decimal point; the box's filter keeps the rest in shape. */
    boolean charTyped(CharacterEvent event) {
        int c = event.codepoint();
        return isOpen() && (!(Character.isDigit(c) || fluid && c == '.') || box.charTyped(event));
    }
}
