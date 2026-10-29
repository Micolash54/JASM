package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftNetwork;
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
import net.minecraft.client.gui.components.Tooltip;
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
    private static final JasmButton.Icon MAX = new JasmButton.Icon(Jasm.id("icon/max_craft"), 7, 7);
    /** Ticks between two asks from this window: a little more than the server's own limit, so none is turned away. */
    private static final int ASK_GAP = CraftNetwork.PLAN_EVERY + 1;
    /** Ticks to wait for the Max answer before giving up on it. */
    private static final int MOST_WAIT = 40;
    /** The amount box takes 7 digits. */
    private static final long MOST_SHOWN = 9_999_999;
    /** Kept while the game is open: the list or the tree, as the player last chose. */
    private static boolean treeMode;

    private final DeckMenu menu;
    private final Font font;
    private final EditBox amount;
    private final List<Placed> buttons = new ArrayList<>();
    private final Button craft;
    private final Button next;
    private final Button view;
    private final CraftTreeView treeView = new CraftTreeView();
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
    /** Ticks the window has been ticking, and the tick it last asked the server something. */
    private int ticks;
    private int askedAt = -ASK_GAP;
    /** Max was pressed and its ask waits for the gap to pass. */
    private boolean mostWanted;
    /** Max was asked at this tick; -1 when no answer is awaited. */
    private int mostAskedAt = -1;
    /** The Deck's Max answers counted when Max was asked: a higher count is the answer. */
    private int mostSeen;
    /** How many of the Deck's jobs were running when last looked at. */
    private int jobsSeen;

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
        // Max sits right of the plus, the same size, with the same gap.
        Component maxName = Component.translatable("screen.jasm.craft.max");
        Button max = JasmButton.icon(() -> MAX, maxName, b -> askMost(), 0, 0, 15, 17);
        max.setTooltip(Tooltip.create(Component.empty().append(maxName).append("\n")
                .append(Component.translatable("screen.jasm.craft.max_hint").withStyle(ChatFormatting.GRAY))));
        place(max, 144, 24, false);
        next = place(JasmButton.text(Component.translatable("screen.jasm.craft.next_server"), b -> nextServer(), 0, 0, 34, 17), 41, 42, true);
        craft = place(JasmButton.text(Component.translatable("screen.jasm.craft.start"), b -> start(), 0, 0, 44, 17), 51, -23, true);
        view = place(JasmButton.text(viewLabel(), b -> toggleView(), 0, 0, 30, 17), 37, 24, true);
    }

    /** The button names the view you can switch to. */
    private static Component viewLabel() {
        return Component.translatable(treeMode ? "screen.jasm.craft.view_list" : "screen.jasm.craft.view_tree");
    }

    private void toggleView() {
        treeMode = !treeMode;
        view.setMessage(viewLabel());
        scroll = 0;
        if (treeMode) {
            // A little later, so a plan asked a moment ago is not turned away by the server.
            askIn = 5;
        }
    }

    private Button place(Button button, int px, int py, boolean fromRight) {
        buttons.add(new Placed(button, px, py, fromRight));
        return button;
    }

    boolean isOpen() {
        return target != null;
    }

    void open(ItemResource item, int left, int top, int w, int h) {
        open(item, 1, left, top, w, h);
    }

    void open(ItemResource item, long count, int left, int top, int w, int h) {
        target = item;
        wanted = null;
        x = left;
        y = top;
        width = w;
        height = h;
        scroll = 0;
        amount.setValue(String.valueOf(Math.max(1, count)));
        amount.setFocused(true);
        forgetMost();
        jobsSeen = menu.view().jobs().size();
        ask();
    }

    void close() {
        target = null;
        amount.setFocused(false);
        forgetMost();
    }

    /** The player changed the amount, or the window moved on: a Max answer still on its way no longer applies. */
    private void forgetMost() {
        mostWanted = false;
        mostAskedAt = -1;
    }

    /** Max: asks the server for the most it can make; the answer fills in the amount, and Craft is still the player's to press. */
    private void askMost() {
        if (target == null) {
            return;
        }
        // The answer changes the amount, which asks for the plan again: a plan asked now would only be replaced.
        askIn = 0;
        mostWanted = true;
        sendMostWhenFree();
    }

    private void sendMostWhenFree() {
        if (!mostWanted || target == null || ticks - askedAt < ASK_GAP) {
            return;
        }
        mostWanted = false;
        askedAt = ticks;
        mostAskedAt = ticks;
        mostSeen = menu.view().mostVersion();
        ClientPacketDistributor.sendToServer(new CraftPayloads.AskMost(menu.containerId, target));
    }

    /** Fills in the Max answer once it is here: the most that can be made, or 1 to show what is missing. */
    private void takeMost() {
        if (mostAskedAt < 0) {
            return;
        }
        if (menu.view().mostVersion() == mostSeen) {
            if (ticks - mostAskedAt > MOST_WAIT) {
                mostAskedAt = -1;
            }
            return;
        }
        mostAskedAt = -1;
        if (target != null && target.equals(menu.view().mostTarget())) {
            amount.setValue(String.valueOf(Math.clamp(menu.view().most(), 1, MOST_SHOWN)));
        }
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
        forgetMost();
        long by = Minecraft.getInstance().hasShiftDown() ? 16 : 1;
        amount.setValue(String.valueOf(Math.max(1, amount() + sign * by)));
    }

    void tick() {
        ticks++;
        sendMostWhenFree();
        takeMost();
        // A job starting or ending changes which servers are free, so the answer on screen is asked for again.
        int running = menu.view().jobs().size();
        if (running != jobsSeen) {
            jobsSeen = running;
            if (isOpen() && askIn == 0) {
                askIn = 5;
            }
        }
        if (askIn > 0 && --askIn == 0) {
            ask();
        }
    }

    private void ask() {
        if (target != null) {
            askedAt = ticks;
            ClientPacketDistributor.sendToServer(new CraftPayloads.Ask(menu.containerId, target, amount(), Optional.ofNullable(wanted), treeMode));
        }
    }

    /** The server's answer, if it is about what the window shows now. In tree mode an answer without a tree is an older one. */
    private CraftPayloads.@Nullable Answer answer() {
        CraftPayloads.Answer answer = menu.view().answer();
        boolean fits = answer != null && target != null && answer.target().equals(target) && answer.amount() == amount();
        return fits && (!treeMode || answer.tree().isPresent()) ? answer : null;
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
        section(lines, "screen.jasm.craft.missing", answer.missing(), JasmGui.BAD);
        section(lines, "screen.jasm.craft.takes", answer.taken(), JasmGui.SUBTEXT);
        // The last craft is the request itself; the ones before it are made on the way.
        List<DeckPayloads.Entry> first = answer.crafts().size() > 1 ? answer.crafts().subList(0, answer.crafts().size() - 1) : List.of();
        section(lines, "screen.jasm.craft.first", first, JasmGui.SUBTEXT);
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

        int listX = x + 6;
        int listY = y + LIST_Y;
        ItemStack hovered = ItemStack.EMPTY;
        if (treeMode) {
            drawTree(graphics, answer, listX, listY, mouseX, mouseY);
        } else {
            List<Line> lines = lines(answer);
            scroll = Math.clamp(scroll, 0, maxScroll(lines.size()));
            int listW = listWidth();
            JasmGui.inset(graphics, listX, listY, listW, listHeight());
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
        }

        // Why it can't start, under the list and beside the Craft button; two lines when it's long.
        if (answer != null && !answer.problem().isEmpty()) {
            List<FormattedCharSequence> wrapped = font.split(Component.translatable(answer.problem()), width - 64);
            int top = y + height - 40;
            for (int i = 0; i < Math.min(3, wrapped.size()); i++) {
                graphics.text(font, wrapped.get(i), x + 7, top + i * 9, JasmGui.BAD, false);
            }
        } else if (answer != null && answer.rounds() > 0) {
            // An item copied from itself: each round can only use the copies made before it.
            Component rounds = answer.rounds() == 1 ? Component.translatable("screen.jasm.craft.rounds_one")
                    : Component.translatable("screen.jasm.craft.rounds", answer.rounds());
            graphics.text(font, trim(rounds.getString(), width - 64), x + 7, y + height - 40, JasmGui.SUBTEXT, false);
        }
        if (!hovered.isEmpty()) {
            graphics.setTooltipForNextFrame(font, hovered.getHoverName(), mouseX, mouseY);
        }
    }

    /** The tree in the list area, or why there is none: still working, too big, or a plan that can't be made. */
    private void drawTree(GuiGraphicsExtractor graphics, CraftPayloads.@Nullable Answer answer, int listX, int listY, int mouseX, int mouseY) {
        int treeW = width - 12;
        CraftPayloads.TreeView tree = answer == null ? null : answer.tree().orElse(null);
        if (tree != null && !tree.tooBig() && !tree.boxes().isEmpty()) {
            treeView.show(tree);
            treeView.draw(graphics, font, listX, listY, treeW, listHeight(), mouseX, mouseY);
            return;
        }
        JasmGui.inset(graphics, listX, listY, treeW, listHeight());
        if (tree == null) {
            graphics.text(font, Component.translatable("screen.jasm.craft.working"), listX + 4, listY + 6, JasmGui.MUTED, false);
            return;
        }
        Component why = tree.tooBig() ? Component.translatable("screen.jasm.craft.tree_too_big")
                : answer.problem().isEmpty() ? Component.translatable("screen.jasm.craft.working") : Component.translatable(answer.problem());
        int line = 0;
        for (FormattedCharSequence wrapped : font.split(why, treeW - 8)) {
            graphics.text(font, wrapped, listX + 4, listY + 6 + line++ * 9, JasmGui.BAD, false);
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
        if (treeMode) {
            if (treeView.mouseClicked(event, x + 6, y + LIST_Y, width - 12, listHeight())) {
                return true;
            }
        } else if (event.x() >= barX && event.x() < barX + SCROLL_WIDTH && event.y() >= y + LIST_Y && event.y() < y + LIST_Y + listHeight()) {
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
        if (treeMode && treeView.mouseDragged(event)) {
            return true;
        }
        if (!draggingHandle) {
            return false;
        }
        scrollToMouse(event.y());
        return true;
    }

    boolean mouseReleased() {
        boolean was = draggingHandle | treeView.mouseReleased();
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

    boolean mouseScrolled(double scrollX, double scrollY) {
        if (treeMode) {
            return treeView.mouseScrolled(scrollX, scrollY, Minecraft.getInstance().hasShiftDown());
        }
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
            forgetMost();
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
        if (!Character.isDigit(event.codepoint())) {
            return true;
        }
        forgetMost();
        return amount.charTyped(event);
    }

    static Component hint(boolean craftTab) {
        return Component.translatable(craftTab ? "screen.jasm.craft.hint_click" : "screen.jasm.craft.hint_middle").withStyle(ChatFormatting.AQUA);
    }
}
