package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftNetwork;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.FluidMarkerItem;
import dev.micolash.jasm.core.AmountMath;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.network.NetworkNode;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The window a Crafting Deck opens to request a craft, laid over the whole main panel: how many, which server runs
 * it, and (worked out by the server) a scrollable list of what it makes, takes, crafts on the way and is missing.
 * The server is picked from a drop-down: any free server, or one of them by name. The Deck keeps the pick for its
 * next requests.
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
    private static final Identifier DROP = Jasm.id("icon/triangle_down");
    private static final Identifier CHECK = Jasm.id("icon/check");
    private static final Identifier CORES = Jasm.id("icon/cores");
    private static final Identifier STORAGE = Jasm.id("icon/storage");
    private static final int CORES_W = 7;
    private static final int STORAGE_W = 9;
    /** The server drop-down's box, from the window's left and top; it reaches the window's right edge less this. */
    private static final int BOX_X = 58;
    private static final int BOX_Y = 42;
    private static final int BOX_H = 17;
    private static final int BOX_RIGHT = 7;
    private static final int DROP_ROW = 13;
    /** Ticks between two asks from this window: a little more than the server's own limit, so none is turned away. */
    private static final int ASK_GAP = CraftNetwork.PLAN_EVERY + 1;
    /** Ticks to wait for the Max answer before giving up on it. */
    private static final int MOST_WAIT = 40;
    /** The most the amount box takes. */
    private static final long MOST_SHOWN = AmountMath.MOST;
    /** Kept while the game is open: the list or the tree, as the player last chose. */
    private static boolean treeMode;

    private final DeckMenu menu;
    private final Font font;
    private final EditBox amount;
    /** What the amount box says now, worked out. */
    private AmountMath.Result parsed = AmountMath.evaluate("");
    private final List<Placed> buttons = new ArrayList<>();
    private final Button craft;
    private final Button view;
    private final CraftTreeView treeView = new CraftTreeView();
    private @Nullable ItemResource target;
    /** The server picked in the drop-down, as the Deck keeps it; null for any free server. */
    private @Nullable NetworkNode picked;
    private boolean dropOpen;
    /** The first row the open drop-down shows. */
    private int dropScroll;
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
        amount.setMaxLength(AmountMath.MAX_LENGTH);
        amount.setResponder(s -> {
            parsed = AmountMath.evaluate(s);
            amount.setTooltip(AmountTips.tooltip(parsed));
            askIn = 6;
        });
        place(JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11), 16, 5, true);
        place(JasmButton.text(Component.literal("-"), b -> step(-1), 0, 0, 15, 17), 58, 24, false);
        place(JasmButton.text(Component.literal("+"), b -> step(1), 0, 0, 15, 17), 127, 24, false);
        // Max sits right of the plus, the same size, with the same gap.
        Component maxName = Component.translatable("screen.jasm.craft.max");
        Button max = JasmButton.icon(() -> MAX, maxName, b -> askMost(), 0, 0, 15, 17);
        max.setTooltip(Tooltip.create(Component.empty().append(maxName).append("\n")
                .append(Component.translatable("screen.jasm.craft.max_hint").withStyle(ChatFormatting.GRAY))));
        place(max, 144, 24, false);
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
        picked = menu.deck().get(JasmComponents.CRAFT_SERVER.get());
        dropOpen = false;
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
        dropOpen = false;
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

    /** The amount typed, worked out; 0 when it can't be confirmed. */
    private long amount() {
        return parsed.value();
    }

    /** Minus and plus: 1 at a time, 16 with shift held. */
    private void step(int sign) {
        forgetMost();
        long by = Minecraft.getInstance().hasShiftDown() ? 16 : 1;
        amount.setValue(String.valueOf(Math.clamp(amount() + sign * by, 1, MOST_SHOWN)));
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
        if (target != null && parsed.ok()) {
            askedAt = ticks;
            ClientPacketDistributor.sendToServer(new CraftPayloads.Ask(menu.containerId, target, amount(), Optional.ofNullable(picked), treeMode));
        }
    }

    /** The server's answer, if it is about what the window shows now. In tree mode an answer without a tree is an older one. */
    private CraftPayloads.@Nullable Answer answer() {
        CraftPayloads.Answer answer = menu.view().answer();
        boolean fits = parsed.ok() && answer != null && target != null && answer.target().equals(target) && answer.amount() == amount();
        return fits && (!treeMode || answer.tree().isPresent()) ? answer : null;
    }

    /** The picked server among those the answer lists, or null; a picked server no longer listed is let go, as on the server. */
    private CraftPayloads.@Nullable ServerView pickedView(CraftPayloads.@Nullable Answer answer) {
        if (picked == null || answer == null) {
            return null;
        }
        for (CraftPayloads.ServerView server : answer.servers()) {
            if (server.pos().equals(picked)) {
                return server;
            }
        }
        picked = null;
        return null;
    }

    /** Picks {@code server} (null: any free one) and asks for the plan again, as soon as the server takes another ask. */
    private void pick(@Nullable NetworkNode server) {
        dropOpen = false;
        if (Objects.equals(server, picked)) {
            return;
        }
        picked = server;
        askIn = Math.max(1, ASK_GAP - (ticks - askedAt));
    }

    private void start() {
        CraftPayloads.Answer answer = answer();
        if (answer != null && answer.problem().isEmpty() && target != null) {
            NetworkNode pos = answer.chosen() >= 0 ? answer.servers().get(answer.chosen()).pos() : null;
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
        CraftPayloads.ServerView pickedServer = pickedView(answer);
        if (dropOpen && answer == null) {
            dropOpen = false;
        }
        // Under the open drop-down nothing else lights up or shows a tooltip.
        boolean underDrop = dropOpen && inDrop(answer, mouseX, mouseY);
        int realMouseX = mouseX;
        int realMouseY = mouseY;
        if (underDrop) {
            mouseX = -1000;
            mouseY = -1000;
        }
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
        Component label = AmountTips.label(parsed, FluidMarkerItem.isMarker(shown) ? "screen.jasm.craft.amount_fluid" : "screen.jasm.craft.amount");
        graphics.text(font, trim(label.getString(), 51), x + 7, y + 30, AmountTips.color(parsed), false);
        amount.extractRenderState(graphics, mouseX, mouseY, a);
        for (Placed placed : buttons) {
            placed.button().extractRenderState(graphics, mouseX, mouseY, a);
        }
        graphics.text(font, trim(Component.translatable("screen.jasm.craft.server_label").getString(), BOX_X - 9), x + 7, y + 47,
                JasmGui.SUBTEXT, false);
        drawBox(graphics, answer, pickedServer, mouseX, mouseY);

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
        if (dropOpen && answer != null) {
            graphics.nextStratum();
            drawDrop(graphics, answer, realMouseX, realMouseY);
        }
    }

    // --- the server drop-down ---

    private int boxX() {
        return x + BOX_X;
    }

    private int boxY() {
        return y + BOX_Y;
    }

    private int boxWidth() {
        return width - BOX_RIGHT - BOX_X;
    }

    private boolean onBox(double mouseX, double mouseY) {
        return mouseX >= boxX() && mouseX < boxX() + boxWidth() && mouseY >= boxY() && mouseY < boxY() + BOX_H;
    }

    /** Rows of the open drop-down: any free server, then every server. */
    private static int dropRows(CraftPayloads.Answer answer) {
        return answer.servers().size() + 1;
    }

    /** The cores and storage figures sit in columns as wide as the widest of each. */
    private int coresColumn(CraftPayloads.Answer answer) {
        int widest = 0;
        for (CraftPayloads.ServerView server : answer.servers()) {
            widest = Math.max(widest, font.width(String.valueOf(server.parallel())));
        }
        return widest;
    }

    private int storageColumn(CraftPayloads.Answer answer) {
        int widest = 0;
        for (CraftPayloads.ServerView server : answer.servers()) {
            widest = Math.max(widest, font.width(GridEntries.abbreviate(server.memory())));
        }
        return widest;
    }

    /** The open drop-down is exactly as wide as the box above it. */
    private int dropWidth(CraftPayloads.Answer answer) {
        return boxWidth();
    }

    /** Rows shown at once: as many as fit under the box inside the window. */
    private int dropShown(CraftPayloads.Answer answer) {
        int room = (y + height - 6 - (boxY() + BOX_H)) / DROP_ROW;
        return Math.max(1, Math.min(dropRows(answer), room));
    }

    private int dropHeight(CraftPayloads.Answer answer) {
        return dropShown(answer) * DROP_ROW + 2;
    }

    private boolean inDrop(CraftPayloads.@Nullable Answer answer, double mouseX, double mouseY) {
        return answer != null && mouseX >= boxX() && mouseX < boxX() + dropWidth(answer) && mouseY >= boxY() + BOX_H
                && mouseY < boxY() + BOX_H + dropHeight(answer);
    }

    /** The drop-down row under the mouse (0 is any free server), or -1. */
    private int dropRowAt(CraftPayloads.@Nullable Answer answer, double mouseX, double mouseY) {
        if (!inDrop(answer, mouseX, mouseY)) {
            return -1;
        }
        int row = (int) (mouseY - (boxY() + BOX_H + 1)) / DROP_ROW;
        return row >= 0 && row < dropShown(answer) ? dropScroll + row : -1;
    }

    /** Busy and too-small servers stay in the list, dimmed with a coloured name, so the player sees they can't take the job. */
    private static boolean pickable(CraftPayloads.ServerView server) {
        return !server.busy() && server.fits();
    }

    /** The closed box: the picked server's name, or which server "any free" goes to. */
    private void drawBox(GuiGraphicsExtractor graphics, CraftPayloads.@Nullable Answer answer, CraftPayloads.@Nullable ServerView pickedServer,
            int mouseX, int mouseY) {
        int bx = boxX();
        int by = boxY();
        int bw = boxWidth();
        JasmGui.inset(graphics, bx, by, bw, BOX_H);
        if (dropOpen || onBox(mouseX, mouseY)) {
            graphics.fill(bx + 1, by + 1, bx + bw - 1, by + BOX_H - 1, JasmGui.HOVER);
        }
        Component shown;
        int color = JasmGui.TEXT;
        if (answer == null) {
            shown = Component.translatable("screen.jasm.craft.working");
            color = JasmGui.MUTED;
        } else if (pickedServer != null) {
            shown = pickedServer.name();
        } else if (answer.chosen() >= 0) {
            shown = Component.translatable("screen.jasm.craft.server_any_chosen", answer.servers().get(answer.chosen()).name());
        } else {
            shown = Component.translatable("screen.jasm.craft.server_any");
        }
        graphics.text(font, trim(shown.getString(), bw - 18), bx + 4, by + 5, color, false);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, DROP, bx + bw - 11, by + 7, 6, 4);
        if (!dropOpen && onBox(mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.craft.server_hint"), mouseX, mouseY);
        }
    }

    private void drawDrop(GuiGraphicsExtractor graphics, CraftPayloads.Answer answer, int mouseX, int mouseY) {
        int dx = boxX();
        int dy = boxY() + BOX_H;
        int dw = dropWidth(answer);
        int shownRows = dropShown(answer);
        // the figures' right edge, clear of the scroll mark
        int right = dx + dw - 4 - (dropRows(answer) > shownRows ? 3 : 0);
        int coresColumn = coresColumn(answer);
        int storageColumn = storageColumn(answer);
        dropScroll = Math.clamp(dropScroll, 0, dropRows(answer) - shownRows);
        graphics.fill(dx, dy, dx + dw, dy + dropHeight(answer), JasmGui.SELECTED);
        graphics.fill(dx + 1, dy, dx + dw - 1, dy + dropHeight(answer) - 1, BACKGROUND);
        int hovered = dropRowAt(answer, mouseX, mouseY);
        for (int i = 0; i < shownRows; i++) {
            int row = dropScroll + i;
            int ry = dy + 1 + i * DROP_ROW;
            boolean selected = row == 0 ? picked == null : answer.servers().get(row - 1).pos().equals(picked);
            boolean usable = row == 0 || pickable(answer.servers().get(row - 1));
            if (selected) {
                graphics.fill(dx + 1, ry, dx + dw - 1, ry + DROP_ROW, JasmGui.SELECTED);
            }
            if (row == hovered && usable) {
                graphics.fill(dx + 1, ry, dx + dw - 1, ry + DROP_ROW, JasmGui.HOVER);
            }
            if (row == 0) {
                graphics.text(font, trim(Component.translatable("screen.jasm.craft.server_any").getString(), dw - 20), dx + 4, ry + 3,
                        JasmGui.TEXT, false);
                if (selected) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CHECK, dx + dw - 13, ry + 3, 9, 7);
                }
                continue;
            }
            CraftPayloads.ServerView server = answer.servers().get(row - 1);
            // a busy server's name is yellow, a too-small one's red; their figures are dimmed
            int nameColor = server.busy() ? JasmGui.WARN : !server.fits() ? JasmGui.BAD : JasmGui.TEXT;
            int figureColor = usable ? JasmGui.SUBTEXT : JasmGui.MUTED;
            float iconAlpha = usable ? 1.0F : 0.5F;
            String size = GridEntries.abbreviate(server.memory());
            int storageX = right - storageColumn - 1 - STORAGE_W;
            graphics.text(font, size, right - font.width(size), ry + 3, figureColor, false);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, STORAGE, storageX, ry + 4, STORAGE_W, 6, iconAlpha);
            String cores = String.valueOf(server.parallel());
            int coresRight = storageX - 5;
            int coresX = coresRight - coresColumn - 1 - CORES_W;
            graphics.text(font, cores, coresRight - font.width(cores), ry + 3, figureColor, false);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CORES, coresX, ry + 3, CORES_W, 7, iconAlpha);
            int nameRoom = coresX - 4 - (dx + 4);
            graphics.text(font, trim(server.name().getString(), nameRoom), dx + 4, ry + 3, nameColor, false);
        }
        if (dropRows(answer) > shownRows) {
            // a thin bar on the right shows where the list is scrolled to
            int track = dropHeight(answer) - 2;
            int handle = Math.max(4, track * shownRows / dropRows(answer));
            int offset = Math.round((track - handle) * dropScroll / (float) (dropRows(answer) - shownRows));
            graphics.fill(dx + dw - 3, dy + 1 + offset, dx + dw - 1, dy + 1 + offset + handle, JasmGui.MUTED);
        }
        if (hovered >= 1) {
            CraftPayloads.ServerView server = answer.servers().get(hovered - 1);
            List<Component> tip = new ArrayList<>();
            tip.add(server.name());
            tip.add(Component.translatable("screen.jasm.craft.server_size", server.parallel(), GridEntries.abbreviate(server.memory()))
                    .withColor(JasmGui.SUBTEXT & 0xFFFFFF));
            if (server.busy()) {
                tip.add(Component.translatable("screen.jasm.craft.server_busy").withColor(JasmGui.WARN & 0xFFFFFF));
            } else if (!server.fits()) {
                tip.add(Component.translatable("screen.jasm.craft.server_too_small").withColor(JasmGui.BAD & 0xFFFFFF));
            }
            BlockPos at = server.pos().pos();
            tip.add(Component.translatable("screen.jasm.craft.server_where", at.getX(), at.getY(), at.getZ()).withColor(JasmGui.MUTED & 0xFFFFFF));
            if (Minecraft.getInstance().level != null && !server.pos().dimension().equals(Minecraft.getInstance().level.dimension())) {
                tip.add(Component.literal(server.pos().dimension().identifier().toString()).withColor(JasmGui.MUTED & 0xFFFFFF));
            }
            graphics.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    /** Clicks while the drop-down is open: a row picks (greyed rows do nothing), anywhere else shuts it. */
    private boolean dropClicked(CraftPayloads.@Nullable Answer answer, double mouseX, double mouseY) {
        int row = dropRowAt(answer, mouseX, mouseY);
        if (row < 0) {
            dropOpen = false;
            return true;
        }
        if (row == 0) {
            pick(null);
        } else if (pickable(answer.servers().get(row - 1))) {
            pick(answer.servers().get(row - 1).pos());
        }
        return true;
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

    private String trim(String text, int room) {
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("...")) + "...";
    }

    // --- input ---

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (dropOpen) {
            return dropClicked(answer(), event.x(), event.y());
        }
        if (onBox(event.x(), event.y()) && answer() != null) {
            dropOpen = true;
            dropScroll = 0;
            amount.setFocused(false);
            return true;
        }
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
        if (dropOpen) {
            dropScroll -= (int) Math.signum(scrollY);
            return true;
        }
        if (treeMode) {
            return treeView.mouseScrolled(scrollX, scrollY, Minecraft.getInstance().hasShiftDown());
        }
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    /** Keys go to the amount box while it has focus; Enter starts the craft, Escape closes the window. */
    boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && dropOpen) {
            dropOpen = false;
            return true;
        }
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

    /** Only digits and the four signs reach the amount box. */
    boolean charTyped(CharacterEvent event) {
        if (!amount.isFocused()) {
            return false;
        }
        if (!AmountMath.typable(event.codepoint())) {
            return true;
        }
        forgetMost();
        return amount.charTyped(event);
    }

    static Component hint(boolean craftTab) {
        return Component.translatable(craftTab ? "screen.jasm.craft.hint_click" : "screen.jasm.craft.hint_middle").withStyle(ChatFormatting.AQUA);
    }
}
