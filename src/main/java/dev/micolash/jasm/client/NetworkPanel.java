package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmClientConfig;
import dev.micolash.jasm.config.JasmClientConfig.NetworkSort;
import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.NetworkViewHover;
import dev.micolash.jasm.deck.NetworkViewOrder;
import dev.micolash.jasm.deck.NetworkViewPayloads;
import dev.micolash.jasm.deck.NetworkViewPayloads.Category;
import dev.micolash.jasm.network.NetworkNode;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Deck's Network tab, drawn where the item grid is: how big the network is against its brain's limit, then its
 * machines, power sources and Batteries with the power each adds or uses, grouped by category or as one list. Clicking
 * a row lights its block up in the world for a few seconds. Grouping, the sort and the folded categories are the
 * player's own choice, kept for every Deck.
 */
public final class NetworkPanel {
    /** Rows are as tall as the item grid's. */
    private static final int ROW = 18;
    /** A category's header: a 9 px band with a pixel of gap above it. */
    private static final int HEAD = 10;
    private static final int BAND = 9;
    /** Client ticks between two requests for a fresh view while the tab is open. */
    private static final int ASK_EVERY = 40;
    /** The keys: tall enough to hold their 11 px icons inside the button's rim; half keys are half as wide, sharing a rim. */
    private static final int KEY_WIDTH = 21;
    private static final int HALF_WIDTH = 11;
    private static final int KEY_HEIGHT = 19;
    /** Room under the grid's last row that the list may use, so the header's gap costs it no row. */
    private static final int SPARE = 2;
    /** The strip above the rows: a little over one row. */
    private static final int HEADER = 21;
    /** The little box that folds a category away. */
    private static final int FOLD = 7;
    /** Numbers end this far in from the right, clear of the scroll bar. */
    private static final int RIGHT_GAP = 2;
    /** Most rows a category's hover names. */
    private static final int HOVER_ROWS = 3;
    private static final int BAND_COLOUR = 0xFF313244;
    /** A badge is 5 px square. */
    private static final int BADGE = 5;
    private static final JasmButton.Icon GROUP = new JasmButton.Icon(Jasm.id("icon/group_category"), 11, 11);
    private static final JasmButton.Icon EXPAND = new JasmButton.Icon(Jasm.id("icon/expand_all"), 5, 11);
    private static final JasmButton.Icon COLLAPSE = new JasmButton.Icon(Jasm.id("icon/collapse_all"), 5, 11);
    private static final JasmButton.Icon SORT_POWER = new JasmButton.Icon(Jasm.id("icon/sort_power"), 11, 11);
    private static final JasmButton.Icon SORT_NAME = new JasmButton.Icon(Jasm.id("icon/sort_name"), 8, 5);
    private static final JasmButton.Icon SORT_TYPE = new JasmButton.Icon(Jasm.id("icon/sort_type"), 11, 11);

    /** The last view the server sent; it only counts for the screen it was meant for. */
    private static NetworkViewPayloads.@Nullable View latest;

    private final DeckMenu menu;
    private final Font font;
    private int x;
    private int y;
    private int width;
    private int rows;
    private int askIn;
    private @Nullable UUID link;
    private boolean tabShown;
    private final List<JasmButton> keys = new ArrayList<>();
    private @Nullable JasmButton groupKey;
    private @Nullable JasmButton expandKey;
    private @Nullable JasmButton collapseKey;
    private @Nullable JasmButton sortKey;
    /** What {@link #items} was worked out from. */
    private NetworkViewPayloads.@Nullable View builtFrom;
    private boolean builtGrouped;
    private @Nullable NetworkSort builtSort;
    private Set<Category> builtFolded = EnumSet.noneOf(Category.class);
    /** The list as shown: category headers and rows, in order. */
    private List<Item> items = List.of();
    /** Each category's rows in the current sort, folded or not, for the header's hover. */
    private Map<Category, List<NetworkViewPayloads.Node>> byCategory = Map.of();

    /** A category's header ({@code node} null) or one row. */
    private record Item(Category category, NetworkViewPayloads.@Nullable Node node) {
        int height() {
            return node == null ? HEAD : ROW;
        }
    }

    /** An item where it sits on screen; {@code pinned} for a header held at the top over its rows. */
    private record Placed(Item item, int top, boolean pinned) {}

    public NetworkPanel(DeckMenu menu, Font font) {
        this.menu = menu;
        this.font = font;
        // Screen ids start again in every world: an older view might carry this screen's id.
        latest = null;
        link = linkNow();
    }

    public static void receive(NetworkViewPayloads.View view) {
        latest = view;
    }

    /** The Deck screen closed: its view goes with it. */
    public void close() {
        latest = null;
    }

    /** The network the Deck in hand is linked to, as the client has it now. */
    private @Nullable UUID linkNow() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : DeckItem.inSlot(player, menu.deckSlot()).get(JasmComponents.DECK_NETWORK.get());
    }

    private NetworkViewPayloads.@Nullable View view() {
        return latest != null && latest.containerId() == menu.containerId ? latest : null;
    }

    /** Where the grid is on screen, and how many rows it shows. Makes the keys there. */
    public void place(int x, int y, int width, int rows) {
        this.x = x;
        this.y = y;
        this.width = width - 2;
        this.rows = rows;
        keys.clear();
        groupKey = JasmButton.icon(() -> GROUP, groupLabel(), b -> {
            JasmClientConfig.setNetworkGrouped(!JasmClientConfig.networkGrouped());
            updateKeys();
        }, 0, y, KEY_WIDTH, KEY_HEIGHT);
        expandKey = JasmButton.icon(() -> EXPAND, Component.translatable("screen.jasm.network.expand_all"),
                b -> setFolded(EnumSet.noneOf(Category.class)), 0, y, HALF_WIDTH, KEY_HEIGHT);
        collapseKey = JasmButton.icon(() -> COLLAPSE, Component.translatable("screen.jasm.network.collapse_all"),
                b -> setFolded(EnumSet.allOf(Category.class)), 0, y, HALF_WIDTH, KEY_HEIGHT);
        sortKey = JasmButton.icon(() -> sortIcon(JasmClientConfig.networkSort()), sortLabel(), b -> {
            NetworkSort[] all = NetworkSort.values();
            JasmClientConfig.setNetworkSort(all[(JasmClientConfig.networkSort().ordinal() + 1) % all.length]);
            updateKeys();
        }, x + this.width - KEY_WIDTH, y, KEY_WIDTH, KEY_HEIGHT);
        expandKey.setTooltip(Tooltip.create(expandKey.getMessage()));
        collapseKey.setTooltip(Tooltip.create(collapseKey.getMessage()));
        keys.addAll(List.of(groupKey, expandKey, collapseKey, sortKey));
        updateKeys();
    }

    /** The tab's keys, for the screen to add. */
    public List<JasmButton> keys() {
        return keys;
    }

    /** Whether the Network tab is the one open: its keys only show then. */
    public void setShown(boolean shown) {
        tabShown = shown;
        updateKeys();
    }

    /** Whether one of the tab's keys is under the mouse. */
    public boolean overKey(double mouseX, double mouseY) {
        for (JasmButton key : keys) {
            if (key.visible && key.isMouseOver(mouseX, mouseY)) return true;
        }
        return false;
    }

    /**
     * Lays the keys out from the right: Sort, then while grouping is on the two half keys, then Group by category. Their
     * labels follow the player's choices.
     */
    private void updateKeys() {
        if (groupKey == null || expandKey == null || collapseKey == null || sortKey == null) return;
        boolean grouped = JasmClientConfig.networkGrouped();
        int right = x + width - KEY_WIDTH;
        sortKey.setX(right);
        collapseKey.setX(right - HALF_WIDTH + 1);
        expandKey.setX(right - 2 * (HALF_WIDTH - 1));
        groupKey.setX(grouped ? expandKey.getX() - KEY_WIDTH + 1 : right - KEY_WIDTH + 1);
        groupKey.visible = tabShown;
        sortKey.visible = tabShown;
        expandKey.visible = tabShown && grouped;
        collapseKey.visible = tabShown && grouped;
        groupKey.setLatched(grouped);
        groupKey.setMessage(groupLabel());
        groupKey.setTooltip(Tooltip.create(groupLabel()));
        sortKey.setMessage(sortLabel());
        sortKey.setTooltip(Tooltip.create(sortLabel()));
    }

    private static Component groupLabel() {
        return Component.translatable(JasmClientConfig.networkGrouped() ? "screen.jasm.network.group_on" : "screen.jasm.network.group_off");
    }

    private static Component sortLabel() {
        return Component.translatable("screen.jasm.network.sort." + JasmClientConfig.networkSort().name().toLowerCase(Locale.ROOT));
    }

    private static JasmButton.Icon sortIcon(NetworkSort sort) {
        return switch (sort) {
            case POWER -> SORT_POWER;
            case NAME -> SORT_NAME;
            case TYPE -> SORT_TYPE;
        };
    }

    private static Set<Category> folded() {
        Set<Category> folded = EnumSet.noneOf(Category.class);
        for (String key : JasmClientConfig.networkFolded()) {
            for (Category category : Category.values()) {
                if (category.key().equals(key)) folded.add(category);
            }
        }
        return folded;
    }

    private static void setFolded(Set<Category> folded) {
        List<String> keys = new ArrayList<>();
        for (Category category : folded) keys.add(category.key());
        JasmClientConfig.setNetworkFolded(keys);
    }

    /** The tab was opened: ask at once, then every two seconds. */
    public void open() {
        UUID now = linkNow();
        if (!Objects.equals(now, link)) {
            link = now;
            latest = null;
        }
        ask();
    }

    public void tick() {
        UUID now = linkNow();
        if (!Objects.equals(now, link)) {
            // Linked somewhere else, or unlinked: the old view no longer applies.
            link = now;
            latest = null;
            ask();
        }
        if (--askIn <= 0) ask();
    }

    private void ask() {
        askIn = ASK_EVERY;
        ClientPacketDistributor.sendToServer(new NetworkViewPayloads.Ask(menu.containerId));
    }

    // --- what is shown ---

    private boolean showing() {
        NetworkViewPayloads.View view = view();
        return view != null && view.header().state() == 0;
    }

    /** The strip above the rows: one short one, or two rows' worth when the view is partial. */
    private int headerHeight() {
        NetworkViewPayloads.View view = view();
        return view != null && view.header().partial() ? 2 * ROW + 2 : HEADER;
    }

    private int listTop() {
        return y + headerHeight();
    }

    private int listBottom() {
        return y + rows * ROW + SPARE;
    }

    /** Whether the list starting at item {@code first} holds its category's header pinned over it. */
    private boolean pins(int first) {
        return first < items.size() && items.get(first).node() != null && JasmClientConfig.networkGrouped();
    }

    /** The first item at the top when scrolled all the way down: the list from there fills the room, pinned header and all. */
    public int maxScroll() {
        rebuild();
        if (!showing() || items.isEmpty()) return 0;
        // A row is 17 px of content on an 18 px step, so the last one needs no gap under it.
        int room = listBottom() - listTop() + (items.getLast().node() != null ? 1 : 0);
        int below = 0;
        int first = items.size();
        for (int i = items.size() - 1; i >= 0; i--) {
            below += items.get(i).height();
            if (below + (pins(i) ? HEAD : 0) > room) break;
            first = i;
        }
        return Math.min(first, Math.max(0, items.size() - 1));
    }

    private void rebuild() {
        NetworkViewPayloads.View view = view();
        boolean grouped = JasmClientConfig.networkGrouped();
        NetworkSort sort = JasmClientConfig.networkSort();
        Set<Category> folded = folded();
        if (view == builtFrom && grouped == builtGrouped && sort == builtSort && folded.equals(builtFolded)) {
            return;
        }
        builtFrom = view;
        builtGrouped = grouped;
        builtSort = sort;
        builtFolded = folded;
        List<Item> order = new ArrayList<>();
        if (view != null) {
            for (NetworkViewOrder.Item item : NetworkViewOrder.list(view, grouped, sort, folded)) {
                order.add(new Item(item.category(), item.node()));
            }
        }
        items = order;
        byCategory = view == null ? Map.of() : NetworkViewOrder.byCategory(view, sort);
    }

    private static Component categoryName(Category category) {
        return NetworkViewOrder.name(category);
    }

    /** Where each item from {@code scroll} on sits, down to the bottom of the room. */
    private List<Placed> placed(int scroll) {
        List<Placed> placed = new ArrayList<>();
        int top = listTop();
        int bottom = listBottom();
        if (scroll >= items.size()) return placed;
        if (pins(scroll)) {
            placed.add(new Placed(new Item(items.get(scroll).category(), null), top, true));
            top += HEAD;
        }
        for (int i = scroll; i < items.size(); i++) {
            Item item = items.get(i);
            if (top + item.height() > bottom + (item.node() == null ? 0 : 1)) break;
            placed.add(new Placed(item, top, false));
            top += item.height();
        }
        return placed;
    }

    private @Nullable Placed at(double mouseX, double mouseY, int scroll) {
        if (!showing() || mouseX < x || mouseX >= x + width) return null;
        for (Placed placed : placed(scroll)) {
            int top = placed.top() + (placed.item().node() == null ? HEAD - BAND : 0);
            int bottom = placed.top() + placed.item().height() - (placed.item().node() == null ? 0 : 1);
            if (mouseY >= top && mouseY < bottom) return placed;
        }
        return null;
    }

    // --- drawing ---

    public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int scrollRow) {
        rebuild();
        NetworkViewPayloads.View view = view();
        if (view == null) {
            return;
        }
        NetworkViewPayloads.Header header = view.header();
        if (header.state() != 0) {
            String key = switch (header.state()) {
                case 1 -> "screen.jasm.network.not_linked";
                case 2 -> "screen.jasm.network.unreachable";
                default -> "screen.jasm.network.no_access";
            };
            List<FormattedCharSequence> lines = font.split(Component.translatable(key), width - 12);
            int top = y + rows * ROW / 2 - lines.size() * font.lineHeight / 2;
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(font, lines.get(i), x + (width - font.width(lines.get(i))) / 2, top + i * font.lineHeight, JasmGui.MUTED, false);
            }
            return;
        }
        drawHeader(graphics, header);
        int scroll = Math.min(scrollRow, maxScroll());
        Placed hovered = at(mouseX, mouseY, scroll);
        for (Placed placed : placed(scroll)) {
            NetworkViewPayloads.Node node = placed.item().node();
            boolean lit = hovered != null && hovered.equals(placed);
            if (node == null) {
                drawCategory(graphics, header, placed.item().category(), placed.top() + HEAD - BAND, lit);
            } else {
                drawRow(graphics, node, placed.top(), lit);
            }
        }
        if (hovered != null && menu.getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(font, tooltip(hovered, header, mouseX), mouseX, mouseY);
        }
    }

    /**
     * "Machines: X / Y", yellow once the network is full and red while it is over its limit; under it a note while part
     * of the network isn't loaded.
     */
    private void drawHeader(GuiGraphicsExtractor graphics, NetworkViewPayloads.Header header) {
        Component machines = Component.translatable("screen.jasm.network.machines", header.count(), BrainBalance.shown(header.limit()));
        int colour = header.over() ? JasmGui.BAD : header.full() ? JasmGui.WARN : JasmGui.SUBTEXT;
        graphics.text(font, machines, x + 1, y + 5, colour, false);
        if (header.partial()) {
            graphics.text(font, trimmed(Component.translatable("screen.jasm.network.partial").getString(), width - 2), x + 1, y + ROW + 5,
                    JasmGui.MUTED, false);
        }
    }

    /** One line: fold box, name and count on the left, the category's power on the right; the Batteries' charge bar between. */
    private void drawCategory(GuiGraphicsExtractor graphics, NetworkViewPayloads.Header header, Category category, int by, boolean lit) {
        graphics.fill(x, by, x + width, by + BAND, BAND_COLOUR);
        if (lit) graphics.fill(x, by, x + width, by + BAND, JasmGui.HOVER);
        drawFold(graphics, x + 2, by + 1, builtFolded.contains(category), lit ? JasmGui.TEXT : JasmGui.SUBTEXT);
        Component name = categoryName(category);
        int nameX = x + 2 + FOLD + 3;
        graphics.text(font, name, nameX, by + 1, JasmGui.ACCENT, false);
        String count = String.valueOf(header.group(category).count());
        int countX = nameX + font.width(name) + 4;
        graphics.text(font, count, countX, by + 1, JasmGui.MUTED, false);
        long power = header.group(category).power();
        String total = signed(power);
        int totalX = x + width - RIGHT_GAP - font.width(total);
        int colour = power > 0 ? JasmGui.GOOD : power < 0 ? JasmGui.BAD : JasmGui.TEXT;
        graphics.text(font, total, totalX, by + 1, colour, false);
        if (category == Category.BATTERIES) {
            int[] bar = batteryBar(category, by);
            if (bar != null) {
                double full = header.capacity() <= 0 ? 0 : (double) header.stored() / header.capacity();
                JasmGui.bar(graphics, bar[0], by + 1, bar[1] - bar[0], BAND - 2, full);
            }
        }
    }

    /** Left and right edge of the Batteries' charge bar, between the count and the number, or null with no room. */
    private int @Nullable [] batteryBar(Category category, int by) {
        NetworkViewPayloads.View view = view();
        if (view == null) return null;
        NetworkViewPayloads.Header header = view.header();
        int left = x + 2 + FOLD + 3 + font.width(categoryName(category)) + 4 + font.width(String.valueOf(header.group(category).count())) + 4;
        int right = x + width - RIGHT_GAP - font.width(signed(header.group(category).power())) - 4;
        return right - left >= 8 ? new int[] {left, right} : null;
    }

    private void drawRow(GuiGraphicsExtractor graphics, NetworkViewPayloads.Node node, int ry, boolean lit) {
        if (lit) {
            graphics.fill(x, ry, x + width, ry + ROW - 1, JasmGui.HOVER);
        }
        graphics.item(node.icon(), x + 1, ry + 1);
        node.serving().ifPresent(serving -> {
            // A small badge in the icon's corner, in the colours of that port's own block: the row is a port or a Power
            // Interface, shown as the block it serves.
            graphics.nextStratum();
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Jasm.id("icon/" + serving.badge().sprite()), x + 1, ry + 1, BADGE, BADGE);
        });
        int nameEnd = x + width - RIGHT_GAP;
        String value = rowValue(node);
        if (!value.isEmpty()) {
            int valueX = x + width - RIGHT_GAP - font.width(value);
            graphics.text(font, value, valueX, ry + 5, JasmGui.SUBTEXT, false);
            nameEnd = valueX - 4;
        }
        int nameX = x + 20;
        graphics.text(font, trimmed(node.name().getString(), nameEnd - nameX), nameX, ry + 5, JasmGui.TEXT, false);
    }

    /** A Battery's charge, or the power a row adds or uses; nothing for a row that uses none right now. */
    private static String rowValue(NetworkViewPayloads.Node node) {
        if (node.category() == Category.BATTERIES) {
            return Component.translatable("screen.jasm.network.stored", GridEntries.abbreviate(node.stored())).getString();
        }
        return node.power() == 0 ? "" : signed(node.power());
    }

    /** "+60 FE/t", "0 FE/t" or "-30 FE/t". */
    private static String signed(long power) {
        String number = String.format("%,d", Math.abs(power));
        return Component.translatable("screen.jasm.network.power", (power > 0 ? "+" : power < 0 ? "-" : "") + number).getString();
    }

    /** A small square with a minus in it, or a plus while the category is folded away. */
    private static void drawFold(GuiGraphicsExtractor graphics, int bx, int by, boolean folded, int colour) {
        graphics.fill(bx, by, bx + FOLD, by + 1, colour);
        graphics.fill(bx, by + FOLD - 1, bx + FOLD, by + FOLD, colour);
        graphics.fill(bx, by + 1, bx + 1, by + FOLD - 1, colour);
        graphics.fill(bx + FOLD - 1, by + 1, bx + FOLD, by + FOLD - 1, colour);
        graphics.fill(bx + 2, by + 3, bx + FOLD - 2, by + 4, colour);
        if (folded) graphics.fill(bx + 3, by + 2, bx + 4, by + FOLD - 2, colour);
    }

    private List<FormattedCharSequence> tooltip(Placed placed, NetworkViewPayloads.Header header, int mouseX) {
        NetworkViewPayloads.Node node = placed.item().node();
        return node == null ? categoryTooltip(placed, header, mouseX) : rowTooltip(node);
    }

    /** Over the Batteries' bar, what they hold; anywhere else on a header, its first few rows by the current sort. */
    private List<FormattedCharSequence> categoryTooltip(Placed placed, NetworkViewPayloads.Header header, int mouseX) {
        Category category = placed.item().category();
        List<FormattedCharSequence> lines = new ArrayList<>();
        int[] bar = category == Category.BATTERIES ? batteryBar(category, placed.top()) : null;
        if (bar != null && mouseX >= bar[0] && mouseX < bar[1]) {
            lines.add(Component.translatable("screen.jasm.network.stored_of", GridEntries.abbreviate(header.stored()),
                    GridEntries.abbreviate(header.capacity())).getVisualOrderText());
            for (NetworkViewHover.Line line : NetworkViewHover.flow(header.in(), header.out())) lines.add(shown(line));
            return lines;
        }
        List<NetworkViewPayloads.Node> nodes = byCategory.getOrDefault(category, List.of());
        for (NetworkViewPayloads.Node node : nodes.subList(0, Math.min(HOVER_ROWS, nodes.size()))) {
            String value = rowValue(node);
            Component line = Component.literal(node.name().getString());
            if (!value.isEmpty()) line = line.copy().append(Component.literal("  " + value).withColor(JasmGui.SUBTEXT & 0xFFFFFF));
            lines.add(line.getVisualOrderText());
        }
        int more = header.group(category).count() - Math.min(HOVER_ROWS, nodes.size());
        if (more > 0) {
            lines.add(Component.translatable("screen.jasm.network.more", more).withColor(JasmGui.MUTED & 0xFFFFFF).getVisualOrderText());
        }
        return lines;
    }

    /** A row's hover, as {@link NetworkViewHover} lays it out. */
    private static List<FormattedCharSequence> rowTooltip(NetworkViewPayloads.Node node) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (NetworkViewHover.Line line : NetworkViewHover.row(node)) lines.add(shown(line));
        return lines;
    }

    private static FormattedCharSequence shown(NetworkViewHover.Line line) {
        int colour = switch (line.tone()) {
            case PLAIN -> 0;
            case SOFT -> JasmGui.SUBTEXT;
            case GOOD -> JasmGui.GOOD;
            case BAD -> JasmGui.BAD;
            case MUTED -> JasmGui.MUTED;
            case WARN -> JasmGui.WARN;
        };
        return colour == 0 ? line.text().getVisualOrderText() : line.text().copy().withColor(colour & 0xFFFFFF).getVisualOrderText();
    }

    private String trimmed(String text, int room) {
        if (room <= 0) return "";
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("…")) + "…";
    }

    // --- input ---

    /** A click on a category's header folds or opens it; on a row, it lights that block up in the world. */
    public void mouseClicked(double mouseX, double mouseY, int scrollRow) {
        Placed placed = at(mouseX, mouseY, Math.min(scrollRow, maxScroll()));
        if (placed == null) {
            return;
        }
        NetworkViewPayloads.Node node = placed.item().node();
        if (node == null) {
            Set<Category> folded = folded();
            if (!folded.remove(placed.item().category())) folded.add(placed.item().category());
            setFolded(folded);
            AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
            return;
        }
        var player = Minecraft.getInstance().player;
        // A block in another dimension can't be lit up from here.
        if (player == null || !player.level().dimension().equals(node.dimension())) {
            return;
        }
        AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
        ClientPacketDistributor.sendToServer(new NetworkViewPayloads.Locate(menu.containerId, new NetworkNode(node.dimension(), node.pos())));
    }
}
