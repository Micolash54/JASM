package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.CraftRule;
import dev.micolash.jasm.deck.DeckMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * Edits one Crafting Deck rule: which item, "when fewer than" or "every", the number for that, how many to craft,
 * and whether it is on. Drawn above the Deck screen like the other windows, and moved by dragging any empty spot of it.
 * The server checks and trims every value.
 */
final class RuleWindow {
    static final int WIDTH = 190;
    static final int HEIGHT = 126;
    private static final int SLOT_X = 8;
    private static final int SLOT_Y = 28;
    // Row one: the item and the kind of rule. Row two: the number it waits for. Row three: how many to craft.
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);

    private final DeckMenu menu;
    private final Font font;
    private final EditBox first;
    private final EditBox second;
    private final List<Placed> buttons = new ArrayList<>();
    private final Button kind;
    private final Button enabled;
    private final Button delete;
    private final Button sendTo;
    private int index = -1;
    private @Nullable UUID id;
    private ItemResource item = ItemResource.EMPTY;
    private boolean timed;
    private boolean on = true;
    private boolean toPlayer;
    private int x;
    private int y;
    /** Where the window was picked up, from its corner; -1 while it isn't being dragged. */
    private int grabX = -1;
    private int grabY;

    private record Placed(Button button, int x, int y) {}

    RuleWindow(DeckMenu menu, Font font) {
        this.menu = menu;
        this.font = font;
        first = new JasmField(font, 0, 0, 50, 12, Component.translatable("screen.jasm.rule.number"));
        second = new JasmField(font, 0, 0, 50, 12, Component.translatable("screen.jasm.rule.amount"));
        first.setMaxLength(7);
        second.setMaxLength(7);
        place(JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11), WIDTH - 16, 5);
        kind = place(JasmButton.text(Component.empty(), b -> {
            timed = !timed;
            first.setValue(timed ? "60" : "16");
        }, 0, 0, 100, 16), 30, 28);
        sendTo = place(JasmButton.text(Component.empty(), b -> toPlayer = !toPlayer, 0, 0, 50, 16), 56, 83);
        enabled = place(JasmButton.text(Component.empty(), b -> on = !on, 0, 0, 36, 16), 8, HEIGHT - 22);
        delete = place(JasmButton.text(Component.translatable("screen.jasm.rule.delete"), b -> {
            send(Optional.empty());
            close();
        }, 0, 0, 44, 16), 48, HEIGHT - 22);
        place(JasmButton.text(Component.translatable("screen.jasm.deck.settings.done"), b -> {
            save();
        }, 0, 0, 40, 16), WIDTH - 47, HEIGHT - 22);
    }

    private Button place(Button button, int px, int py) {
        buttons.add(new Placed(button, px, py));
        return button;
    }

    boolean isOpen() {
        return index >= 0;
    }

    /** Opens rule {@code index}, or a new one when {@code rule} is null. */
    void open(int index, @Nullable CraftRule rule, int left, int top) {
        this.index = index;
        this.x = left;
        this.y = top;
        if (rule == null) {
            id = null;
            item = ItemResource.EMPTY;
            timed = false;
            on = true;
            toPlayer = false;
            first.setValue("16");
            second.setValue("16");
        } else {
            id = rule.id();
            item = rule.item();
            timed = rule.timed();
            on = rule.enabled();
            toPlayer = rule.toPlayer();
            first.setValue(String.valueOf(timed ? rule.seconds() : rule.threshold()));
            second.setValue(String.valueOf(rule.amount()));
        }
        first.setFocused(false);
        second.setFocused(false);
    }

    void close() {
        index = -1;
        grabX = -1;
        first.setFocused(false);
        second.setFocused(false);
    }

    boolean contains(double mouseX, double mouseY) {
        return isOpen() && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    Optional<Rect2i> area() {
        return isOpen() ? Optional.of(new Rect2i(x, y, WIDTH + 3, HEIGHT + 3)) : Optional.empty();
    }

    /** The item slot on screen, for JEI drag and drop. */
    Rect2i slotArea() {
        return new Rect2i(x + SLOT_X, y + SLOT_Y, 16, 16);
    }

    /** Sets the rule's item (from the cursor or JEI). */
    void setItem(ItemStack stack) {
        item = stack.isEmpty() ? ItemResource.EMPTY : ItemResource.of(stack.copyWithCount(1));
    }

    private static long number(EditBox box, long fallback) {
        try {
            return Math.max(1, Long.parseLong(box.getValue()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void save() {
        if (item.isEmpty()) {
            return;
        }
        long a = number(first, 1);
        send(Optional.of(new CraftRule(id == null ? UUID.randomUUID() : id, item, timed, timed ? 1 : a, timed ? (int) Math.min(a, 86_400) : 60,
                number(second, 1), on, toPlayer)));
        close();
    }

    private void send(Optional<CraftRule> rule) {
        ClientPacketDistributor.sendToServer(new CraftPayloads.SetRule(menu.containerId, index, rule));
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!isOpen()) {
            return;
        }
        kind.setMessage(Component.translatable(timed ? "screen.jasm.rule.every" : "screen.jasm.rule.below"));
        enabled.setMessage(JasmGui.state(Component.translatable(on ? "screen.jasm.rule.on" : "screen.jasm.rule.off"), on));
        sendTo.setMessage(Component.translatable(toPlayer ? "screen.jasm.rule.to_player" : "screen.jasm.rule.to_deck"));
        delete.visible = id != null;
        for (Placed placed : buttons) {
            placed.button().setPosition(x + placed.x(), y + placed.y());
        }
        first.setPosition(x + 40, y + 50);
        second.setPosition(x + 40, y + 70);
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        JasmGui.window(graphics, x, y, WIDTH, HEIGHT);
        graphics.text(font, Component.translatable(id == null ? "screen.jasm.rule.new" : "screen.jasm.rule.edit"), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 21, WIDTH - 8);
        JasmGui.slot(graphics, x + SLOT_X, y + SLOT_Y);
        if (!item.isEmpty()) {
            graphics.item(item.toStack(1), x + SLOT_X, y + SLOT_Y);
        }
        boolean overSlot = mouseX >= x + SLOT_X && mouseX < x + SLOT_X + 16 && mouseY >= y + SLOT_Y && mouseY < y + SLOT_Y + 16;
        if (overSlot) {
            graphics.fill(x + SLOT_X, y + SLOT_Y, x + SLOT_X + 16, y + SLOT_Y + 16, JasmGui.HOVER);
            graphics.setTooltipForNextFrame(font, item.isEmpty()
                    ? Component.translatable("screen.jasm.rule.pick")
                    : item.toStack(1).getHoverName(), mouseX, mouseY);
        }
        graphics.text(font, Component.translatable(timed ? "screen.jasm.rule.seconds" : "screen.jasm.rule.items"), x + 94, y + 52,
                JasmGui.MUTED, false);
        graphics.text(font, Component.translatable("screen.jasm.rule.craft"), x + 8, y + 72, JasmGui.SUBTEXT, false);
        graphics.text(font, Component.translatable("screen.jasm.rule.more"), x + 94, y + 72, JasmGui.MUTED, false);
        graphics.text(font, Component.translatable("screen.jasm.rule.send_to"), x + 8, y + 88, JasmGui.SUBTEXT, false);
        first.extractRenderState(graphics, mouseX, mouseY, a);
        second.extractRenderState(graphics, mouseX, mouseY, a);
        for (Placed placed : buttons) {
            placed.button().extractRenderState(graphics, mouseX, mouseY, a);
        }
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (Placed placed : buttons) {
            if (placed.button().visible && placed.button().mouseClicked(event, doubleClick)) {
                return true;
            }
        }
        double mx = event.x();
        double my = event.y();
        if (mx >= x + SLOT_X && mx < x + SLOT_X + 16 && my >= y + SLOT_Y && my < y + SLOT_Y + 16) {
            // Holding an item puts it in; empty-handed clears. The item itself is never used up.
            setItem(menu.getCarried());
            return true;
        }
        first.setFocused(first.isMouseOver(mx, my));
        second.setFocused(second.isMouseOver(mx, my));
        if (first.isFocused()) {
            first.mouseClicked(event, doubleClick);
        } else if (second.isFocused()) {
            second.mouseClicked(event, doubleClick);
        } else if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && grabbable(mx, my)) {
            grabX = (int) mx - x;
            grabY = (int) my - y;
        }
        return true;
    }

    /** Any spot of the window that isn't a button, field or the item slot picks it up. */
    private boolean grabbable(double mx, double my) {
        if (!contains(mx, my) || first.isMouseOver(mx, my) || second.isMouseOver(mx, my) || slotArea().contains((int) mx, (int) my)) return false;
        for (Placed placed : buttons) if (placed.button().visible && placed.button().isMouseOver(mx, my)) return false;
        return true;
    }

    /** Moves the window with the mouse while it is held, kept on screen. */
    boolean mouseDragged(MouseButtonEvent event, int screenWidth, int screenHeight) {
        if (grabX < 0) return false;
        x = Math.clamp((int) event.x() - grabX, 0, Math.max(0, screenWidth - WIDTH));
        y = Math.clamp((int) event.y() - grabY, 0, Math.max(0, screenHeight - HEIGHT));
        return true;
    }

    boolean mouseReleased() {
        boolean held = grabX >= 0;
        grabX = -1;
        return held;
    }

    boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            close();
            return true;
        }
        if (event.isConfirmation()) {
            save();
            return true;
        }
        EditBox focused = first.isFocused() ? first : second.isFocused() ? second : null;
        if (focused != null) {
            focused.keyPressed(event);
            return true;
        }
        return false;
    }

    /** Only digits reach the number boxes. */
    boolean charTyped(CharacterEvent event) {
        EditBox focused = first.isFocused() ? first : second.isFocused() ? second : null;
        if (focused == null) {
            return false;
        }
        return !Character.isDigit(event.codepoint()) || focused.charTyped(event);
    }
}
