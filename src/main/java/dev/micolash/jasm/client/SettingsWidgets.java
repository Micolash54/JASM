package dev.micolash.jasm.client;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** The controls of the settings screen that the game has no JASM-looking version of: a switch, a slider and a number box. */
final class SettingsWidgets {
    private SettingsWidgets() {}

    /** An on/off switch with its state written to the left of it: a green "On" or a grey "Off". */
    static final class Switch extends AbstractWidget {
        static final int WIDTH = 60;
        static final int HEIGHT = 14;
        private static final int TRACK_WIDTH = 32;
        private final BooleanSupplier state;
        private final Consumer<Boolean> change;
        private final Component on = Component.translatable("screen.jasm.settings.on");
        private final Component off = Component.translatable("screen.jasm.settings.off");

        Switch(Component name, BooleanSupplier state, Consumer<Boolean> change) {
            super(0, 0, WIDTH, HEIGHT, name);
            this.state = state;
            this.change = change;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            change.accept(!state.getAsBoolean());
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (isActive() && event.isSelection()) {
                change.accept(!state.getAsBoolean());
                return true;
            }
            return false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            Font font = Minecraft.getInstance().font;
            boolean isOn = state.getAsBoolean();
            Component label = isOn ? on : off;
            int color = !active ? JasmGui.MUTED : isOn ? JasmGui.GOOD : JasmGui.MUTED;
            graphics.text(font, label, getX() + WIDTH - TRACK_WIDTH - 4 - font.width(label), getY() + (HEIGHT - 8) / 2 + 1, color, false);
            JasmGui.slider(graphics, getX() + WIDTH - TRACK_WIDTH, getY() + 3, TRACK_WIDTH, 8, TRACK_WIDTH / 2, isOn ? 1.0 : 0.0,
                    active && (isHovered() || isFocused()), false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * A slider over whole steps from {@code min} to {@code max}, with the number written to the left of it. {@code shown}
     * turns a step into the text; {@code change} hears every step the player lands on.
     */
    static final class Slider extends AbstractWidget {
        static final int WIDTH = 112;
        static final int HEIGHT = 14;
        private static final int NUMBER_ROOM = 34;
        private static final int KNOB = 8;
        private final int steps;
        private final java.util.function.IntFunction<String> shown;
        private final java.util.function.IntConsumer change;
        private int step;
        private boolean dragging;

        Slider(Component name, int steps, int step, java.util.function.IntFunction<String> shown, java.util.function.IntConsumer change) {
            super(0, 0, WIDTH, HEIGHT, name);
            this.steps = Math.max(1, steps);
            this.step = Math.clamp(step, 0, this.steps);
            this.shown = shown;
            this.change = change;
        }

        private void setFromMouse(double mouseX) {
            double room = WIDTH - NUMBER_ROOM - KNOB;
            double along = (mouseX - (getX() + NUMBER_ROOM + KNOB / 2.0)) / room;
            move((int) Math.round(Math.clamp(along, 0.0, 1.0) * steps));
        }

        private void move(int to) {
            int next = Math.clamp(to, 0, steps);
            if (next != step) {
                step = next;
                change.accept(step);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            dragging = true;
            setFromMouse(event.x());
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            setFromMouse(event.x());
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            dragging = false;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (isActive() && isFocused() && (event.isLeft() || event.isRight())) {
                move(step + (event.isLeft() ? -1 : 1));
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            Font font = Minecraft.getInstance().font;
            String text = shown.apply(step);
            graphics.text(font, text, getX() + NUMBER_ROOM - 4 - font.width(text), getY() + (HEIGHT - 8) / 2 + 1,
                    active ? JasmGui.TEXT : JasmGui.MUTED, false);
            JasmGui.slider(graphics, getX() + NUMBER_ROOM, getY() + 3, WIDTH - NUMBER_ROOM, 8, KNOB, step / (double) steps,
                    active && (isHovered() || isFocused()), dragging);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** A text box that gets a red rim while what is typed in it isn't something the setting accepts. */
    static final class Field extends JasmField {
        private boolean bad;

        Field(Font font, int width, Component narration) {
            super(font, 0, 0, width, 14, narration);
        }

        void setBad(boolean bad) {
            this.bad = bad;
        }

        boolean bad() {
            return bad;
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
            if (bad && isVisible()) JasmGui.rim(graphics, getX(), getY(), getWidth(), getHeight(), JasmGui.BAD);
        }
    }
}
