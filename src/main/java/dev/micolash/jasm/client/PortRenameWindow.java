package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AccessPortMenu;
import dev.micolash.jasm.autocraft.CraftPayloads;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Access Port's rename panel: a name box and a Rename key. Renaming sends the name and shuts the panel, but only
 * for a name the port takes. Opened from a side key and moved by dragging any empty spot of it.
 */
final class PortRenameWindow {
    static final int WIDTH = 150;
    static final int HEIGHT = 51;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);

    private final int containerId;
    private final Font font;
    private final Consumer<String> renamed;
    private final EditBox name;
    private final JasmButton rename;
    private final JasmButton close;
    private boolean open;
    private int x;
    private int y;
    /** Where the window was picked up, from its corner; -1 while it isn't being dragged. */
    private int grabX = -1;
    private int grabY;

    /** {@code renamed} hears the name sent, so the screen's title can follow it. */
    PortRenameWindow(int containerId, Font font, Consumer<String> renamed) {
        this.containerId = containerId;
        this.font = font;
        this.renamed = renamed;
        name = new JasmField(font, 0, 0, 86, 14, Component.translatable("screen.jasm.port.name"));
        name.setMaxLength(AccessPortMenu.MAX_NAME);
        name.setHint(Component.translatable("screen.jasm.port.name_hint"));
        rename = JasmButton.text(Component.translatable("screen.jasm.port.rename"), b -> submit(), 0, 0, 44, 17);
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11);
    }

    boolean isOpen() {
        return open;
    }

    /** Opens just left of the side key at {@code keyX}, a little below the screen's top, showing {@code current}; or shuts it. */
    void toggle(int keyX, int top, String current) {
        if (open) {
            close();
            return;
        }
        open = true;
        x = keyX - 6 - WIDTH;
        y = top + 40;
        name.setValue(current);
        name.setFocused(true);
    }

    void close() {
        open = false;
        grabX = -1;
        name.setFocused(false);
    }

    private void submit() {
        String value = name.getValue();
        if (!AccessPortMenu.validName(value)) return;
        ClientPacketDistributor.sendToServer(new CraftPayloads.PortName(containerId, value.strip()));
        renamed.accept(value.strip());
        close();
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    Optional<Rect2i> area() {
        return open ? Optional.of(new Rect2i(x, y, WIDTH + 3, HEIGHT + 3)) : Optional.empty();
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!open) return;
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        JasmGui.window(graphics, x, y, WIDTH, HEIGHT);
        graphics.text(font, Component.translatable("screen.jasm.port.rename"), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 21, WIDTH - 8);
        close.setPosition(x + WIDTH - 16, y + 5);
        name.setPosition(x + 8, y + 28);
        rename.setPosition(x + 98, y + 26);
        rename.active = AccessPortMenu.validName(name.getValue());
        close.extractRenderState(graphics, mouseX, mouseY, a);
        name.extractRenderState(graphics, mouseX, mouseY, a);
        rename.extractRenderState(graphics, mouseX, mouseY, a);
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick) || rename.mouseClicked(event, doubleClick)) return true;
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
        return contains(mx, my) && !name.isMouseOver(mx, my) && !close.isMouseOver(mx, my) && !rename.isMouseOver(mx, my);
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

    /** Escape shuts the window, Enter renames; other keys go to the name box while it is being typed in. */
    boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            close();
            return true;
        }
        if (event.isConfirmation()) {
            submit();
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
