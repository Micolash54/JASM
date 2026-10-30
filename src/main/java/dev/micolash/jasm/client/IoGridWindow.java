package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.FaceMode;
import dev.micolash.jasm.network.MachineBlockEntity;
import dev.micolash.jasm.network.MachineFace;
import dev.micolash.jasm.network.MachineSides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A machine's I/O grid, in a window like the Deck Link one: opened from a side key, drawn above the screen and moved by
 * dragging any empty spot of it. Six face keys stand in a plus with the back in the bottom-right corner; each shows its
 * mode by colour and, on top, the item of the block next to that side, read from the world the player sees. A click
 * steps the side to the next mode. Machines with a tank get a key under the title that switches between the items'
 * faces and the fluids'; machines with an Auto Output switch get it on a row of its own at the bottom.
 */
final class IoGridWindow {
    static final int WIDTH = 100;
    private static final int FACE = 22;
    private static final int PITCH = 25;
    /** The neighbour's item, drawn a little under its usual 16 so the key's colour shows round it. */
    private static final int ICON_SIZE = 14;
    /** A key's raised face: inside its outline, above the two shadow rows and outline of its bottom lip. */
    private static final int FACE_INSIDE = FACE - 2;
    private static final int FACE_HEIGHT = FACE - 1 - 3;
    /** Where the icon sits so it is centred on the raised face. */
    private static final int ICON_X = 1 + (FACE_INSIDE - ICON_SIZE) / 2;
    private static final int ICON_Y = 1 + (FACE_HEIGHT - ICON_SIZE) / 2;
    private static final int TOGGLE_WIDTH = 22;
    private static final int TOGGLE_HEIGHT = 12;
    private static final int GRID_X = (WIDTH - 2 * PITCH - FACE) / 2;
    private static final int SWITCH_Y = 26;
    private static final int SWITCH_HEIGHT = 16;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);
    private static final JasmButton.Icon ICON = new JasmButton.Icon(Jasm.id("icon/io_grid"), 11, 11);
    private static final Identifier NONE = Jasm.id("button");
    private static final Identifier NONE_LIT = Jasm.id("button_highlighted");
    /** Where each face sits in the plus, in columns and rows of the 3×3. */
    private static final int[][] CELLS = {{1, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}, {2, 2}};

    private final Font font;
    private final boolean fluids;
    private final ToIntFunction<MachineSides.Kind> modes;
    private final IntConsumer press;
    private final Supplier<@Nullable BlockPos> at;
    private final @Nullable BooleanSupplier autoOutput;
    /** Each side's neighbour, as last drawn: its item (empty for none) and name (null for none). */
    private final ItemStack[] icons = new ItemStack[MachineFace.values().length];
    private final @Nullable Component[] names = new Component[MachineFace.values().length];
    private final JasmButton close;
    private final @Nullable JasmButton kindSwitch;
    private @Nullable JasmButton key;
    private MachineSides.Kind kind = MachineSides.Kind.ITEMS;
    private boolean open;
    private int x;
    private int y;
    /** Where the window was picked up, from its corner; -1 while it isn't being dragged. */
    private int grabX = -1;
    private int grabY;

    /**
     * {@code modes} gives a kind's faces as the menu packs them; {@code press} sends a button's id; {@code at} says where
     * the machine stands, for its neighbours' icons. {@code autoOutput}, if given, is the Auto Output switch's state.
     */
    IoGridWindow(Font font, boolean fluids, ToIntFunction<MachineSides.Kind> modes, IntConsumer press, Supplier<@Nullable BlockPos> at,
            @Nullable BooleanSupplier autoOutput) {
        this.font = font;
        this.fluids = fluids;
        this.modes = modes;
        this.press = press;
        this.at = at;
        this.autoOutput = autoOutput;
        Arrays.fill(icons, ItemStack.EMPTY);
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11);
        kindSwitch = fluids ? JasmButton.text(kindLabel(), b -> switchKind(), 0, 0, WIDTH - 16, SWITCH_HEIGHT) : null;
    }

    /** The side key that opens and shuts the window; the screen adds it. */
    JasmButton key(int keyX, int keyY, int top) {
        Component label = Component.translatable("screen.jasm.io.title");
        key = JasmButton.icon(() -> ICON, label, b -> toggle(keyX, top), keyX, keyY, JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT);
        key.setTooltip(Tooltip.create(label));
        key.setLatched(open);
        return key;
    }

    /** "< Items >": what is being set, and that a click swaps it. */
    private Component kindLabel() {
        return Component.literal("< ")
                .append(Component.translatable(kind == MachineSides.Kind.ITEMS ? "screen.jasm.io.items" : "screen.jasm.io.fluids"))
                .append(" >");
    }

    private void switchKind() {
        kind = kind == MachineSides.Kind.ITEMS ? MachineSides.Kind.FLUIDS : MachineSides.Kind.ITEMS;
        if (kindSwitch != null) kindSwitch.setMessage(kindLabel());
    }

    boolean isOpen() {
        return open;
    }

    /** Opens the window just left of the side key at {@code keyX}, a little below the screen's top; or shuts it. */
    private void toggle(int keyX, int top) {
        if (open) {
            close();
        } else {
            open = true;
            x = keyX - 6 - WIDTH;
            // a little below the screen's top, but never past the bottom of a small window
            y = Math.max(0, Math.min(top + 40, Minecraft.getInstance().getWindow().getGuiScaledHeight() - height()));
            if (key != null) key.setLatched(true);
        }
    }

    void close() {
        open = false;
        grabX = -1;
        if (key != null) key.setLatched(false);
    }

    private int gridY() {
        return fluids ? SWITCH_Y + SWITCH_HEIGHT + 5 : 28;
    }

    private int gridBottom() {
        return gridY() + 2 * PITCH + FACE;
    }

    /** The Auto Output row's top, below its divider. */
    private int toggleRowY() {
        return gridBottom() + 13;
    }

    int height() {
        return autoOutput == null ? gridBottom() + 8 : toggleRowY() + TOGGLE_HEIGHT + 8;
    }

    private boolean overToggle(double mouseX, double mouseY) {
        if (!open || autoOutput == null) return false;
        int tx = x + WIDTH - 8 - TOGGLE_WIDTH;
        int ty = y + toggleRowY();
        return mouseX >= tx && mouseX < tx + TOGGLE_WIDTH && mouseY >= ty && mouseY < ty + TOGGLE_HEIGHT;
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height();
    }

    /** The face key under the mouse, or null. */
    private @Nullable MachineFace faceAt(double mouseX, double mouseY) {
        if (!open) return null;
        for (MachineFace face : MachineFace.values()) {
            int fx = x + GRID_X + CELLS[face.ordinal()][0] * PITCH;
            int fy = y + gridY() + CELLS[face.ordinal()][1] * PITCH;
            if (mouseX >= fx && mouseX < fx + FACE && mouseY >= fy && mouseY < fy + FACE) return face;
        }
        return null;
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!open) return;
        close.setPosition(x + WIDTH - 16, y + 5);
        if (kindSwitch != null) kindSwitch.setPosition(x + 8, y + SWITCH_Y);
        MachineFace hovered = faceAt(mouseX, mouseY);
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        JasmGui.window(graphics, x, y, WIDTH, height());
        graphics.text(font, Component.translatable("screen.jasm.io.title"), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 21, WIDTH - 8);
        int packed = modes.applyAsInt(kind);
        readNeighbours();
        for (MachineFace face : MachineFace.values()) {
            int fx = x + GRID_X + CELLS[face.ordinal()][0] * PITCH;
            int fy = y + gridY() + CELLS[face.ordinal()][1] * PITCH;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(MachineSides.unpack(packed, face), face == hovered), fx, fy, FACE, FACE);
            ItemStack icon = icons[face.ordinal()];
            if (!icon.isEmpty()) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(fx + ICON_X, fy + ICON_Y);
                graphics.pose().scale(ICON_SIZE / 16F, ICON_SIZE / 16F);
                graphics.item(icon, 0, 0);
                graphics.pose().popMatrix();
            }
        }
        boolean overToggle = overToggle(mouseX, mouseY);
        if (autoOutput != null) {
            boolean on = autoOutput.getAsBoolean();
            JasmGui.divider(graphics, x + 4, y + gridBottom() + 6, WIDTH - 8);
            graphics.text(font, Component.translatable("screen.jasm.io.auto_output"), x + 8, y + toggleRowY() + 2, JasmGui.TEXT, false);
            String sprite = "auto_output_" + (on ? "on" : "off") + (overToggle ? "_highlighted" : "");
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Jasm.id(sprite), x + WIDTH - 8 - TOGGLE_WIDTH, y + toggleRowY(),
                    TOGGLE_WIDTH, TOGGLE_HEIGHT);
        }
        close.extractRenderState(graphics, mouseX, mouseY, a);
        if (kindSwitch != null) kindSwitch.extractRenderState(graphics, mouseX, mouseY, a);
        if (hovered != null) {
            FaceMode mode = MachineSides.unpack(packed, hovered);
            Component name = Component.translatable("screen.jasm.io.face." + hovered.name().toLowerCase(Locale.ROOT));
            Component said = Component.translatable("screen.jasm.io.mode." + mode.name().toLowerCase(Locale.ROOT))
                    .withColor(color(mode) & 0xFFFFFF);
            List<FormattedCharSequence> lines = new ArrayList<>(3);
            lines.add(name.getVisualOrderText());
            lines.add(said.getVisualOrderText());
            Component neighbour = names[hovered.ordinal()];
            if (neighbour != null) lines.add(neighbour.copy().withColor(JasmGui.SUBTEXT & 0xFFFFFF).getVisualOrderText());
            graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
        } else if (overToggle && autoOutput != null) {
            boolean on = autoOutput.getAsBoolean();
            Component state = Component.translatable("screen.jasm.io.auto_output." + (on ? "on" : "off"))
                    .withColor((on ? JasmGui.GOOD : JasmGui.SUBTEXT) & 0xFFFFFF);
            Component title = Component.translatable("screen.jasm.io.auto_output").append(": ").append(state);
            Component hint = Component.translatable("screen.jasm.io.auto_output." + (on ? "on_hint" : "off_hint"))
                    .withColor(JasmGui.SUBTEXT & 0xFFFFFF);
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(title.getVisualOrderText());
            lines.addAll(font.split(hint, 160));
            graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    /**
     * What stands next to each side, from the world the player sees: its item and name, or nothing for air, a spot that
     * isn't loaded, or no machine position. A block with no item of its own (water, fire) keeps its name but no icon. A
     * cable with a thin port or acceptor on the face touching the machine shows that part, as picking it would give.
     */
    private void readNeighbours() {
        ClientLevel level = Minecraft.getInstance().level;
        BlockPos pos = at.get();
        Direction front = level != null && pos != null && level.getBlockEntity(pos) instanceof MachineBlockEntity machine
                ? machine.frontSide() : Direction.NORTH;
        for (MachineFace face : MachineFace.values()) {
            int i = face.ordinal();
            BlockPos next = pos == null ? null : pos.relative(face.toWorld(front));
            BlockState state = level == null || next == null || !level.isLoaded(next) ? null : level.getBlockState(next);
            if (state == null || state.isAir()) {
                icons[i] = ItemStack.EMPTY;
                names[i] = null;
                continue;
            }
            DataCableBlockEntity cable = level.getBlockEntity(next) instanceof DataCableBlockEntity c ? c : null;
            Direction part = cable == null ? null : cable.partFacing(pos);
            if (part != null) {
                icons[i] = cable.partItem(part);
                names[i] = icons[i].getHoverName();
                continue;
            }
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) icons[i] = ItemStack.EMPTY;
            else if (!icons[i].is(item)) icons[i] = new ItemStack(item);
            names[i] = state.getBlock().getName();
        }
    }

    private static Identifier sprite(FaceMode mode, boolean lit) {
        return switch (mode) {
            case NONE -> lit ? NONE_LIT : NONE;
            case INPUT -> Jasm.id(lit ? "io_input_highlighted" : "io_input");
            case OUTPUT -> Jasm.id(lit ? "io_output_highlighted" : "io_output");
            case BOTH -> Jasm.id(lit ? "io_both_highlighted" : "io_both");
        };
    }

    /** The colour a mode's name is written in: the colour of its key. */
    private static int color(FaceMode mode) {
        return switch (mode) {
            case NONE -> JasmGui.SUBTEXT;
            case INPUT -> JasmGui.GOOD;
            case OUTPUT -> JasmGui.BAD;
            case BOTH -> JasmGui.WARN;
        };
    }

    /** True when the click was inside the window. */
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick) || kindSwitch != null && kindSwitch.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        MachineFace face = faceAt(event.x(), event.y());
        if (overToggle(event.x(), event.y())) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            press.accept(MachineSides.AUTO_OUTPUT_BUTTON);
        } else if (face != null) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            press.accept(MachineSides.button(kind, face));
        } else if (grabbable(event.x(), event.y())) {
            grabX = (int) event.x() - x;
            grabY = (int) event.y() - y;
        }
        return true;
    }

    /** Any spot of the window that isn't a key picks it up. */
    private boolean grabbable(double mx, double my) {
        return contains(mx, my) && faceAt(mx, my) == null && !overToggle(mx, my) && !close.isMouseOver(mx, my)
                && (kindSwitch == null || !kindSwitch.isMouseOver(mx, my));
    }

    /** Moves the window with the mouse while it is held, kept on screen. */
    boolean mouseDragged(MouseButtonEvent event, int screenWidth, int screenHeight) {
        if (grabX < 0) return false;
        x = Math.clamp((int) event.x() - grabX, 0, Math.max(0, screenWidth - WIDTH));
        y = Math.clamp((int) event.y() - grabY, 0, Math.max(0, screenHeight - height()));
        return true;
    }

    boolean mouseReleased() {
        boolean held = grabX >= 0;
        grabX = -1;
        return held;
    }
}
