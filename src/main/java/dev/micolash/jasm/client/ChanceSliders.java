package dev.micolash.jasm.client;

import java.util.Locale;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen.Context;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen.Element;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Gives every setting that is a number from 0 to 1 (the chances) a slider in the settings screen, in steps of 0.01.
 * NeoForge shows those as a text box. The slider writes into that box, so undo, reset and saving work as before.
 */
public final class ChanceSliders {
    private ChanceSliders() {}

    public static Element filter(Context context, String key, Element original) {
        if (!(original.widget() instanceof EditBox box)
                || !(context.valueSpecs().get(key) instanceof ModConfigSpec.ValueSpec spec)
                || !(spec.getRange() instanceof ModConfigSpec.Range<?> range)) {
            return original;
        }
        if ((key.equals("batteryMaxBlocks") || key.equals("batteryCapacityBonus"))
                && range.getMin() instanceof Integer min && range.getMax() instanceof Integer max) {
            try {
                IntegerSlider slider = new IntegerSlider(box, Integer.parseInt(box.getValue()), min, max,
                        key.equals("batteryCapacityBonus"));
                slider.setTooltip(Tooltip.create(original.tooltip()));
                return new Element(original.name(), original.tooltip(), slider);
            } catch (NumberFormatException e) {
                return original;
            }
        }
        if (!(range.getMin() instanceof Double low) || !(range.getMax() instanceof Double high) || low != 0.0 || high != 1.0) {
            return original;
        }
        double now;
        try {
            now = Double.parseDouble(box.getValue());
        } catch (NumberFormatException e) {
            return original;
        }
        Slider slider = new Slider(box, now);
        slider.setTooltip(Tooltip.create(original.tooltip()));
        return new Element(original.name(), original.tooltip(), slider);
    }

    private static double snap(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static final class IntegerSlider extends AbstractSliderButton {
        private final EditBox box;
        private final int min;
        private final int max;
        private final boolean percent;

        IntegerSlider(EditBox box, int current, int min, int max, boolean percent) {
            super(0, 0, Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, Component.empty(),
                    Math.clamp((current - min) / (double) (max - min), 0.0, 1.0));
            this.box = box;
            this.min = min;
            this.max = max;
            this.percent = percent;
            updateMessage();
        }

        private int number() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(number() + (percent ? "%" : "")));
        }

        @Override
        protected void applyValue() {
            int number = number();
            value = (number - min) / (double) (max - min);
            box.setValue(Integer.toString(number));
        }
    }

    private static final class Slider extends AbstractSliderButton {
        private final EditBox box;

        Slider(EditBox box, double value) {
            super(0, 0, Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, Component.empty(), snap(value));
            this.box = box;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format(Locale.ROOT, "%.2f", snap(value))));
        }

        @Override
        protected void applyValue() {
            value = snap(value);
            box.setValue(Double.toString(value));
        }
    }
}
