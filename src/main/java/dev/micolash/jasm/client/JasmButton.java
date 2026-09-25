package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** A button in the JASM look. It shows either its text or, for small toggles, an icon (the text is then read aloud only). */
public class JasmButton extends Button {
    private static final Identifier NORMAL = Jasm.id("button");
    private static final Identifier HIGHLIGHTED = Jasm.id("button_highlighted");
    private static final Identifier DISABLED = Jasm.id("button_disabled");

    /** An icon sprite and its size. */
    public record Icon(Identifier sprite, int width, int height) {}

    private final @Nullable Supplier<Icon> icon;

    private JasmButton(Builder builder, @Nullable Supplier<Icon> icon) {
        super(builder);
        this.icon = icon;
    }

    public static JasmButton text(Component message, OnPress onPress, int x, int y, int width, int height) {
        return (JasmButton) Button.builder(message, onPress).bounds(x, y, width, height).build(b -> new JasmButton(b, null));
    }

    /** {@code icon} is asked again every frame, so a toggle can switch icons. */
    public static JasmButton icon(Supplier<Icon> icon, Component narration, OnPress onPress, int x, int y, int width, int height) {
        return (JasmButton) Button.builder(narration, onPress).bounds(x, y, width, height).build(b -> new JasmButton(b, icon));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Identifier sprite = !active ? DISABLED : isHoveredOrFocused() ? HIGHLIGHTED : NORMAL;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
        if (icon != null) {
            Icon shown = icon.get();
            int ix = getX() + (getWidth() - shown.width()) / 2;
            int iy = getY() + (getHeight() - shown.height()) / 2;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, shown.sprite(), ix, iy, shown.width(), shown.height(), active ? 1.0F : 0.5F);
        } else {
            setFGColor(active ? JasmGui.TEXT : JasmGui.MUTED);
            extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }
    }
}
