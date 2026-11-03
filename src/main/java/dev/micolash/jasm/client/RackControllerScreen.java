package dev.micolash.jasm.client;

import dev.micolash.jasm.autocraft.Card;
import dev.micolash.jasm.autocraft.ProcessingCard;
import dev.micolash.jasm.autocraft.RackControllerMenu;
import dev.micolash.jasm.autocraft.RackPayloads;
import dev.micolash.jasm.autocraft.RecipeRackBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Rack Controller's screen: a search box over a scrolling list with one section per Recipe Rack (its name, how full
 * it is, its 16 slots in two rows), then the inventory. Click a card to take it, click an empty slot to put the held
 * card in, Shift-click a card to send it to the inventory. While searching, cards that make what was typed stay lit,
 * the rest dim, and racks with no match fold to their name.
 */
public class RackControllerScreen extends JasmScreen<RackControllerMenu> {
    private static final int WIDTH = RackControllerMenu.WIDTH;
    private static final int HEIGHT = RackControllerMenu.HEIGHT;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - HELP_ROOM - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int LIST_X = RackControllerMenu.LIST_X;
    private static final int LIST_Y = RackControllerMenu.LIST_Y;
    private static final int LIST_H = RackControllerMenu.LIST_HEIGHT;
    private static final int COLS = 8;
    private static final int ROWS = RecipeRackBlockEntity.SLOTS / COLS;
    private static final int LIST_W = COLS * 18;
    private static final int TRACK_X = LIST_X + LIST_W + 2;
    private static final int KNOB = 18;
    /** A rack's name strip, its slots under it, and the gap to the next rack. */
    private static final int HEADER = 12;
    private static final int GAP = 2;
    private static final int FULL = HEADER + ROWS * 18 + GAP;
    private static final int FOLDED = HEADER + GAP;
    private static final int NOTE = 12;
    private static final int DIM = 0xB31E1E2E;
    private static final int SCROLL_STEP = 18;

    /**
     * One rack as laid out: its view, what each slot shows (the item a card makes, as with Shift held over a card), which
     * cards the search lights (null without a search), and where it starts.
     */
    private record Section(RackControllerMenu.RackView rack, List<ItemStack> icons, boolean @Nullable [] lit, boolean folded, int top) {
        int height() {
            return folded ? FOLDED : FULL;
        }
    }

    private JasmField search;
    private List<Section> sections = List.of();
    private int contentHeight;
    private int laidOutFor = -1;
    private String laidOutQuery = "";
    private int scroll;
    private boolean dragging;

    public RackControllerScreen(RackControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = RackControllerMenu.INVENTORY_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        addHelp(WIDTH - 7, "items/rack-controller.md");
        String query = search == null ? "" : search.getValue();
        search = new JasmField(font, leftPos + LIST_X, topPos + 19, WIDTH - 2 * LIST_X, 14,
                Component.translatable("screen.jasm.rack_controller.search_hint"));
        search.setHint(Component.translatable("screen.jasm.rack_controller.search_hint"));
        search.setSelectAllOnClick();
        search.setValue(query);
        search.setResponder(text -> scroll = 0);
        addRenderableWidget(search);
        laidOutFor = -1;
    }

    // --- the list ---

    /** Lays the racks out again when the server sent something or the search changed. */
    private List<Section> sections() {
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        if (laidOutFor != menu.version() || !query.equals(laidOutQuery)) {
            laidOutFor = menu.version();
            laidOutQuery = query;
            List<RackControllerMenu.RackView> racks = new ArrayList<>(menu.racks());
            racks.sort(RackControllerMenu.RackView.ORDER);
            List<Section> laid = new ArrayList<>(racks.size());
            int y = 0;
            for (RackControllerMenu.RackView rack : racks) {
                boolean[] lit = null;
                boolean folded = false;
                if (!query.isEmpty()) {
                    lit = new boolean[RecipeRackBlockEntity.SLOTS];
                    boolean any = false;
                    for (int i = 0; i < lit.length; i++) {
                        lit[i] = matches(rack.cards().get(i), query);
                        any |= lit[i];
                    }
                    folded = !any;
                }
                Section section = new Section(rack, icons(rack), lit, folded, y);
                laid.add(section);
                y += section.height();
            }
            sections = laid;
            contentHeight = y + (menu.complete() || racks.isEmpty() ? 0 : NOTE);
        }
        return sections;
    }

    /** What each slot shows: the item its card makes, or the card itself when it makes nothing that can be drawn. */
    private static List<ItemStack> icons(RackControllerMenu.RackView rack) {
        List<ItemStack> icons = new ArrayList<>(RecipeRackBlockEntity.SLOTS);
        for (ItemStack stack : rack.cards()) {
            Card card = Card.of(stack);
            ItemStack result = card == null ? ItemStack.EMPTY : card.result();
            icons.add(result.isEmpty() ? stack : result);
        }
        return icons;
    }

    private static boolean matches(ItemStack stack, String query) {
        return RackControllerMenu.matches(stack, query, RackControllerScreen::outputName);
    }

    private static Component outputName(ProcessingCard.Amount amount) {
        return amount.isMaterial() ? MaterialIcons.name(amount.material()) : amount.stack().getHoverName();
    }

    private int maxScroll() {
        sections();
        return Math.max(0, contentHeight - LIST_H);
    }

    private boolean inList(double mouseX, double mouseY) {
        return mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_W && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + LIST_H;
    }

    /** What is under the mouse in the list: a rack's name strip ({@code slot} -1) or one of its slots. */
    private record Spot(Section section, int slot) {}

    private @Nullable Spot spotAt(double mouseX, double mouseY) {
        if (!inList(mouseX, mouseY)) return null;
        int x = (int) Math.floor(mouseX) - leftPos - LIST_X;
        int y = (int) Math.floor(mouseY) - topPos - LIST_Y + scroll;
        for (Section section : sections()) {
            if (y < section.top() || y >= section.top() + section.height()) continue;
            int inside = y - section.top();
            if (inside < HEADER - 1) return new Spot(section, -1);
            if (section.folded() || inside < HEADER) return null;
            int row = (inside - HEADER) / 18;
            if (row >= ROWS) return null;
            return new Spot(section, row * COLS + x / 18);
        }
        return null;
    }

    // --- drawing ---

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        for (var slot : menu.slots) {
            JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
        JasmGui.bar(graphics, leftPos + BAR_X, topPos + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
        scroll = Math.clamp(scroll, 0, maxScroll());
        int max = maxScroll();
        int offset = max == 0 ? 0 : Math.round((LIST_H - KNOB) * scroll / (float) max);
        JasmGui.track(graphics, leftPos + TRACK_X, topPos + LIST_Y, LIST_H, offset, KNOB, max > 0);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        drawList(graphics, mouseX, mouseY);
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, List.of(
                    Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                            String.format("%,d", menu.capacity())).getVisualOrderText(),
                    Component.translatable("screen.jasm.archive.drain", menu.drain())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText()),
                    mouseX, mouseY);
        }
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = leftPos + LIST_X;
        int top = topPos + LIST_Y;
        List<Section> laid = sections();
        if (laid.isEmpty()) {
            // With part of the network unloaded, its racks may be there: say so rather than that there are none.
            Component empty = !menu.running() ? MachineStatusText.noPower(menu.containerId, "screen.jasm.rack_controller.no_power")
                    : Component.translatable(menu.complete() ? "screen.jasm.rack_controller.none" : "screen.jasm.rack_controller.unloaded");
            List<FormattedCharSequence> lines = font.split(empty, LIST_W);
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(font, lines.get(i), left + (LIST_W - font.width(lines.get(i))) / 2, top + LIST_H / 2 - lines.size() * 5 + i * 10,
                        menu.running() ? JasmGui.MUTED : JasmGui.BAD, false);
            }
            return;
        }
        Spot hovered = spotAt(mouseX, mouseY);
        graphics.enableScissor(left, top, left + LIST_W, top + LIST_H);
        for (Section section : laid) {
            int y = top + section.top() - scroll;
            if (y + section.height() <= top || y >= top + LIST_H) continue;
            drawHeader(graphics, section, left, y);
            if (section.folded()) continue;
            for (int i = 0; i < RecipeRackBlockEntity.SLOTS; i++) {
                int x = left + 1 + i % COLS * 18;
                int sy = y + HEADER + 1 + i / COLS * 18;
                JasmGui.slot(graphics, x, sy);
                if (hovered != null && hovered.section() == section && hovered.slot() == i) JasmGui.slotHover(graphics, x, sy);
                ItemStack icon = section.icons().get(i);
                if (!icon.isEmpty()) FluidGrid.drawStack(graphics, icon, x, sy);
            }
        }
        // Over the cards: those the search doesn't find dimmed, then the hover rim.
        graphics.nextStratum();
        for (Section section : laid) {
            int y = top + section.top() - scroll;
            if (section.folded() || y + section.height() <= top || y >= top + LIST_H) continue;
            for (int i = 0; i < RecipeRackBlockEntity.SLOTS; i++) {
                int x = left + 1 + i % COLS * 18;
                int sy = y + HEADER + 1 + i / COLS * 18;
                if (section.lit() != null && !section.lit()[i]) graphics.fill(x, sy, x + 16, sy + 16, DIM);
                if (hovered != null && hovered.section() == section && hovered.slot() == i) JasmGui.slotHoverFrame(graphics, x, sy);
            }
        }
        if (!menu.complete()) {
            int y = top + contentHeight - NOTE - scroll + 2;
            Component note = Component.translatable("screen.jasm.rack_controller.unloaded");
            JasmGui.smallText(graphics, font, note, left + (LIST_W - JasmGui.smallWidth(font, note)) / 2, y, JasmGui.MUTED);
        }
        graphics.disableScissor();
        if (hovered != null) tooltip(graphics, hovered, mouseX, mouseY);
    }

    /** The name strip: the rack's name (red without power), and how many of its slots hold a card, or "no match". */
    private void drawHeader(GuiGraphicsExtractor graphics, Section section, int left, int y) {
        JasmGui.inset(graphics, left, y, LIST_W, HEADER - 1);
        RackControllerMenu.RackView rack = section.rack();
        Component count = section.folded() ? Component.translatable("screen.jasm.rack_controller.no_match")
                : Component.translatable("screen.jasm.rack_controller.count", rack.filled(), RecipeRackBlockEntity.SLOTS);
        int countWidth = font.width(count);
        graphics.text(font, count, left + LIST_W - 3 - countWidth, y + 2, JasmGui.MUTED, false);
        String name = font.plainSubstrByWidth(name(rack).getString(), LIST_W - 10 - countWidth);
        graphics.text(font, name, left + 3, y + 2, rack.powered() ? (section.folded() ? JasmGui.MUTED : JasmGui.TEXT) : JasmGui.BAD, false);
    }

    private static Component name(RackControllerMenu.RackView rack) {
        return rack.label().isEmpty() ? JasmBlocks.RECIPE_RACK.get().getName() : Component.literal(rack.label());
    }

    private void tooltip(GuiGraphicsExtractor graphics, Spot spot, int mouseX, int mouseY) {
        RackControllerMenu.RackView rack = spot.section().rack();
        if (spot.slot() >= 0) {
            ItemStack card = rack.cards().get(spot.slot());
            if (!card.isEmpty()) {
                graphics.setTooltipForNextFrame(font, getTooltipFromContainerItem(card), card.getTooltipImage(), mouseX, mouseY);
            }
            return;
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(name(rack).copy().withColor(JasmGui.TEXT & 0xFFFFFF).getVisualOrderText());
        var pos = rack.at().pos();
        lines.add(Component.translatable("screen.jasm.rack_controller.at", pos.getX(), pos.getY(), pos.getZ())
                .withColor(JasmGui.SUBTEXT & 0xFFFFFF).getVisualOrderText());
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(rack.at().dimension())) {
            lines.add(Component.translatable("screen.jasm.rack_controller.in", dimensionName(rack.at().dimension().identifier().getPath()))
                    .withColor(JasmGui.SUBTEXT & 0xFFFFFF).getVisualOrderText());
        }
        if (!rack.powered()) {
            lines.add(Component.translatable("screen.jasm.rack.no_power").withColor(JasmGui.BAD & 0xFFFFFF).getVisualOrderText());
        }
        graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    /** "the_nether" as "The Nether". */
    static String dimensionName(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.split("[_/]")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, font.plainSubstrByWidth(title.getString(), BAR_X - 4 - titleLabelX), titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        // With racks listed, a controller that stops (no power, network full) says so above the inventory.
        if (!menu.running() && !sections().isEmpty()) {
            Component line = MachineStatusText.noPower(menu.containerId, "screen.jasm.rack_controller.no_power", font, WIDTH - 16);
            graphics.text(font, line, WIDTH - 8 - font.width(line), inventoryLabelY, JasmGui.BAD, false);
        }
    }

    // --- input ---

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (inList(x, y)) {
            Spot spot = spotAt(x, y);
            if (spot != null && spot.slot() >= 0 && menu.running()) {
                ItemStack there = spot.section().rack().cards().get(spot.slot());
                boolean quick = Minecraft.getInstance().hasShiftDown();
                boolean anything = quick ? !there.isEmpty() : !there.isEmpty() || !menu.getCarried().isEmpty();
                if (anything) {
                    ClientPacketDistributor.sendToServer(new RackPayloads.RackClick(menu.containerId, spot.section().rack().at(), spot.slot(),
                            there, quick));
                }
            }
            return true;
        }
        if (onTrack(x, y) && maxScroll() > 0) {
            dragging = true;
            dragTo(y);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean onTrack(double x, double y) {
        return x >= leftPos + TRACK_X - 1 && x < leftPos + TRACK_X + 11 && y >= topPos + LIST_Y && y < topPos + LIST_Y + LIST_H;
    }

    private void dragTo(double y) {
        double fraction = (y - topPos - LIST_Y - KNOB / 2.0) / (LIST_H - KNOB);
        scroll = (int) Math.round(Math.clamp(fraction, 0, 1) * maxScroll());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            dragTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (inList(x, y) || onTrack(x, y)) {
            scroll = Math.clamp(scroll - (int) Math.signum(scrollY) * SCROLL_STEP, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (search.isFocused() && !event.isEscape()) {
            search.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return search.isFocused() ? search.charTyped(event) : super.charTyped(event);
    }
}
