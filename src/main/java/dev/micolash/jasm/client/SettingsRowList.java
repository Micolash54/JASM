package dev.micolash.jasm.client;

import dev.micolash.jasm.config.Setting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * The right-hand side of the settings screen: one row for each setting of the open section, with its name, its control and
 * what it does in plain words. Rows are as tall as their words need. On a wide window the control sits beside the words;
 * on a narrow one it moves under the name, so nothing ever overlaps.
 */
final class SettingsRowList extends SettingsPane<SettingsRowList.Row> {
    private static final int PEACH = 0xFFFAB387;
    private static final int CONTROL_HEIGHT = 14;
    private static final int RESET_WIDTH = 30;
    private static final int LIST_BOX = 46;
    private static final int GAP = 3;
    /** The least room the words beside a control are given; with less, the control goes under the name. */
    private static final int MIN_WORDS = 110;
    private static final int LINE = 10;

    private final JasmSettingsScreen screen;
    private final Font font;

    SettingsRowList(Minecraft minecraft, JasmSettingsScreen screen, int x, int y, int width, int height) {
        super(minecraft, x, y, width, height, 36);
        this.screen = screen;
        this.font = minecraft.font;
    }

    /** Fills the list with these notes (words above the settings) and settings, keeping the scroll place when asked. */
    void show(List<Component> notes, boolean warn, List<Setting> settings, boolean keepScroll) {
        double scroll = keepScroll ? scrollAmount() : 0;
        clearEntries();
        boolean first = true;
        for (Component note : notes) {
            Note row = new Note(note, warn && first);
            addEntry(row, row.height());
            first = false;
        }
        for (Setting setting : settings) {
            SettingRow row = new SettingRow(setting, getRowWidth());
            addEntry(row, row.height);
        }
        setScrollAmount(scroll);
    }

    /** Re-reads every row's reset key and colours after a value changed somewhere else (a preset filling in numbers). */
    void refreshStates() {
        for (Row row : children()) row.refresh();
    }

    abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        void refresh() {}
    }

    /** Words between the settings: what the section does with a change, or why it can't be changed. */
    private final class Note extends Row {
        private final List<FormattedCharSequence> lines;
        private final boolean warn;

        Note(Component text, boolean warn) {
            this.lines = font.split(text, getRowWidth() - 8);
            this.warn = warn;
        }

        int height() {
            return lines.size() * LINE + 8;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            if (warn) graphics.fill(getX(), getY() + 2, getX() + getWidth(), getY() + getHeight() - 2, 0x33F9E2AF);
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(font, lines.get(i), getX() + 4, getY() + 5 + i * LINE, warn ? JasmGui.WARN : JasmGui.SUBTEXT, false);
            }
        }
    }

    private final class SettingRow extends Row {
        private final Setting setting;
        private final List<FormattedCharSequence> nameLines;
        private final List<FormattedCharSequence> description;
        private final List<FormattedCharSequence> meta;
        private final Component restartMark = Component.translatable("screen.jasm.settings.restart");
        private final List<AbstractWidget> widgets = new ArrayList<>();
        /** Where each widget sits, from the row's top left corner. */
        private final List<int[]> spots = new ArrayList<>();
        private final List<SettingsWidgets.Field> fields = new ArrayList<>();
        /** The small index numbers above a list's boxes: {x, y, index}. */
        private final List<int[]> labels = new ArrayList<>();
        private final Component name;
        private final JasmButton reset;
        private final boolean locked;
        private final int rowWidth;
        private final int textTop;
        private final int height;

        SettingRow(Setting setting, int rowWidth) {
            this.setting = setting;
            this.rowWidth = rowWidth;
            this.locked = screen.locked(setting);
            this.name = label("jasm.configuration." + setting.key(), setting.key());
            Component tip = label("jasm.configuration." + setting.key() + ".tooltip", "");
            this.reset = JasmButton.smallText(Component.translatable("screen.jasm.settings.reset"), b -> screen.reset(setting), 0, 0, RESET_WIDTH,
                    CONTROL_HEIGHT);
            int markRoom = setting.needsRestart() ? JasmGui.smallWidth(font, restartMark) + 10 : 0;
            int fullWidth = rowWidth - 2 * PAD;
            int nameBottom;
            int controlBottom;
            int textWidth;
            int textStart;
            if (setting.isList()) {
                // Add, remove and reset sit at the end of the name line; the boxes fill the lines below.
                int keys = RESET_WIDTH + 2 * (GAP + CONTROL_HEIGHT);
                int x = rowWidth - PAD - RESET_WIDTH;
                place(reset, x, 2);
                List<?> values = (List<?>) screen.value(setting);
                JasmButton add = JasmButton.text(Component.literal("+"), b -> screen.resizeList(setting, 1), 0, 0, CONTROL_HEIGHT, CONTROL_HEIGHT);
                JasmButton remove = JasmButton.text(Component.literal("-"), b -> screen.resizeList(setting, -1), 0, 0, CONTROL_HEIGHT, CONTROL_HEIGHT);
                add.setTooltip(Tooltip.create(Component.translatable("screen.jasm.settings.list_add")));
                remove.setTooltip(Tooltip.create(Component.translatable("screen.jasm.settings.list_remove")));
                add.active = !locked && values.size() < Setting.MAX_LIST;
                remove.active = !locked && values.size() > 1;
                place(add, x - GAP - CONTROL_HEIGHT, 2);
                place(remove, x - 2 * (GAP + CONTROL_HEIGHT), 2);
                this.nameLines = font.split(name, fullWidth - keys - GAP - markRoom);
                nameBottom = 5 + nameLines.size() * LINE;
                int perLine = Math.max(1, (fullWidth + GAP) / (LIST_BOX + GAP));
                int y = Math.max(16, nameBottom) + 1;
                for (int i = 0; i < values.size(); i++) {
                    int column = i % perLine;
                    if (column == 0 && i > 0) y += 25;
                    SettingsWidgets.Field box = new SettingsWidgets.Field(font, LIST_BOX, name);
                    box.setMaxLength(12);
                    box.setValue(setting.format(values.get(i)));
                    box.setFilter(JasmSettingsScreen::numberText);
                    box.setEditable(!locked);
                    box.active = !locked;
                    place(box, PAD + column * (LIST_BOX + GAP), y + 9);
                    fields.add(box);
                    box.setResponder(text -> typed());
                    labels.add(new int[]{PAD + column * (LIST_BOX + GAP) + 2, y, i});
                }
                controlBottom = y + 9 + CONTROL_HEIGHT;
                textStart = controlBottom + 3;
                textWidth = fullWidth;
            } else {
                int controlWidth = switch (setting.kind()) {
                    case SWITCH -> SettingsWidgets.Switch.WIDTH;
                    case CHOICE -> 84;
                    default -> sliderSteps() > 0 ? SettingsWidgets.Slider.WIDTH : 76;
                };
                int free = fullWidth - controlWidth - GAP - RESET_WIDTH - 6;
                AbstractWidget control = makeControl(controlWidth);
                if (free >= MIN_WORDS) {
                    int controlX = rowWidth - PAD - controlWidth;
                    place(reset, controlX - GAP - RESET_WIDTH, 2);
                    place(control, controlX, 2);
                    this.nameLines = font.split(name, free - markRoom);
                    nameBottom = 5 + nameLines.size() * LINE;
                    controlBottom = 2 + CONTROL_HEIGHT;
                    textStart = nameBottom + 1;
                    textWidth = free;
                } else {
                    this.nameLines = font.split(name, fullWidth - markRoom);
                    nameBottom = 5 + nameLines.size() * LINE;
                    int controlY = nameBottom + 1;
                    place(control, PAD, controlY);
                    place(reset, PAD + controlWidth + GAP, controlY);
                    controlBottom = controlY + CONTROL_HEIGHT;
                    textStart = controlBottom + 3;
                    textWidth = fullWidth;
                }
            }
            this.description = tip.getString().isEmpty() ? List.of() : font.split(tip, textWidth);
            this.meta = font.split(metaLine(), textWidth);
            this.textTop = textStart;
            this.height = Math.max(controlBottom, textStart + (description.size() + meta.size()) * LINE) + 8;
            refresh();
        }

        private void place(AbstractWidget widget, int x, int y) {
            widgets.add(widget);
            spots.add(new int[]{x, y});
        }

        /** How many steps a slider for this setting has, or 0 when it gets a box instead. */
        private int sliderSteps() {
            Number min = setting.min();
            Number max = setting.max();
            if (min == null || max == null) return 0;
            if (setting.kind() == Setting.Kind.WHOLE && max.intValue() - min.intValue() <= 128) return max.intValue() - min.intValue();
            if (setting.kind() == Setting.Kind.DECIMAL && min.doubleValue() == 0.0 && max.doubleValue() == 1.0) return 100;
            return 0;
        }

        private AbstractWidget makeControl(int width) {
            Object now = screen.value(setting);
            AbstractWidget control;
            switch (setting.kind()) {
                case SWITCH -> control = new SettingsWidgets.Switch(name, () -> (Boolean) screen.value(setting), on -> {
                    screen.set(setting, on);
                    refresh();
                });
                case CHOICE -> control = JasmButton.text(choiceName(now), b -> {
                    List<Enum<?>> all = setting.choices();
                    Enum<?> next = all.get((all.indexOf((Enum<?>) screen.value(setting)) + 1) % all.size());
                    screen.set(setting, next);
                    b.setMessage(choiceName(next));
                    refresh();
                }, 0, 0, width, CONTROL_HEIGHT);
                default -> {
                    int steps = sliderSteps();
                    if (steps > 0 && setting.kind() == Setting.Kind.WHOLE) {
                        int min = setting.min().intValue();
                        control = new SettingsWidgets.Slider(name, steps, (Integer) now - min, i -> Integer.toString(min + i), i -> {
                            screen.set(setting, min + i);
                            refresh();
                        });
                    } else if (steps > 0) {
                        control = new SettingsWidgets.Slider(name, steps, (int) Math.round((Double) now * 100),
                                i -> String.format(Locale.ROOT, "%.2f", i / 100.0), i -> {
                                    screen.set(setting, i / 100.0);
                                    refresh();
                                });
                    } else {
                        SettingsWidgets.Field box = new SettingsWidgets.Field(font, width, name);
                        box.setMaxLength(14);
                        box.setValue(setting.format(now));
                        box.setFilter(JasmSettingsScreen::numberText);
                        box.setResponder(text -> typed());
                        fields.add(box);
                        control = box;
                    }
                }
            }
            if (locked) {
                control.active = false;
                if (control instanceof SettingsWidgets.Field f) f.setEditable(false);
            }
            return control;
        }

        private Component choiceName(Object choice) {
            return choice instanceof TranslatableEnum t ? t.getTranslatedName() : Component.literal(String.valueOf(choice));
        }

        /** Something was typed in a box: a value the setting accepts is kept, anything else turns the box red and blocks Done. */
        private void typed() {
            List<String> texts = new ArrayList<>();
            boolean allGood = true;
            for (SettingsWidgets.Field f : fields) {
                boolean good = setting.parse(f.getValue()) != null;
                f.setBad(!good);
                allGood &= good;
                texts.add(f.getValue());
            }
            Object whole = allGood ? setting.parse(String.join(",", texts)) : null;
            if (allGood && whole == null) {
                // Every entry is fine on its own, but the list as a whole isn't.
                for (SettingsWidgets.Field f : fields) f.setBad(true);
            }
            if (whole != null) {
                screen.set(setting, whole);
                screen.markInvalid(setting, false);
            } else {
                screen.markInvalid(setting, true);
            }
            refresh();
        }

        @Override
        void refresh() {
            boolean changed = screen.changed(setting);
            reset.visible = changed && !locked;
            reset.setTooltip(Tooltip.create(Component.translatable("screen.jasm.settings.reset_tip", defaultText())));
            for (SettingsWidgets.Field f : fields) f.setTextColor(changed ? JasmGui.WARN : JasmGui.TEXT);
        }

        private Component defaultText() {
            Object def = setting.defaultValue();
            if (def instanceof Boolean on) return Component.translatable(on ? "screen.jasm.settings.on" : "screen.jasm.settings.off");
            if (def instanceof Enum<?>) return choiceName(def);
            if (def instanceof Integer n) return Component.literal(String.format(Locale.ROOT, "%,d", n));
            if (def instanceof List<?> list) {
                List<String> parts = new ArrayList<>();
                for (Object o : list) {
                    parts.add(o instanceof Integer n ? String.format(Locale.ROOT, "%,d", n) : setting.format(o));
                }
                return Component.literal(String.join(", ", parts));
            }
            return Component.literal(setting.format(def));
        }

        private Component metaLine() {
            Number min = setting.min();
            Number max = setting.max();
            if (setting.kind() == Setting.Kind.WHOLE && min != null && max != null) {
                return Component.translatable("screen.jasm.settings.range", String.format(Locale.ROOT, "%,d", min.intValue()),
                        String.format(Locale.ROOT, "%,d", max.intValue()), defaultText());
            }
            if (setting.kind() == Setting.Kind.DECIMAL && min != null && max != null) {
                return Component.translatable("screen.jasm.settings.range", setting.format(min.doubleValue()), setting.format(max.doubleValue()),
                        defaultText());
            }
            return Component.translatable("screen.jasm.settings.default", defaultText());
        }

        private Component label(String key, String fallback) {
            return I18n.exists(key) ? Component.translatable(key) : Component.literal(fallback);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int left = getX();
            int top = getY();
            int nameColor = locked ? JasmGui.SUBTEXT : screen.changed(setting) ? JasmGui.WARN : JasmGui.TEXT;
            for (int i = 0; i < nameLines.size(); i++) {
                graphics.text(font, nameLines.get(i), left + PAD, top + 5 + i * LINE, nameColor, false);
            }
            if (setting.needsRestart() && !nameLines.isEmpty()) {
                int markX = left + PAD + font.width(nameLines.get(0)) + 6;
                JasmGui.smallText(graphics, font, restartMark, markX, top + 6, PEACH);
                int markWidth = JasmGui.smallWidth(font, restartMark);
                if (mouseX >= markX - 2 && mouseX < markX + markWidth + 2 && mouseY >= top + 3 && mouseY < top + 14) {
                    graphics.setTooltipForNextFrame(font, screen.restartTip(), mouseX, mouseY);
                }
            }
            for (int[] label : labels) {
                JasmGui.smallText(graphics, font, Component.literal(Integer.toString(label[2])), left + label[0], top + label[1] + 1, JasmGui.MUTED);
            }
            for (int i = 0; i < widgets.size(); i++) {
                AbstractWidget widget = widgets.get(i);
                widget.setX(left + spots.get(i)[0]);
                widget.setY(top + spots.get(i)[1]);
                widget.extractRenderState(graphics, mouseX, mouseY, a);
            }
            int y = top + textTop;
            for (FormattedCharSequence line : description) {
                graphics.text(font, line, left + PAD, y, JasmGui.SUBTEXT, false);
                y += LINE;
            }
            for (FormattedCharSequence line : meta) {
                graphics.text(font, line, left + PAD, y, JasmGui.MUTED, false);
                y += LINE;
            }
            JasmGui.divider(graphics, left, top + getHeight() - 2, rowWidth);
        }
    }
}
