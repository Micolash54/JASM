package dev.micolash.jasm.client;

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
 * The Relay Chatterbox screen, plain for now: the frequency with its - and + keys, a box to type one and the Clear
 * frequency key; the Link Byteling slot on the left, the Dimension Upgrade slot on the right; then what the operator
 * says about the pair, in a recessed section two lines tall; then the inventory. With no Link Byteling seated the
 * section stays empty. The server checks every change and quietly drops any it won't allow.
 *
 * <p>Layout, in GUI pixels: 176 wide, title on row 6, frequency keys 20 to 36, slots 44 to 60, section 66 to 92 with its
 * lines wrapped to 154 pixels, inventory label 93, inventory from 104.
 */
public class RelayChatterboxScreen extends JasmScreen<RelayChatterboxMenu> {
    private static final int WIDTH = RelayChatterboxMenu.WIDTH;
    private static final int HEIGHT = RelayChatterboxMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int KEY = 16;
    private static final int FIELD_X = 8 + KEY + 2;
    private static final int FIELD_WIDTH = 30;
    private static final int PLUS_X = FIELD_X + FIELD_WIDTH + 2;
    private static final int CLEAR_X = PLUS_X + KEY + 6;
    private static final int SECTION_X = 7;
    private static final int SECTION_Y = 66;
    private static final int SECTION_WIDTH = WIDTH - 2 * SECTION_X;
    private static final int SECTION_PAD = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int SECTION_LINES = 2;
    private static final int SECTION_HEIGHT = SECTION_LINES * LINE_HEIGHT + 2 * SECTION_PAD - 2;
    private static final int TEXT_WIDTH = SECTION_WIDTH - 2 * SECTION_PAD;

    private JasmField field;
    private JasmButton minus;
    private JasmButton plus;
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
        clear = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.relay_chatterbox.clear"), b -> send(RelayEnd.NO_FREQUENCY),
                leftPos + CLEAR_X, y, WIDTH - 8 - CLEAR_X, KEY));
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

    /** Enter sends the number typed; an empty box changes nothing. */
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
        JasmGui.inset(graphics, leftPos + SECTION_X, topPos + SECTION_Y, SECTION_WIDTH, SECTION_HEIGHT);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        int labelY = RelayChatterboxMenu.SLOTS_Y + 4;
        graphics.text(font, Component.translatable("screen.jasm.relay_chatterbox.operator"), RelayChatterboxMenu.OPERATOR_X + 20, labelY,
                JasmGui.SUBTEXT, false);
        Component upgrade = Component.translatable("screen.jasm.relay_chatterbox.upgrade");
        graphics.text(font, upgrade, RelayChatterboxMenu.UPGRADE_X - 4 - font.width(upgrade), labelY, JasmGui.SUBTEXT, false);
        if (menu.operator().isEmpty()) return;
        RelayStatus status = menu.status();
        List<FormattedCharSequence> parts = font.split(Component.translatable(line(status)), TEXT_WIDTH);
        for (int i = 0; i < Math.min(SECTION_LINES, parts.size()); i++) {
            FormattedCharSequence part = parts.get(i);
            graphics.text(font, part, (imageWidth - font.width(part)) / 2, SECTION_Y + SECTION_PAD + i * LINE_HEIGHT, colour(status), false);
        }
    }

    /** What the operator says. */
    private static String line(RelayStatus status) {
        return "screen.jasm.relay_chatterbox.status." + status.name().toLowerCase(Locale.ROOT);
    }

    /** White when linked or with no frequency, yellow when linked with one side unpowered, red when not linked. */
    private static int colour(RelayStatus status) {
        return switch (status) {
            case LINKED, NO_FREQUENCY -> JasmGui.TEXT;
            case LINKED_ONE_SIDE -> JasmGui.WARN;
            default -> JasmGui.BAD;
        };
    }
}
