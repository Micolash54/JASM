package dev.micolash.jasm.client;

import dev.micolash.jasm.relay.RelayChatterboxBlockEntity;
import dev.micolash.jasm.relay.RelayChatterboxMenu;
import dev.micolash.jasm.relay.RelayEnd;
import dev.micolash.jasm.relay.RelayPayloads;
import dev.micolash.jasm.relay.RelayStatus;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Relay Chatterbox screen: the frequency with its - and + keys, a box to type one, and the Set and Clear keys; the
 * Link Byteling slot on the left, the Dimension Upgrade slot on the right; then what the operator says about the pair,
 * in the Chip Workshop's speech bubble, its tail pointing up at the operator, coloured by how the pair stands; then the
 * inventory. With no Link Byteling seated there is no bubble. The server checks every change and quietly drops any it
 * won't allow.
 *
 * <p>Layout, in GUI pixels: 176 wide, title on row 6 with the power the Link Byteling is paying right-aligned on it,
 * frequency keys 20 to 36, slots 44 to 60, bubble from 66 (its tail from 63), one or two lines wrapped to 154 pixels, so
 * at most 66 to 92; inventory label 96, inventory from 107. Every line the operator says fits two lines at that width.
 */
public class RelayChatterboxScreen extends JasmScreen<RelayChatterboxMenu> {
    private static final int WIDTH = RelayChatterboxMenu.WIDTH;
    private static final int HEIGHT = RelayChatterboxMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int KEY = 16;
    private static final int FIELD_X = 8 + KEY + 2;
    private static final int FIELD_WIDTH = 30;
    private static final int PLUS_X = FIELD_X + FIELD_WIDTH + 2;
    private static final int SET_X = PLUS_X + KEY + 6;
    /** Set and Clear share the room right of the + key: the same size, two pixels apart. */
    private static final int SET_WIDTH = (WIDTH - 8 - SET_X - 2) / 2;
    private static final int CLEAR_X = SET_X + SET_WIDTH + 2;
    private static final int BUBBLE_X = 7;
    private static final int BUBBLE_Y = 66;
    private static final int BUBBLE_WIDTH = WIDTH - 2 * BUBBLE_X;
    private static final int BUBBLE_PAD = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BUBBLE_LINES = 2;
    private static final int TEXT_WIDTH = BUBBLE_WIDTH - 2 * BUBBLE_PAD;
    /** The text is always dark; the bubble's fill and edge follow the pair. */
    private static final int BUBBLE_TEXT = 0xFF1E1E2E;
    /** The Chip Workshop's own bubble: linked, no frequency set, or no one else on it yet. */
    private static final int CALM_FILL = 0xFFCDD6F4;
    private static final int CALM_EDGE = 0xFF9399B2;
    /** Linked, with one side unpowered. */
    private static final int WARN_FILL = 0xFFF9E2AF;
    private static final int WARN_EDGE = 0xFFB8A06E;
    /** Not linked. */
    private static final int BAD_FILL = 0xFFF5C2CB;
    private static final int BAD_EDGE = 0xFFB5868F;

    private JasmField field;
    private JasmButton minus;
    private JasmButton plus;
    private JasmButton set;
    private JasmButton clear;

    public RelayChatterboxScreen(RelayChatterboxMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelX = RelayChatterboxMenu.INVENTORY_X;
        this.inventoryLabelY = RelayChatterboxMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        int y = topPos + RelayChatterboxMenu.FREQUENCY_Y;
        minus = addRenderableWidget(JasmButton.text(Component.literal("-"), b -> step(-1), leftPos + 8, y, KEY, KEY));
        field = new JasmField(font, leftPos + FIELD_X, y, FIELD_WIDTH, KEY, Component.translatable("screen.jasm.relay_chatterbox.frequency"));
        field.setMaxLength(3);
        field.setFilter(text -> text.chars().allMatch(Character::isDigit));
        field.setHint(Component.literal("---"));
        addRenderableWidget(field);
        plus = addRenderableWidget(JasmButton.text(Component.literal("+"), b -> step(1), leftPos + PLUS_X, y, KEY, KEY));
        set = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.relay_chatterbox.set"), b -> submit(),
                leftPos + SET_X, y, SET_WIDTH, KEY));
        clear = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.relay_chatterbox.clear"), b -> send(RelayEnd.NO_FREQUENCY),
                leftPos + CLEAR_X, y, SET_WIDTH, KEY));
        showFrequency();
    }

    /** One up or down from the frequency shown; with none set, either key starts at 0. */
    private void step(int by) {
        int now = menu.frequency();
        send(now == RelayEnd.NO_FREQUENCY ? 0 : Math.clamp(now + by, 0, RelayEnd.MAX_FREQUENCY));
    }

    private void send(int frequency) {
        if (frequency != menu.frequency()) {
            ClientPacketDistributor.sendToServer(new RelayPayloads.SetFrequency(menu.containerId, menu.frequency(), frequency));
        }
    }

    /** Set or Enter sends the number typed; an empty box changes nothing. */
    private void submit() {
        String text = field.getValue();
        if (!text.isEmpty()) send(Integer.parseInt(text));
        field.setFocused(false);
        setFocused(null);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        showFrequency();
    }

    /** The box shows the frequency while nobody types in it; the keys are pressable only when they would change it. */
    private void showFrequency() {
        int now = menu.frequency();
        if (!field.isFocused()) {
            field.setValue(now == RelayEnd.NO_FREQUENCY ? "" : String.format(Locale.ROOT, "%03d", now));
        }
        minus.active = now != 0;
        plus.active = now != RelayEnd.MAX_FREQUENCY;
        String typed = field.getValue();
        set.active = !typed.isEmpty() && Integer.parseInt(typed) != now;
        clear.active = now != RelayEnd.NO_FREQUENCY;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (field.isFocused() && !event.isEscape()) {
            if (event.isConfirmation()) {
                submit();
            } else {
                field.keyPressed(event);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
        if (!menu.operator().isEmpty()) {
            RelayStatus status = menu.status();
            bubble(graphics, leftPos + BUBBLE_X, topPos + BUBBLE_Y, speech().size(), fill(status), edge(status));
        }
    }

    /** The operator's line, wrapped, at most {@link #BUBBLE_LINES} lines. */
    private List<FormattedCharSequence> speech() {
        List<FormattedCharSequence> lines = font.split(Component.translatable(line(menu.status())), TEXT_WIDTH);
        return lines.subList(0, Math.min(BUBBLE_LINES, lines.size()));
    }

    /** The Chip Workshop's speech bubble, {@code lines} lines tall, its tail pointing up at the operator slot. */
    private static void bubble(GuiGraphicsExtractor graphics, int x, int y, int lines, int fill, int edge) {
        int w = BUBBLE_WIDTH;
        int h = lines * LINE_HEIGHT + 2 * BUBBLE_PAD - 2;
        // Edge, then fill, each with its corners cut off.
        graphics.fill(x + 1, y, x + w - 1, y + h, edge);
        graphics.fill(x, y + 1, x + w, y + h - 1, edge);
        graphics.fill(x + 2, y + 1, x + w - 2, y + h - 1, fill);
        graphics.fill(x + 1, y + 2, x + w - 1, y + h - 2, fill);
        // The tail: a small step up on the left.
        int tail = x + 10;
        graphics.fill(tail, y - 3, tail + 1, y + 1, edge);
        graphics.fill(tail + 1, y - 2, tail + 2, y + 1, fill);
        graphics.fill(tail + 1, y - 3, tail + 3, y - 2, edge);
        graphics.fill(tail + 2, y - 2, tail + 3, y + 1, fill);
        graphics.fill(tail + 3, y - 2, tail + 4, y, edge);
        graphics.fill(tail + 3, y, tail + 5, y + 1, fill);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        Component power = Component.translatable("screen.jasm.relay_chatterbox.power", menu.powerUse());
        JasmGui.smallText(graphics, font, power, imageWidth - 8 - JasmGui.smallWidth(font, power), titleLabelY + 1, JasmGui.SUBTEXT);
        graphics.text(font, Component.translatable("screen.jasm.relay_chatterbox.operator"), RelayChatterboxMenu.OPERATOR_X + 20,
                RelayChatterboxMenu.SLOTS_Y + 4, JasmGui.SUBTEXT, false);
        if (menu.operator().isEmpty()) return;
        List<FormattedCharSequence> parts = speech();
        for (int i = 0; i < parts.size(); i++) {
            FormattedCharSequence part = parts.get(i);
            graphics.text(font, part, (imageWidth - font.width(part)) / 2, BUBBLE_Y + BUBBLE_PAD - 1 + i * LINE_HEIGHT, BUBBLE_TEXT, false);
        }
    }

    /** The empty upgrade slot names what goes in it; with an item in, the item's own tooltip shows. */
    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (hoveredSlot != null && hoveredSlot.index == RelayChatterboxBlockEntity.UPGRADE && !hoveredSlot.hasItem()) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.relay_chatterbox.upgrade"), mouseX, mouseY);
        }
    }

    /** What the operator says. */
    private static String line(RelayStatus status) {
        return "screen.jasm.relay_chatterbox.status." + status.name().toLowerCase(Locale.ROOT);
    }

    /** Lavender when linked, with no frequency or no one else on it yet; yellow when linked with one side unpowered; red otherwise. */
    private static int fill(RelayStatus status) {
        return switch (status) {
            case LINKED, NO_FREQUENCY, NO_PARTNER -> CALM_FILL;
            case LINKED_ONE_SIDE -> WARN_FILL;
            default -> BAD_FILL;
        };
    }

    private static int edge(RelayStatus status) {
        return switch (status) {
            case LINKED, NO_FREQUENCY, NO_PARTNER -> CALM_EDGE;
            case LINKED_ONE_SIDE -> WARN_EDGE;
            default -> BAD_EDGE;
        };
    }
}
