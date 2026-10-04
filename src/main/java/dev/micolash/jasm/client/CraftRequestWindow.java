package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The window a Crafting Deck opens to request a craft, laid over the whole main panel: how many, which server runs
 * it, and (worked out by the server) a scrollable list of what it makes, takes, crafts on the way and is missing.
 */
final class CraftRequestWindow {
    /** Darker than the Deck underneath, so the window stands out, but lighter than the list's frame so its edges show. */
    private static final int BACKGROUND = 0xFF2A2B3D;
    private static final int SCROLL_GAP = 3;
    private static final int LIST_Y = 62;
    private static final int ROW = 18;
    private static final int SCROLL_WIDTH = 10;
    private static final int HANDLE_HEIGHT = 15;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);

    private final DeckMenu menu;
    private final Font font;
    private final EditBox amount;
    private final List<Placed> buttons = new ArrayList<>();
    private final Button craft;
    private final Button next;
    private @Nullable ItemResource target;
    private @Nullable BlockPos wanted;
    private int x;
    private int y;
    private int width;
    private int height;
    private int scroll;
    private boolean draggingHandle;
    /** Ticks until the server is asked again, after the amount changed; 0 when nothing is waiting. */
    private int askIn;

    /** A button and where it sits: from the window's left (or right) edge, and from its top (or bottom, when negative). */
    private record Placed(Button button, int x, int y, boolean fromRight) {}

    /** One line of the list: a section title, or an item with its count. */
    private record Line(@Nullable Component title, @Nullable ItemResource item, long count, int color) {}

    CraftRequestWindow(DeckMenu menu, Font font) {
        this.menu = menu;
        this.font = font;
        amount = new JasmField(font, 0, 0, 50, 12, Component.translatable("screen.jasm.craft.amount"));
        amount.setMaxLength(7);
        amount.setResponder(s -> askIn = 6);
        place(JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11), 16, 5, true);
        place(JasmButton.text(Component.literal("-"), b -> step(-1), 0, 0, 15, 17), 58, 24, false);
        place(JasmButton.text(Component.literal("+"), b -> step(1), 0, 0, 15, 17), 127, 24, false);
        next = place(JasmButton.text(Component.translatable("screen.jasm.craft.next_server"), b -> nextServer(), 0, 0, 34, 17), 41, 42, true);
        craft = place(JasmButton.text(Component.translatable("screen.jasm.craft.start"), b -> start(), 0, 0, 44, 17), 51, -23, true);
    }

    private Button place(Button button, int px, int py, boolean fromRight) {
        buttons.add(new Placed(button, px, py, fromRight));
        return button;
    }

    boolean isOpen() {
        return target != null;
    }

    void open(ItemResource item, int left, int top, int w, int h) {
        target = item;
        wanted = null;
        x = left;
        y = top;
        width = w;
        height = h;
        scroll = 0;
        amount.setValue("1");
        amount.setFocused(true);
        ask();
    }

    void close() {
        target = null;
        amount.setFocused(false);
    }

    boolean contains(double mouseX, double mouseY) {
        return isOpen() && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    Optional<Rect2i> area() {
        return isOpen() ? Optional.of(new Rect2i(x, y, width + 3, height + 3)) : Optional.empty();
    }

    private long amount() {
        try {
            return Math.max(1, Long.parseLong(amount.getValue()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Minus and plus: 1 at a time, 16 with shift held. */
    private void step(int sign) {
        long by = Minecraft.getInstance().hasShiftDown() ? 16 : 1;
        amount.setValue(String.valueOf(Math.max(1, amount() + sign * by)));
    }

    void tick() {
        if (askIn > 0 && --askIn == 0) {
            ask();
        }
    }

    private void ask() {
        if (target != null) {
            ClientPacketDistributor.sendToServer(new CraftPayloads.Ask(menu.containerId, target, amount(), Optional.ofNullable(wanted)));
        }
    }

    /** The server's answer, if it is about what the window shows now. */
    private CraftPayloads.@Nullable Answer answer() {
        CraftPayloads.Answer answer = menu.view().answer();
        return answer != null && target != null && answer.target().equals(target) && answer.amount() == amount() ? answer : null;
    }

    private void nextServer() {
        CraftPayloads.Answer answer = answer();
        if (answer == null || answer.servers().isEmpty()) {
            return;
        }
        List<CraftPayloads.ServerView> servers = answer.servers();
        int from = Math.max(0, answer.chosen());
        for (int i = 1; i <= servers.size(); i++) {
            CraftPayloads.ServerView option = servers.get((from + i) % servers.size());
            if (!option.busy() && option.fits()) {
                wanted = option.pos();
                ask();
                return;
            }
        }
    }

    private void start() {
        CraftPayloads.Answer answer = answer();
        if (answer != null && answer.problem().isEmpty() && target != null) {
            BlockPos pos = answer.chosen() >= 0 ? answer.servers().get(answer.chosen()).pos() : null;
            ClientPacketDistributor.sendToServer(new CraftPayloads.Start(menu.containerId, target, amount(), Optional.ofNullable(pos)));
            close();
        }
    }

    // --- the list ---

    private List<Line> lines(CraftPayloads.@Nullable Answer answer) {
        List<Line> lines = new ArrayList<>();
        if (answer == null) {
            lines.add(new Line(Component.translatable("screen.jasm.craft.working"), null, 0, JasmGui.MUTED));
            return lines;
        }
        if (answer.made() > 0 && target != null) {
            lines.add(new Line(Component.translatable("screen.jasm.craft.makes_title"), null, 0, JasmGui.GOOD));
            lines.add(new Line(null, target, answer.made(), JasmGui.TEXT));
        }
        section(lines, "screen.jasm.craft.takes", answer.taken(), JasmGui.SUBTEXT);
        // The last craft is the request itself; the ones before it are made on the way.
        List<DeckPayloads.Entry> first = answer.crafts().size() > 1 ? answer.crafts().subList(0, answer.crafts().size() - 1) : List.of();
        section(lines, "screen.jasm.craft.first", first, JasmGui.SUBTEXT);
        section(lines, "screen.jasm.craft.missing", answer.missing(), JasmGui.BAD);
        return lines;
    }

    private static void section(List<Line> lines, String key, List<DeckPayloads.Entry> entries, int color) {
        if (entries.isEmpty()) {
            return;
        }
        lines.add(new Line(Component.translatable(key), null, 0, color));
        for (DeckPayloads.Entry entry : entries) {
            lines.add(new Line(null, entry.key(), entry.count(), color == JasmGui.BAD ? JasmGui.BAD : JasmGui.TEXT));
        }
    }

    private int listHeight() {
        return height - LIST_Y - 46;
    }

    private int listWidth() {
        return width - 12 - SCROLL_WIDTH - SCROLL_GAP;
    }

    private int visibleRows() {
        return (listHeight() - 2) / ROW;
    }

    private int maxScroll(int lineCount) {
        return Math.max(0, lineCount - visibleRows());
    }

    // --- drawing ---

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (target == null) {
            return;
        }
        CraftPayloads.Answer answer = answer();
        craft.active = answer != null && answer.problem().isEmpty();
        next.visible = answer != null && answer.servers().stream().filter(s -> !s.busy() && s.fits()).count() > 1;
        for (Placed placed : buttons) {
            placed.button().setPosition(placed.fromRight() ? x + width - placed.x() : x + placed.x(),
                    placed.y() < 0 ? y + height + placed.y() : y + placed.y());
        }
        amount.setPosition(x + 75, y + 27);

        JasmGui.panel(graphics, x, y, width, height);
        graphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, BACKGROUND);
        ItemStack shown = target.toStack(1);
        FluidGrid.drawStack(graphics, shown, x + 6, y + 4);
        graphics.text(font, trim(shown.getHoverName().getString(), width - 44), x + 26, y + 8, JasmGui.TEXT, false);
        graphics.fill(x + 4, y + 22, x + width - 4, y + 23, JasmGui.SELECTED);
        graphics.text(font, Component.translatable(FluidMarkerItem.isMarker(shown) ? "screen.jasm.craft.amount_fluid" : "screen.jasm.craft.amount"),
                x + 7, y + 30, JasmGui.SUBTEXT, false);
        amount.extractRenderState(graphics, mouseX, mouseY, a);
        for (Placed placed : buttons) {
            placed.button().extractRenderState(graphics, mouseX, mouseY, a);
        }
        graphics.text(font, trim(serverLine(answer).getString(), next.visible ? width - 56 : width - 14), x + 7, y + 47, JasmGui.SUBTEXT, false);

        List<Line> lines = lines(answer);
        scroll = Math.clamp(scroll, 0, maxScroll(lines.size()));
        int listX = x + 6;
        int listW = listWidth();
        int listY = y + LIST_Y;
        JasmGui.inset(graphics, listX, listY, listW, listHeight());
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < visibleRows() && scroll + i < lines.size(); i++) {
            Line line = lines.get(scroll + i);
            int ly = listY + 1 + i * ROW;
            if (line.item() == null) {
                graphics.text(font, trim(line.title().getString(), listW - 8), listX + 4, ly + 5, line.color(), false);
                continue;
            }
            ItemStack stack = line.item().toStack(1);
            FluidGrid.drawStack(graphics, stack, listX + 3, ly + 1);
            String text = FluidGrid.describe(stack, line.count());
            graphics.text(font, trim(text, listW - 28), listX + 23, ly + 5, line.color(), false);
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= ly && mouseY < ly + ROW) {
                hovered = stack;
            }
        }
        JasmGui.scrollBar(graphics, listX + listW + SCROLL_GAP, listY, SCROLL_WIDTH, listHeight(), handleOffset(lines.size()), HANDLE_HEIGHT,
                maxScroll(lines.size()) > 0);

        // Why it can't start, under the list and beside the Craft button; two lines when it's long.
        if (answer != null && !answer.problem().isEmpty()) {
            List<FormattedCharSequence> wrapped = font.split(Component.translatable(answer.problem()), width - 64);
            int top = y + height - 40;
            for (int i = 0; i < Math.min(3, wrapped.size()); i++) {
                graphics.text(font, wrapped.get(i), x + 7, top + i * 9, JasmGui.BAD, false);
            }
        }
        if (!hovered.isEmpty()) {
            graphics.setTooltipForNextFrame(font, hovered.getHoverName(), mouseX, mouseY);
        }
    }

    private int handleOffset(int lineCount) {
        int travel = listHeight() - 2 - HANDLE_HEIGHT;
        int max = maxScroll(lineCount);
        return max == 0 ? 0 : Math.round(travel * scroll / (float) max);
    }

    private Component serverLine(CraftPayloads.@Nullable Answer answer) {
        if (answer == null || answer.chosen() < 0) {
            return Component.translatable("screen.jasm.craft.server_none");
        }
        CraftPayloads.ServerView server = answer.servers().get(answer.chosen());
        return Component.translatable("screen.jasm.craft.server", GridEntries.abbreviate(server.memory()), server.parallel());
    }

    private String trim(String text, int room) {
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("...")) + "...";
    }

    // --- input ---

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int barX = x + 6 + listWidth() + SCROLL_GAP;
        if (event.x() >= barX && event.x() < barX + SCROLL_WIDTH && event.y() >= y + LIST_Y && event.y() < y + LIST_Y + listHeight()) {
            draggingHandle = true;
            scrollToMouse(event.y());
            return true;
        }
        for (Placed placed : buttons) {
            if (placed.button().visible && placed.button().mouseClicked(event, doubleClick)) {
                return true;
            }
        }
        amount.setFocused(amount.isMouseOver(event.x(), event.y()));
        if (amount.isFocused()) {
            amount.mouseClicked(event, doubleClick);
        }
        return true;
    }

    /** While the scroll handle is held: follows the mouse. */
    boolean mouseDragged(MouseButtonEvent event) {
        if (!draggingHandle) {
            return false;
        }
        scrollToMouse(event.y());
        return true;
    }

    boolean mouseReleased() {
        boolean was = draggingHandle;
        draggingHandle = false;
        return was;
    }

    /** Scrolls so the handle's middle sits under the mouse. */
    private void scrollToMouse(double mouseY) {
        int max = maxScroll(lines(answer()).size());
        float travel = listHeight() - 2 - HANDLE_HEIGHT;
        float along = (float) (mouseY - (y + LIST_Y + 1) - HANDLE_HEIGHT / 2.0) / travel;
        scroll = Math.clamp(Math.round(along * max), 0, max);
    }

    boolean mouseScrolled(double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    /** Keys go to the amount box while it has focus; Enter starts the craft, Escape closes the window. */
    boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            close();
            return true;
        }
        if (event.isConfirmation()) {
            start();
            return true;
        }
        if (amount.isFocused()) {
            amount.keyPressed(event);
            return true;
        }
        return false;
    }

    /** Only digits reach the amount box. */
    boolean charTyped(CharacterEvent event) {
        if (!amount.isFocused()) {
            return false;
        }
        return !Character.isDigit(event.codepoint()) || amount.charTyped(event);
    }

    static Component hint(boolean craftTab) {
        return Component.translatable(craftTab ? "screen.jasm.craft.hint_click" : "screen.jasm.craft.hint_middle").withStyle(ChatFormatting.AQUA);
    }
}
