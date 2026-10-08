package dev.micolash.jasm.client;

import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.workshop.BitlingItem;
import dev.micolash.jasm.workshop.ChipWorkshopMenu;
import dev.micolash.jasm.workshop.WorkshopNeed;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The Workshop screen. The Workshop's own panel sits in the middle of the screen: the Single/Batch button by the
 * title, the Blank Chip slot and progress in the first column, the output grid in the second, then the player's
 * inventory. The critter's panel hangs off its left side: slot, name, status, a speech bubble, the Byteling's
 * standard/Advanced switch, then its training and battery bars. The I/O grid's key hangs off the right side.
 */
public class ChipWorkshopScreen extends JasmScreen<ChipWorkshopMenu> {
    private JasmFrame frame;
    private static final int SIDE = ChipWorkshopMenu.SIDE_WIDTH;
    private static final int MAIN_X = ChipWorkshopMenu.MAIN_X;
    /** The I/O grid's key hangs off the Workshop panel's right side. */
    private static final int KEY_X = MAIN_X + ChipWorkshopMenu.MAIN_WIDTH;
    private static final int WIDTH = KEY_X + JasmGui.SIDE_KEY_WIDTH + 3;
    /** Both panels are the same height; the critter's holds a speech bubble, a switch and two bars. */
    private static final int SIDE_HEIGHT = 178;
    private static final int HEIGHT = SIDE_HEIGHT;

    // The critter panel.
    private static final int SIDE_PAD = 6;
    private static final int SIDE_TEXT_WIDTH = SIDE - 2 * SIDE_PAD;
    private static final int NAME_Y = 7;
    private static final int STATUS_Y = 42;
    private static final int STATUS_HEIGHT = 14;
    private static final int BUBBLE_Y = STATUS_Y + STATUS_HEIGHT + 6;
    private static final int BUBBLE_PAD = 4;
    private static final int BUBBLE_LINES = 4;
    private static final int BAR_HEIGHT = 17;
    private static final int SIDE_BAR_HEIGHT = 8;
    /** Each bar has its name above it. */
    private static final int BATTERY_Y = SIDE_HEIGHT - SIDE_PAD - SIDE_BAR_HEIGHT;
    private static final int BATTERY_LABEL_Y = BATTERY_Y - 10;
    private static final int TRAINING_Y = BATTERY_LABEL_Y - 4 - SIDE_BAR_HEIGHT;
    private static final int TRAINING_LABEL_Y = TRAINING_Y - 10;
    private static final int ADVANCED_Y = TRAINING_LABEL_Y - 4 - BAR_HEIGHT;
    /** The speech bubble: light, with dark text, so it stands out from the panel. */
    private static final int BUBBLE_FILL = 0xFFCDD6F4;
    private static final int BUBBLE_EDGE = 0xFF9399B2;
    private static final int BUBBLE_TEXT = 0xFF1E1E2E;
    /** Lines in each pool of things a critter says, and how long each one stays up while it works or idles. */
    private static final int LINES_PER_POOL = 10;
    private static final int WORK_LINE_TICKS = 100;
    private static final int IDLE_LINE_TICKS = 200;

    // The Workshop panel.
    private static final int PROGRESS_X = ChipWorkshopMenu.GRID_X + 2 * 18 + 4;
    private static final int PROGRESS_WIDTH = ChipWorkshopMenu.OUTPUT_X - 6 - PROGRESS_X;
    private static final int MODE_WIDTH = 40;

    private JasmButton batchButton;
    private JasmButton advancedButton;
    private @Nullable IoGridWindow io;
    private final RandomSource random = RandomSource.create();
    private int line = -1;
    private int lineTicks;
    private @Nullable BitlingKind lineKind;
    private boolean lineWorking;

    public ChipWorkshopScreen(ChipWorkshopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.titleLabelX = MAIN_X + 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = MAIN_X + 8;
        this.inventoryLabelY = ChipWorkshopMenu.INVENTORY_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        // The Workshop's panel is centred like any other machine's; the critter's panel hangs off its left side.
        leftPos = Math.max(0, (width - ChipWorkshopMenu.MAIN_WIDTH) / 2 - MAIN_X);
        addHelp(MAIN_X + ChipWorkshopMenu.MAIN_WIDTH - 7, "items/chip-workshop.md");
        batchButton = addRenderableWidget(JasmButton.text(batchLabel(),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ChipWorkshopMenu.BUTTON_BATCH),
                leftPos + MAIN_X + ChipWorkshopMenu.MAIN_WIDTH - 8 - HELP_ROOM - MODE_WIDTH, topPos + 4, MODE_WIDTH, BAR_HEIGHT));
        advancedButton = addRenderableWidget(JasmButton.text(advancedLabel(),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ChipWorkshopMenu.BUTTON_ADVANCED),
                leftPos + SIDE_PAD, topPos + ADVANCED_Y, SIDE_TEXT_WIDTH, BAR_HEIGHT));
        if (io == null) {
            io = new IoGridWindow(font, false, kind -> menu.sides(), id -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id));
        }
        addRenderableWidget(io.key(leftPos + KEY_X, topPos + JasmGui.sideKeyY(0), topPos));
    }

    private Component batchLabel() {
        return Component.translatable(menu.batch() ? "screen.jasm.workshop.batch" : "screen.jasm.workshop.single");
    }

    private Component advancedLabel() {
        return Component.translatable(menu.advancedSelected() ? "screen.jasm.workshop.advanced" : "screen.jasm.workshop.standard");
    }

    private @Nullable BitlingItem critter() {
        return menu.critter().getItem() instanceof BitlingItem bitling ? bitling : null;
    }

    /** What the critter is up to, and the colour it is shown in. */
    private record Status(String key, int color) {}

    private Status status(@Nullable BitlingItem critter) {
        if (ClientNetworkStatus.full(menu.containerId) != null) {
            return new Status("network_full", JasmGui.BAD);
        }
        if (critter == null) {
            return new Status("empty", JasmGui.MUTED);
        }
        if (menu.napping()) {
            // Only a napping critter needs power from outside, to charge up again.
            return menu.powered() ? new Status("napping", JasmGui.WARN) : new Status("no_power", JasmGui.BAD);
        }
        if (menu.working()) {
            return new Status("working", JasmGui.GOOD);
        }
        if (WorkshopNeed.isCritter(menu.need())) {
            return new Status("waiting", JasmGui.WARN);
        }
        return menu.need() == WorkshopNeed.NO_RECIPE ? new Status("no_recipe", JasmGui.WARN) : new Status("idle", JasmGui.MUTED);
    }

    /**
     * While the screen is open the critter says something new every few seconds, never the same line twice in a row:
     * faster while it works, slower while it idles, and at once when it starts or stops working.
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        BitlingItem critter = critter();
        BitlingKind kind = critter == null ? null : critter.kind();
        boolean working = menu.working();
        int stay = working ? WORK_LINE_TICKS : IDLE_LINE_TICKS;
        if (kind != lineKind || working != lineWorking || ++lineTicks >= stay || line < 0) {
            lineKind = kind;
            lineWorking = working;
            lineTicks = 0;
            int next = random.nextInt(LINES_PER_POOL - 1);
            line = line >= 0 && next >= line ? next + 1 : next;
        }
    }

    /** What the speech bubble says, or null for no bubble: a Basic Bitling has nothing to say yet. */
    private @Nullable Component speech(BitlingItem critter) {
        if (critter.kind() == BitlingKind.BASIC) {
            return null;
        }
        if (menu.napping()) {
            return Component.translatable("screen.jasm.workshop.napping");
        }
        if (!menu.working() && WorkshopNeed.isCritter(menu.need())) {
            return Component.translatable("screen.jasm.workshop.need", ChipWorkshopNeeds.needed(menu.need()));
        }
        if (!menu.working() && menu.need() == WorkshopNeed.NO_RECIPE) {
            return Component.translatable("screen.jasm.workshop.no_recipe_line");
        }
        return Component.translatable("screen.jasm.workshop.line." + critter.kind().name().toLowerCase(Locale.ROOT)
                + (lineWorking ? ".work." : ".idle.") + (Math.max(0, line) + 1));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        batchButton.setMessage(batchLabel());
        advancedButton.setMessage(advancedLabel());
        BitlingItem critter = critter();
        advancedButton.visible = critter != null && critter.stage() == BitlingStage.BYTELING;
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        if (frame == null)
            frame = JasmFrame.rounded(new int[]{0, 0, SIDE, SIDE_HEIGHT}, new int[]{MAIN_X, 0, ChipWorkshopMenu.MAIN_WIDTH, imageHeight},
                    JasmGui.sideStrip(KEY_X, 1));
        frame.draw(graphics, x, y);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        // Level with the middle of the 2x2 grid and the output grid.
        JasmGui.bar(graphics, x + PROGRESS_X, y + ChipWorkshopMenu.GRID_Y + 14, PROGRESS_WIDTH, 6, menu.progress());

        JasmGui.inset(graphics, x + SIDE_PAD, y + STATUS_Y, SIDE_TEXT_WIDTH, STATUS_HEIGHT);
        if (critter == null) {
            return;
        }
        Component speech = speech(critter);
        if (speech != null) {
            bubble(graphics, x + SIDE_PAD, y + BUBBLE_Y, bubbleLines(speech).size());
        }
        int required = menu.required();
        double trained = required > 0 ? Math.min(1, menu.trained() / (double) required) : critter.stage() == BitlingStage.BYTELING ? 1 : 0;
        JasmGui.crystalBar(graphics, x + SIDE_PAD, y + TRAINING_Y, SIDE_TEXT_WIDTH, SIDE_BAR_HEIGHT, trained);
        JasmGui.bar(graphics, x + SIDE_PAD, y + BATTERY_Y, SIDE_TEXT_WIDTH, SIDE_BAR_HEIGHT,
                menu.battery() <= 0 ? 0 : menu.critterEnergy() / (double) menu.battery());
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realX, int realY, float a) {
        // Under the I/O window nothing else lights up or shows a tooltip.
        boolean hidden = io.contains(realX, realY);
        int mouseX = hidden ? -1000 : realX;
        int mouseY = hidden ? -1000 : realY;
        super.extractContents(graphics, mouseX, mouseY, a);
        if (io.isOpen()) {
            graphics.nextStratum();
            io.draw(graphics, realX, realY, a);
        }
        BitlingItem critter = critter();
        if (critter == null || mouseX < leftPos + SIDE_PAD || mouseX >= leftPos + SIDE_PAD + SIDE_TEXT_WIDTH) {
            return;
        }
        if (mouseY >= topPos + BATTERY_LABEL_Y && mouseY < topPos + BATTERY_Y + SIDE_BAR_HEIGHT) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.workshop.battery_tip",
                    String.format("%,d", menu.critterEnergy()), String.format("%,d", menu.battery())), mouseX, mouseY);
        } else if (mouseY >= topPos + TRAINING_LABEL_Y && mouseY < topPos + TRAINING_Y + SIDE_BAR_HEIGHT) {
            int required = menu.required();
            Component training = required > 0
                    ? Component.translatable("screen.jasm.workshop.training_percent", Math.min(100, menu.trained() * 100 / required))
                    : Component.translatable(
                            critter.stage() == BitlingStage.BYTELING ? "tooltip.jasm.bitling.fully_learned" : "screen.jasm.workshop.no_training");
            graphics.setTooltipForNextFrame(font, training, mouseX, mouseY);
        }
    }

    private List<FormattedCharSequence> bubbleLines(Component text) {
        List<FormattedCharSequence> lines = font.split(text, SIDE_TEXT_WIDTH - 2 * BUBBLE_PAD);
        return lines.subList(0, Math.min(BUBBLE_LINES, lines.size()));
    }

    /** A rounded speech bubble {@code lines} lines tall, with its tail pointing up at the status box. */
    private static void bubble(GuiGraphicsExtractor graphics, int x, int y, int lines) {
        int w = SIDE_TEXT_WIDTH;
        int h = lines * 10 + 2 * BUBBLE_PAD - 2;
        // Edge, then fill, each with its corners cut off.
        graphics.fill(x + 1, y, x + w - 1, y + h, BUBBLE_EDGE);
        graphics.fill(x, y + 1, x + w, y + h - 1, BUBBLE_EDGE);
        graphics.fill(x + 2, y + 1, x + w - 2, y + h - 1, BUBBLE_FILL);
        graphics.fill(x + 1, y + 2, x + w - 1, y + h - 2, BUBBLE_FILL);
        // The tail: a small step up on the left.
        int tail = x + 10;
        graphics.fill(tail, y - 3, tail + 1, y + 1, BUBBLE_EDGE);
        graphics.fill(tail + 1, y - 2, tail + 2, y + 1, BUBBLE_FILL);
        graphics.fill(tail + 1, y - 3, tail + 3, y - 2, BUBBLE_EDGE);
        graphics.fill(tail + 2, y - 2, tail + 3, y + 1, BUBBLE_FILL);
        graphics.fill(tail + 3, y - 2, tail + 4, y, BUBBLE_EDGE);
        graphics.fill(tail + 3, y, tail + 5, y + 1, BUBBLE_FILL);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);

        BitlingItem critter = critter();
        Status status = status(critter);
        if (status.key().equals("network_full")) {
            // The whole line, in place of "Status: ...", so the numbers fit.
            Component full = MachineStatusText.noPower(menu.containerId, "screen.jasm.workshop.status.no_power", font, SIDE_TEXT_WIDTH - 8);
            graphics.text(font, full, SIDE_PAD + 4, STATUS_Y + 3, status.color(), false);
        } else {
            Component label = Component.translatable("screen.jasm.workshop.status",
                    Component.translatable("screen.jasm.workshop.status." + status.key()).withColor(status.color()));
            graphics.text(font, label, SIDE_PAD + 4, STATUS_Y + 3, JasmGui.SUBTEXT, false);
        }
        if (critter == null) {
            centred(graphics, Component.translatable("screen.jasm.workshop.no_critter_name"), NAME_Y, JasmGui.MUTED);
            List<FormattedCharSequence> hint = font.split(Component.translatable("screen.jasm.workshop.no_critter"), SIDE_TEXT_WIDTH);
            for (int i = 0; i < hint.size(); i++) {
                graphics.text(font, hint.get(i), SIDE_PAD, BUBBLE_Y + i * 10, JasmGui.SUBTEXT, false);
            }
            return;
        }
        centred(graphics, menu.critter().getHoverName(), NAME_Y, JasmGui.TEXT);
        Component speech = speech(critter);
        if (speech != null) {
            List<FormattedCharSequence> lines = bubbleLines(speech);
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(font, lines.get(i), SIDE_PAD + BUBBLE_PAD, BUBBLE_Y + BUBBLE_PAD - 1 + i * 10, BUBBLE_TEXT, false);
            }
        }

        barLabel(graphics, Component.translatable("screen.jasm.workshop.training"), TRAINING_LABEL_Y);
        barLabel(graphics, Component.translatable("screen.jasm.workshop.battery"), BATTERY_LABEL_Y);
    }

    private void barLabel(GuiGraphicsExtractor graphics, Component name, int y) {
        graphics.text(font, name, SIDE_PAD, y, JasmGui.SUBTEXT, false);
    }

    private void centred(GuiGraphicsExtractor graphics, Component text, int y, int color) {
        List<FormattedCharSequence> lines = font.split(text, SIDE_TEXT_WIDTH);
        if (!lines.isEmpty()) {
            graphics.text(font, lines.getFirst(), (SIDE - font.width(lines.getFirst())) / 2, y, color, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (io.contains(event.x(), event.y())) return io.mouseClicked(event, doubleClick);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return io.mouseDragged(event, width, height) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return io.mouseReleased() | super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        return io.contains(x, y) || super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (io.isOpen() && event.isEscape()) {
            io.close();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected boolean hasClickedOutside(double x, double y, int left, int top) {
        return !io.contains(x, y) && super.hasClickedOutside(x, y, left, top);
    }
}
