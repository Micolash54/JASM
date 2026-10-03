package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.NetworkViewPayloads;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Deck's Network tab, drawn where the item grid is: how big the network is against its brain's limit, then its
 * machines as a list or a tree. Clicking a machine lights it up in the world for a few seconds.
 */
public final class NetworkPanel {
    /** Rows are as tall as the item grid's. */
    private static final int ROW = 18;
    /** Client ticks between two requests for a fresh view while the tab is open. */
    private static final int ASK_EVERY = 40;
    private static final int INDENT = 8;
    /** The tree stops stepping in here, so deep rows keep room for their names. */
    private static final int MAX_INDENT = 72;
    private static final int KEY = 16;
    private static final int DOT = 4;
    /** Junction icons are drawn through a half-clear coat of the well's colour. */
    private static final int FADE = 0x801E1E2E;
    private static final JasmButton.Icon LIST = new JasmButton.Icon(Jasm.id("icon/view_list"), 11, 11);
    private static final JasmButton.Icon TREE = new JasmButton.Icon(Jasm.id("icon/view_tree"), 11, 11);

    /** The last view the server sent; it only counts for the screen it was meant for. */
    private static NetworkViewPayloads.@Nullable View latest;

    private final DeckMenu menu;
    private final Font font;
    private int x;
    private int y;
    private int width;
    private int rows;
    private boolean tree;
    private int askIn;
    private @Nullable UUID link;
    private @Nullable JasmButton key;
    private NetworkViewPayloads.@Nullable View builtFrom;
    private boolean builtTree;
    /** The nodes as shown, in order, with their index in the view (for the tree's lines). */
    private List<Integer> shown = List.of();

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
        return player == null ? null : player.getInventory().getItem(menu.deckSlot()).get(JasmComponents.DECK_NETWORK.get());
    }

    private NetworkViewPayloads.@Nullable View view() {
        return latest != null && latest.containerId() == menu.containerId ? latest : null;
    }

    /** Where the grid is on screen, and how many rows it shows. Makes the list/tree key there. */
    public void place(int x, int y, int width, int rows) {
        this.x = x;
        this.y = y;
        this.width = width - 2;
        this.rows = rows;
        key = JasmButton.icon(() -> tree ? TREE : LIST, viewLabel(), b -> {
            tree = !tree;
            b.setMessage(viewLabel());
            b.setTooltip(Tooltip.create(viewLabel()));
        }, x + this.width - KEY, y + 1, KEY, KEY);
        key.setTooltip(Tooltip.create(viewLabel()));
    }

    public JasmButton key() {
        return key;
    }

    private Component viewLabel() {
        return Component.translatable(tree ? "screen.jasm.network.view_tree" : "screen.jasm.network.view_list");
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

    private int headerRows() {
        NetworkViewPayloads.View view = view();
        return view != null && view.header().partial() ? 2 : 1;
    }

    private int listRows() {
        return Math.max(0, rows - headerRows());
    }

    public int maxScroll() {
        rebuild();
        return showing() ? Math.max(0, shown.size() - listRows()) : 0;
    }

    private void rebuild() {
        NetworkViewPayloads.View view = view();
        if (view == builtFrom && tree == builtTree) {
            return;
        }
        builtFrom = view;
        builtTree = tree;
        List<Integer> order = new ArrayList<>();
        if (view != null) {
            for (int i = 0; i < view.nodes().size(); i++) {
                if (tree || !view.nodes().get(i).junction()) order.add(i);
            }
            if (!tree) {
                List<NetworkViewPayloads.Node> nodes = view.nodes();
                order.sort(Comparator.<Integer, String>comparing(i -> nodes.get(i).name().getString().toLowerCase(Locale.ROOT))
                        .thenComparingInt(i -> distance(nodes.get(i))));
            }
        }
        shown = order;
    }

    /** Whole blocks from the player, or -1 in another dimension. */
    private static int distance(NetworkViewPayloads.Node node) {
        var player = Minecraft.getInstance().player;
        if (player == null || !player.level().dimension().equals(node.dimension())) {
            return -1;
        }
        return Mth.floor(Math.sqrt(player.blockPosition().distSqr(node.pos())));
    }

    private static String where(NetworkViewPayloads.Node node) {
        int distance = distance(node);
        return distance >= 0 ? String.valueOf(distance) : node.dimension().identifier().getPath();
    }

    private int indent(NetworkViewPayloads.Node node) {
        return tree ? Math.min(MAX_INDENT, INDENT * node.depth()) : 0;
    }

    private int rowY(int listIndex, int scroll) {
        return y + (headerRows() + listIndex - scroll) * ROW;
    }

    /** The shown node under the mouse, as its index in the view, or -1. */
    private int nodeAt(double mouseX, double mouseY, int scroll) {
        if (!showing() || mouseX < x || mouseX >= x + width) {
            return -1;
        }
        int listTop = y + headerRows() * ROW;
        if (mouseY < listTop || mouseY >= y + rows * ROW) {
            return -1;
        }
        int index = (int) Math.floor((mouseY - listTop) / ROW) + scroll;
        return index >= 0 && index < shown.size() ? shown.get(index) : -1;
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
        if (tree) drawLines(graphics, view.nodes(), scroll);
        int hovered = nodeAt(mouseX, mouseY, scroll);
        for (int row = 0; row < listRows() && scroll + row < shown.size(); row++) {
            int index = shown.get(scroll + row);
            drawRow(graphics, view.nodes().get(index), rowY(scroll + row, scroll), index == hovered);
        }
        if (hovered >= 0 && menu.getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(font, tooltip(view.nodes().get(hovered)), mouseX, mouseY);
        }
    }

    /** Two short lines beside the key: the brain's level and the network's state, then how full it is. */
    private void drawHeader(GuiGraphicsExtractor graphics, NetworkViewPayloads.Header header) {
        Component level = header.level() >= 0
                ? Component.translatable("screen.jasm.network.level", header.level())
                : Component.translatable("screen.jasm.network.no_brain");
        graphics.text(font, level, x + 1, y, JasmGui.TEXT, false);
        Component state;
        int colour;
        if (header.stopped()) {
            state = Component.translatable("screen.jasm.network.full");
            colour = JasmGui.BAD;
        } else if (header.brainUnpowered()) {
            state = Component.translatable("screen.jasm.network.brain_unpowered");
            colour = JasmGui.WARN;
        } else {
            state = Component.translatable("screen.jasm.network.working");
            colour = JasmGui.GOOD;
        }
        graphics.text(font, state, x + width - KEY - 4 - font.width(state), y, colour, false);
        graphics.text(font, Component.translatable("screen.jasm.network.machines", header.count(), header.limit()), x + 1, y + 9,
                JasmGui.SUBTEXT, false);
        if (header.partial()) {
            graphics.text(font, trimmed(Component.translatable("screen.jasm.network.partial").getString(), width - 2), x + 1, y + ROW + 5,
                    JasmGui.MUTED, false);
        }
    }

    /** Each row hangs from a line down from its parent, with a short tick into it. */
    private void drawLines(GuiGraphicsExtractor graphics, List<NetworkViewPayloads.Node> nodes, int scroll) {
        int top = y + headerRows() * ROW;
        int bottom = y + rows * ROW;
        for (int i = 0; i < nodes.size(); i++) {
            NetworkViewPayloads.Node node = nodes.get(i);
            // Past the deepest indent a row sits right under its parent's icon: no line would fit, so none is drawn.
            if (node.parent() < 0 || indent(node) <= indent(nodes.get(node.parent()))) continue;
            int lineX = x + indent(nodes.get(node.parent())) + 3;
            int childY = rowY(i, scroll) + 8;
            int from = Math.max(top, rowY(node.parent(), scroll) + 17);
            int to = Math.min(bottom, childY + 1);
            if (from < to) graphics.fill(lineX, from, lineX + 1, to, JasmGui.MUTED);
            int tickEnd = x + indent(node) - 1;
            if (childY >= top && childY < bottom && tickEnd > lineX + 1) graphics.fill(lineX + 1, childY, tickEnd, childY + 1, JasmGui.MUTED);
        }
    }

    private void drawRow(GuiGraphicsExtractor graphics, NetworkViewPayloads.Node node, int ry, boolean hovered) {
        if (hovered) {
            graphics.fill(x, ry, x + width, ry + ROW - 1, JasmGui.HOVER);
        }
        int ix = x + indent(node);
        graphics.item(node.icon(), ix, ry + 1);
        if (node.junction()) {
            graphics.nextStratum();
            graphics.fill(ix, ry + 1, ix + 16, ry + 17, FADE);
        }
        String where = where(node);
        int whereX = x + width - 1 - font.width(where);
        graphics.text(font, where, whereX, ry + 5, JasmGui.SUBTEXT, false);
        int nameEnd = whereX - 4;
        if (!node.junction()) {
            int dotX = whereX - 4 - DOT;
            int colour = switch (node.status()) {
                case 0 -> JasmGui.GOOD;
                case 1 -> JasmGui.BAD;
                default -> JasmGui.MUTED;
            };
            graphics.fill(dotX, ry + 7, dotX + DOT, ry + 7 + DOT, colour);
            nameEnd = dotX - 3;
        }
        int nameX = ix + 19;
        graphics.text(font, trimmed(node.name().getString(), nameEnd - nameX), nameX, ry + 5,
                node.junction() ? JasmGui.MUTED : JasmGui.TEXT, false);
    }

    private List<FormattedCharSequence> tooltip(NetworkViewPayloads.Node node) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(node.name().getVisualOrderText());
        lines.add(Component.translatable("screen.jasm.network.pos", node.pos().getX(), node.pos().getY(), node.pos().getZ())
                .withColor(JasmGui.SUBTEXT & 0xFFFFFF).getVisualOrderText());
        lines.add(Component.literal(node.dimension().identifier().toString()).withColor(JasmGui.SUBTEXT & 0xFFFFFF).getVisualOrderText());
        int distance = distance(node);
        if (distance >= 0) {
            lines.add(Component.translatable("screen.jasm.network.distance", distance).withColor(JasmGui.SUBTEXT & 0xFFFFFF).getVisualOrderText());
        }
        if (!node.junction()) {
            Component status = switch (node.status()) {
                case 0 -> Component.translatable("screen.jasm.network.status.working").withColor(JasmGui.GOOD & 0xFFFFFF);
                case 1 -> Component.translatable("screen.jasm.network.status.stopped").withColor(JasmGui.BAD & 0xFFFFFF);
                default -> Component.translatable("screen.jasm.network.status.unloaded").withColor(JasmGui.MUTED & 0xFFFFFF);
            };
            lines.add(status.getVisualOrderText());
        }
        return lines;
    }

    private String trimmed(String text, int room) {
        if (room <= 0) return "";
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("…")) + "…";
    }

    // --- input ---

    /** A click on a row lights that block up in the world. */
    public void mouseClicked(double mouseX, double mouseY, int scrollRow) {
        int index = nodeAt(mouseX, mouseY, Math.min(scrollRow, maxScroll()));
        NetworkViewPayloads.View view = view();
        // A block in another dimension can't be lit up from here.
        if (index < 0 || view == null || distance(view.nodes().get(index)) < 0) {
            return;
        }
        AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
        ClientPacketDistributor.sendToServer(new NetworkViewPayloads.Locate(menu.containerId, view.nodes().get(index).pos()));
    }
}
