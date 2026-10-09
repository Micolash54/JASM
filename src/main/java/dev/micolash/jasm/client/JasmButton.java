package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A button in the JASM look. It shows either its text or, for small toggles, an icon (the text is then read aloud only).
 * It is drawn as a dark key with a lip under it. It sinks in while held and for a moment after any press, and a selected
 * button (the open tab) stays sunk in.
 */
public class JasmButton extends Button {
    private static final Identifier NORMAL = Jasm.id("button");
    private static final Identifier HIGHLIGHTED = Jasm.id("button_highlighted");
    private static final Identifier DISABLED = Jasm.id("button_disabled");
    private static final Identifier PRESSED = Jasm.id("button_pressed");
    private static final Identifier SELECTED = Jasm.id("button_selected");
    /** How long a press shows when the button is let go at once, or pressed with the keyboard. */
    private static final long FLASH_MS = 110;

    /** An icon sprite and its size. */
    public record Icon(Identifier sprite, int width, int height) {}

    private final @Nullable Supplier<Icon> icon;
    private final boolean wrap;
    private boolean small;
    private boolean held;
    private long flashUntil;
    private boolean selected;

    private JasmButton(Builder builder, @Nullable Supplier<Icon> icon, boolean wrap) {
        super(builder);
        this.icon = icon;
        this.wrap = wrap;
    }

    public static JasmButton text(Component message, OnPress onPress, int x, int y, int width, int height) {
        return (JasmButton) Button.builder(message, onPress).bounds(x, y, width, height).build(b -> new JasmButton(b, null, false));
    }

    /** A text button whose label is drawn at {@link JasmGui#SMALL_TEXT} size. */
    public static JasmButton smallText(Component message, OnPress onPress, int x, int y, int width, int height) {
        JasmButton button = text(message, onPress, x, y, width, height);
        button.small = true;
        return button;
    }

    public static JasmButton wrappedText(Component message, OnPress onPress, int x, int y, int width, int height) {
        return (JasmButton) Button.builder(message, onPress).bounds(x, y, width, height).build(b -> new JasmButton(b, null, true));
    }

    /** {@code icon} is asked again every frame, so a toggle can switch icons. */
    public static JasmButton icon(Supplier<Icon> icon, Component narration, OnPress onPress, int x, int y, int width, int height) {
        return (JasmButton) Button.builder(narration, onPress).bounds(x, y, width, height).build(b -> new JasmButton(b, icon, false));
    }

    /** Marks this as the chosen one of a group, like the open tab. A selected button can't be pressed again. */
    public void setSelected(boolean selected) {
        this.selected = selected;
        this.active = !selected;
    }

    /** Shows a switch that is on: pressed in like a selected button, but it can still be pressed to switch it off. */
    public void setLatched(boolean latched) {
        this.selected = latched;
    }

    public boolean isSelected() {
        return selected;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        held = true;
        super.onClick(event, doubleClick);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        held = false;
        super.onRelease(event);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        flashUntil = Util.getMillis() + FLASH_MS;
        super.onPress(input);
    }

    private boolean pressed() {
        // The release can go to another widget, so the real mouse button is checked too: held means still held down.
        if (held && !Minecraft.getInstance().mouseHandler.isLeftPressed()) held = false;
        return active && (held && isHovered() || Util.getMillis() < flashUntil);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // A click leaves the button focused; only show that when the player is moving around with the keyboard.
        boolean lit = isHovered() || isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
        Identifier sprite = selected ? SELECTED : pressed() ? PRESSED : !active ? DISABLED : lit ? HIGHLIGHTED : NORMAL;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
        boolean bright = active || selected;
        graphics.pose().pushMatrix();
        // The face sits above a 2px lip, so content is centred 1px high; a pressed cap drops 2px and loses the lip.
        graphics.pose().translate(0, selected || pressed() ? 1 : -1);
        if (icon != null) {
            Icon shown = icon.get();
            int ix = getX() + (getWidth() - shown.width()) / 2;
            int iy = getY() + (getHeight() - shown.height()) / 2;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, shown.sprite(), ix, iy, shown.width(), shown.height(), bright ? 1.0F : 0.5F);
        } else if (wrap) {
            var font = Minecraft.getInstance().font;
            var lines = font.split(getMessage(), getWidth() - 8);
            int y = getY() + (getHeight() - lines.size() * font.lineHeight) / 2;
            for (var line : lines) {
                graphics.text(font, line, getX() + (getWidth() - font.width(line)) / 2, y, bright ? JasmGui.TEXT : JasmGui.MUTED, false);
                y += font.lineHeight;
            }
        } else if (small) {
            var font = Minecraft.getInstance().font;
            int width = JasmGui.smallWidth(font, getMessage());
            JasmGui.smallText(graphics, font, getMessage(), getX() + (getWidth() - width + 1) / 2,
                    getY() + Math.round((getHeight() - 7 * JasmGui.SMALL_TEXT) / 2), bright ? JasmGui.TEXT : JasmGui.MUTED);
        } else {
            var font = Minecraft.getInstance().font;
            int width = font.width(getMessage());
            if (width <= getWidth() - 6) {
                // Drawn by hand so a coloured label (a green "On") keeps its colour.
                graphics.text(font, getMessage(), getX() + (getWidth() - width + 1) / 2, getY() + (getHeight() - 7) / 2,
                        bright ? JasmGui.TEXT : JasmGui.MUTED, false);
            } else {
                setFGColor(bright ? JasmGui.TEXT : JasmGui.MUTED);
                extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
            }
        }
        graphics.pose().popMatrix();
    }
}
