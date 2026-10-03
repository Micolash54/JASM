package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.EncodingTerminalMenu;
import dev.micolash.jasm.network.TrustList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Encoding Terminal's access window, for its owner: who else may use their blocks on this network. Trusted players
 * are listed with a button to remove each; a name box adds one. Moved by dragging any empty spot of it.
 */
final class TrustWindow {
    static final int WIDTH = 160;
    static final int HEIGHT = 134;
    private static final int ROWS = 8;
    private static final int LIST_Y = 26;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);

    private final EncodingTerminalMenu menu;
    private final Font font;
    private final EditBox name;
    private final Button trust;
    private final Button close;
    private final List<Button> removes = new ArrayList<>();
    private boolean open;
    private int scroll;
    private int x;
    private int y;
    /** Where the window was picked up, from its corner; -1 while it isn't being dragged. */
    private int grabX = -1;
    private int grabY;

    TrustWindow(EncodingTerminalMenu menu, Font font) {
        this.menu = menu;
        this.font = font;
        name = new JasmField(font, 0, 0, WIDTH - 66, 12, Component.translatable("screen.jasm.terminal.name"));
        name.setMaxLength(16);
        name.setHint(Component.translatable("screen.jasm.terminal.name"));
        trust = JasmButton.text(Component.translatable("screen.jasm.archive.trust"), b -> add(), 0, 0, 44, 17);
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> open = false, 0, 0, 11, 11);
        for (int i = 0; i < ROWS; i++) {
            int row = i;
            removes.add(JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.terminal.untrust"), b -> remove(row), 0, 0, 9, 9));
        }
    }

    boolean isOpen() {
        return open;
    }

    void toggle(int left, int top) {
        open = !open;
        x = left;
        y = top;
        grabX = -1;
        name.setFocused(open);
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    Optional<Rect2i> area() {
        return open ? Optional.of(new Rect2i(x, y, WIDTH + 3, HEIGHT + 3)) : Optional.empty();
    }

    private void add() {
        if (!name.getValue().isBlank()) {
            ClientPacketDistributor.sendToServer(new CraftPayloads.Trust(menu.containerId, name.getValue().trim(), Optional.empty()));
            name.setValue("");
        }
    }

    private void remove(int row) {
        List<TrustList.Entry> entries = menu.trust().entries();
        if (scroll + row < entries.size()) {
            ClientPacketDistributor.sendToServer(new CraftPayloads.Trust(menu.containerId, "", Optional.of(entries.get(scroll + row).id())));
        }
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!open) {
            return;
        }
        List<TrustList.Entry> entries = menu.trust().entries();
        scroll = Math.clamp(scroll, 0, Math.max(0, entries.size() - ROWS));
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        JasmGui.window(graphics, x, y, WIDTH, HEIGHT);
        graphics.text(font, Component.translatable("screen.jasm.terminal.access"), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 20, WIDTH - 8);
        close.setPosition(x + WIDTH - 16, y + 5);
        close.extractRenderState(graphics, mouseX, mouseY, a);
        JasmGui.inset(graphics, x + 6, y + LIST_Y - 2, WIDTH - 12, ROWS * 10 + 3);
        if (entries.isEmpty()) {
            graphics.text(font, Component.translatable("screen.jasm.terminal.nobody"), x + 10, y + LIST_Y + 1, JasmGui.MUTED, false);
        }
        for (int i = 0; i < ROWS; i++) {
            Button remove = removes.get(i);
            remove.visible = scroll + i < entries.size();
            if (remove.visible) {
                graphics.text(font, entries.get(scroll + i).name(), x + 10, y + LIST_Y + 1 + i * 10, JasmGui.TEXT, false);
                remove.setPosition(x + WIDTH - 20, y + LIST_Y + i * 10);
                remove.extractRenderState(graphics, mouseX, mouseY, a);
            }
        }
        // Who was trusted, or why not, over the bottom of the list for a few seconds.
        Component notice = menu.notices().current(Minecraft.getInstance().level.getGameTime());
        if (notice != null) {
            graphics.nextStratum();
            JasmGui.notice(graphics, font, notice, menu.notices().ok(), x + 7, y + LIST_Y + ROWS * 10, WIDTH - 14);
        }
        name.setPosition(x + 8, y + HEIGHT - 22);
        trust.setPosition(x + WIDTH - 52, y + HEIGHT - 25);
        trust.active = !name.getValue().isBlank() && !menu.trust().isFull();
        name.extractRenderState(graphics, mouseX, mouseY, a);
        trust.extractRenderState(graphics, mouseX, mouseY, a);
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick) || trust.mouseClicked(event, doubleClick)) {
            return true;
        }
        for (Button remove : removes) {
            if (remove.visible && remove.mouseClicked(event, doubleClick)) {
                return true;
            }
        }
        name.setFocused(name.isMouseOver(event.x(), event.y()));
        if (name.isFocused()) {
            name.mouseClicked(event, doubleClick);
        } else if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && grabbable(event.x(), event.y())) {
            grabX = (int) event.x() - x;
            grabY = (int) event.y() - y;
        }
        return true;
    }

    /** Any spot of the window that isn't a key or the name box picks it up. */
    private boolean grabbable(double mx, double my) {
        if (!contains(mx, my) || name.isMouseOver(mx, my) || close.isMouseOver(mx, my) || trust.isMouseOver(mx, my)) return false;
        for (Button remove : removes) if (remove.visible && remove.isMouseOver(mx, my)) return false;
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

    boolean mouseScrolled(double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            open = false;
            return true;
        }
        if (event.isConfirmation()) {
            add();
            return true;
        }
        if (name.isFocused()) {
            name.keyPressed(event);
            return true;
        }
        return false;
    }

    boolean charTyped(CharacterEvent event) {
        return name.isFocused() && name.charTyped(event);
    }
}
