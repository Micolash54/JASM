package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftRule;
import dev.micolash.jasm.autocraft.PauseReason;
import dev.micolash.jasm.autocraft.Rules;
import dev.micolash.jasm.config.JasmClientConfig;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.core.SearchQuery;
import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.deck.DeckPayloads;
import dev.micolash.jasm.deck.DeckStorage;
import dev.micolash.jasm.deck.DeckTier;
import dev.micolash.jasm.deck.DeckView;
import dev.micolash.jasm.storage.WaferSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Deck screen: wafer slots in a side panel on the left; in the main panel, search and sorting, charge and wafer
 * status, a scrollable grid of everything on the Deck's wafers, and the player's inventory, with the scroll bar in a
 * column on the right and the tab keys beside it. A Crafting Deck adds a box under the grid with the player, their
 * armour and off-hand, and the 3×3 crafting grid, and the Craft, Rules and Jobs keys. Right-clicking a wafer opens its
 * ordered filters in a window on top.
 */
public class DeckScreen extends JasmScreen<DeckMenu> {
    /** Width of the main panel; the side panels add to it. */
    private static final int MAIN_WIDTH = DeckMenu.MAIN_WIDTH;
    private static final int COLUMNS = 10;
    /** Grid rows at the smallest size; the other sizes share out the room the game window has left. */
    private static final int SMALL_ROWS = 6;
    /** Room kept free above and below the screen at the largest size. */
    private static final int MARGIN = 16;
    /** The Rules tab lists taller rows, two lines of text each: five fit where the grid shows six. */
    private static final int RULE_ROW = 21;
    private static final int CHARGE_WIDTH = 76;
    private static final int LINK_SIZE = 6;
    /** The scroll track sits in its own column right of the grid, from just under the search row to the grid's end. */
    private static final int TRACK_Y = 31;
    private static final int HANDLE_HEIGHT = 18;
    /** The search row along the top: the field, then the sort and size keys. */
    private static final int KEY = 16;
    private static final int KEY_Y = 5;
    /** The tab keys down the right-hand column, sharing their outlines. */
    private static final int TAB_WIDTH = 21;
    private static final int TAB_HEIGHT = 22;
    private static final int TAB_Y = 29;

    private static final JasmButton.Icon SORT_NAME = new JasmButton.Icon(Jasm.id("icon/sort_name"), 8, 5);
    private static final JasmButton.Icon SORT_AMOUNT = new JasmButton.Icon(Jasm.id("icon/sort_amount"), 7, 5);
    private static final JasmButton.Icon UP = new JasmButton.Icon(Jasm.id("icon/triangle_up"), 6, 4);
    private static final JasmButton.Icon DOWN = new JasmButton.Icon(Jasm.id("icon/triangle_down"), 6, 4);
    private static final Identifier CRAFT_ARROW = Jasm.id("icon/craft_arrow_wide");
    private static final Identifier CRAFTABLE = Jasm.id("icon/craftable");
    private static final JasmButton.Icon[] TAB_ICONS = {
            new JasmButton.Icon(Jasm.id("icon/tab_items"), 11, 10), new JasmButton.Icon(Jasm.id("icon/tab_craft"), 11, 11),
            new JasmButton.Icon(Jasm.id("icon/tab_rules"), 11, 11), new JasmButton.Icon(Jasm.id("icon/tab_network"), 11, 11)};
    private static final JasmButton.Icon JOBS = new JasmButton.Icon(Jasm.id("icon/jobs"), 11, 13);
    private static final JasmButton.Icon[] SIZE_ICONS = {
            new JasmButton.Icon(Jasm.id("icon/size_small"), 8, 7), new JasmButton.Icon(Jasm.id("icon/size_medium"), 8, 7),
            new JasmButton.Icon(Jasm.id("icon/size_tall"), 8, 7), new JasmButton.Icon(Jasm.id("icon/size_full"), 8, 7)};
    private static final Identifier RULE_ON = Jasm.id("icon/rule_on");
    private static final Identifier RULE_WAITING = Jasm.id("icon/rule_waiting");
    private static final Identifier RULE_OFF = Jasm.id("icon/rule_off");
    private static final int WELL = 0xFF1E1E2E;
    private static final int WELL_LIGHT = 0xFF585B70;
    private static final int WELL_CORNER = 0xFF45475A;
    private static final int BOX = 0xFF313244;
    private static final int BOX_SHADE = 0xFF181825;

    private EditBox search;
    private GridEntries.Sort sort = GridEntries.Sort.NAME;
    private boolean ascending = true;
    private int scrollRow;
    private int builtVersion = -1;
    private String builtQuery = "";
    private List<GridEntries.Entry<ItemResource>> visible = List.of();
    private boolean draggingHandle;
    /** Left edge of the main panel, and the grid and scroll track inside it. */
    private final int mainX;
    private final int gridX;
    private final int trackX;
    private final int gridY = DeckMenu.GRID_Y;
    private final int statusY = 24;
    /** Grid rows at the chosen size. */
    private int rows = SMALL_ROWS;
    private Button sizeButton;
    private WaferFilterWindow filterWindow;
    /** The Network tab: kept while the screen is open, so it remembers list or tree. */
    private @Nullable NetworkPanel networkPanel;

    /** The views of the grid: what is stored, what the network can craft, the rules, and the network. */
    private enum Tab {
        ITEMS,
        CRAFT,
        RULES,
        NETWORK
    }

    /** The tabs this Deck has, top to bottom: only a Crafting Deck crafts and holds rules. */
    private final List<Tab> shown;
    private Tab tab = Tab.ITEMS;
    private Tab builtTab = Tab.ITEMS;
    private final List<JasmButton> tabs = new ArrayList<>();
    private CraftRequestWindow craftWindow;
    private RuleWindow ruleWindow;
    private JobsWindow jobsWindow;
    private @Nullable Button jobsButton;

    public DeckScreen(DeckMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.screenWidth(), menu.screenHeight());
        this.mainX = menu.mainX();
        this.gridX = mainX + 12;
        this.trackX = mainX + 203;
        this.inventoryLabelX = mainX + 21;
        this.shown = menu.isCrafting() ? List.of(Tab.ITEMS, Tab.CRAFT, Tab.RULES, Tab.NETWORK) : List.of(Tab.ITEMS, Tab.NETWORK);
    }

    /**
     * Grid rows for the chosen size: the smallest always shows six, the largest as many as the game window fits, and
     * the two between split the difference.
     */
    private int rowsFor(JasmClientConfig.DeckSize size) {
        int fixed = menu.screenHeight() - menu.rows() * 18;
        int most = Math.max(SMALL_ROWS, (height - 2 * MARGIN - fixed) / 18);
        return SMALL_ROWS + (most - SMALL_ROWS) * size.ordinal() / (JasmClientConfig.DeckSize.values().length - 1);
    }

    /** Sizes the screen to the chosen grid height, moving everything under the grid down. */
    private void layout() {
        rows = rowsFor(JasmClientConfig.deckSize());
        menu.layout(rows);
        imageHeight = menu.screenHeight();
        inventoryLabelY = menu.wellBottom() + 4;
        frame = null;
        scrollRow = Math.min(scrollRow, maxScroll());
    }

    private int ruleRows() {
        return rows * 18 / RULE_ROW;
    }

    /** Height of the scroll track, which runs down beside the grid. */
    private int trackHeight() {
        return menu.gridEnd() - TRACK_Y;
    }

    /** Steps to the next size, or back one; the screen is laid out again around the new grid. */
    private void changeSize(boolean back) {
        JasmClientConfig.DeckSize[] sizes = JasmClientConfig.DeckSize.values();
        int next = Math.floorMod(JasmClientConfig.deckSize().ordinal() + (back ? -1 : 1), sizes.length);
        JasmClientConfig.setDeckSize(sizes[next]);
        rebuildWidgets();
    }

    private Component sizeLabel() {
        String name = JasmClientConfig.deckSize().name().toLowerCase(Locale.ROOT);
        return Component.translatable("screen.jasm.deck.size", Component.translatable("screen.jasm.deck.size." + name), rows);
    }

    @Override
    protected void init() {
        layout();
        super.init();
        // The search text stays when the screen is laid out again for a new size or window.
        String query = search == null ? "" : search.getValue();
        search = new JasmField(font, leftPos + mainX + 5, topPos + 6, 145, 14, Component.translatable("screen.jasm.deck.search"));
        search.setHint(Component.translatable("screen.jasm.deck.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(text -> scrollRow = 0);
        addRenderableWidget(search);
        // The three keys right of the search field share their outlines.
        int keyX = leftPos + mainX + 152;
        addRenderableWidget(JasmButton.icon(() -> sort == GridEntries.Sort.NAME ? SORT_NAME : SORT_AMOUNT, sortLabel(), b -> {
            sort = sort == GridEntries.Sort.NAME ? GridEntries.Sort.AMOUNT : GridEntries.Sort.NAME;
            b.setMessage(sortLabel());
            builtVersion = -1;
        }, keyX, topPos + KEY_Y, KEY, KEY));
        addRenderableWidget(JasmButton.icon(() -> ascending ? UP : DOWN, directionLabel(), b -> {
            ascending = !ascending;
            b.setMessage(directionLabel());
            builtVersion = -1;
        }, keyX + KEY - 1, topPos + KEY_Y, KEY, KEY));
        sizeButton = JasmButton.icon(() -> SIZE_ICONS[JasmClientConfig.deckSize().ordinal()], sizeLabel(), b -> changeSize(false),
                keyX + 2 * (KEY - 1), topPos + KEY_Y, KEY, KEY);
        sizeButton.setTooltip(Tooltip.create(Component.empty().append(sizeLabel()).append("\n")
                .append(Component.translatable("screen.jasm.deck.size_hint").withStyle(ChatFormatting.GRAY))));
        addRenderableWidget(sizeButton);
        tabs.clear();
        craftWindow = new CraftRequestWindow(menu, font);
        ruleWindow = new RuleWindow(menu, font);
        jobsWindow = new JobsWindow(menu, font);
        jobsButton = null;
        int tabX = leftPos + mainX + 217;
        for (int i = 0; i < shown.size(); i++) {
            Tab t = shown.get(i);
            Component label = Component.translatable("screen.jasm.deck.tab." + t.name().toLowerCase(Locale.ROOT));
            JasmButton.Icon icon = TAB_ICONS[t.ordinal()];
            JasmButton button = JasmButton.icon(() -> icon, label, b -> {
                jobsWindow.close();
                tab = t;
                scrollRow = 0;
                builtVersion = -1;
                if (t == Tab.NETWORK) networkPanel.open();
                updateTabs();
            }, tabX, topPos + TAB_Y + i * (TAB_HEIGHT - 1), TAB_WIDTH, TAB_HEIGHT);
            button.setTooltip(Tooltip.create(label));
            tabs.add(addRenderableWidget(button));
        }
        if (menu.isCrafting()) {
            // The job list is the last key: it can be opened from any of the tabs.
            jobsButton = JasmButton.icon(() -> JOBS, Component.translatable("screen.jasm.jobs.button"),
                    b -> {
                        if (jobsWindow.isOpen()) jobsWindow.close();
                        else openJobs();
                        updateTabs();
                    },
                    tabX, topPos + TAB_Y + shown.size() * (TAB_HEIGHT - 1), TAB_WIDTH, TAB_HEIGHT);
            jobsButton.setTooltip(Tooltip.create(Component.translatable("screen.jasm.jobs.button")));
            addRenderableWidget(jobsButton);
            // Beside the crafting grid: send what is in it back to the Deck, or down to the inventory.
            int keysX = leftPos + mainX + 154;
            int gridTop = topPos + menu.craftY() + 12;
            Button toDeck = JasmButton.icon(() -> UP, Component.translatable("screen.jasm.deck.grid_to_deck"),
                    b -> ClientPacketDistributor.sendToServer(new DeckPayloads.ClearGrid(menu.containerId, false)), keysX, gridTop, 14, 14);
            toDeck.setTooltip(Tooltip.create(Component.translatable("screen.jasm.deck.grid_to_deck")));
            addRenderableWidget(toDeck);
            Button toInventory = JasmButton.icon(() -> DOWN, Component.translatable("screen.jasm.deck.grid_to_inventory"),
                    b -> ClientPacketDistributor.sendToServer(new DeckPayloads.ClearGrid(menu.containerId, true)), keysX, gridTop + 38, 14, 14);
            toInventory.setTooltip(Tooltip.create(Component.translatable("screen.jasm.deck.grid_to_inventory")));
            addRenderableWidget(toInventory);
        }
        if (networkPanel == null) networkPanel = new NetworkPanel(menu, font);
        networkPanel.place(leftPos + gridX, topPos + gridY, COLUMNS * 18, rows);
        addRenderableWidget(networkPanel.key());
        updateTabs();

        if (filterWindow == null) filterWindow = new WaferFilterWindow(menu, font);
    }

    /** The open tab's button stays pressed in; while the job list is open, its button is the pressed one. */
    private void updateTabs() {
        boolean jobs = jobsWindow != null && jobsWindow.isOpen();
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).setSelected(!jobs && i == shown.indexOf(tab));
        }
        if (jobsButton instanceof JasmButton button) button.setLatched(jobs);
        if (networkPanel != null) networkPanel.key().visible = tab == Tab.NETWORK;
    }

    /** The job list covers the main panel, including its wider search row; other windows close. */
    private void openJobs() {
        closeSettings();
        craftWindow.close();
        ruleWindow.close();
        jobsWindow.open(leftPos + mainX - 3, topPos, MAIN_WIDTH + 5, imageHeight);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        craftWindow.tick();
        if (tab == Tab.NETWORK) networkPanel.tick();
    }

    @Override
    public void removed() {
        super.removed();
        if (networkPanel != null) networkPanel.close();
    }

    /** Opens the request window for {@code key}, closing the wafer settings if they were open. */
    private void openCraft(ItemResource key) {
        closeSettings();
        ruleWindow.close();
        jobsWindow.close();
        craftWindow.open(key, leftPos + mainX - 3, topPos, MAIN_WIDTH + 5, imageHeight);
    }

    /** The Deck as the client has it now; the slot's stack is replaced whenever the server sends it again. */
    private ItemStack currentDeck() {
        return minecraft.player.getInventory().getItem(menu.deckSlot());
    }

    private List<CraftRule> rules() {
        return Rules.of(currentDeck());
    }

    /** The rule row under the mouse on the Rules tab: a rule's index, the "add" row (the rule count), or -1. */
    private int ruleRowAt(double mouseX, double mouseY) {
        if (!inGrid(mouseX, mouseY)) {
            return -1;
        }
        int row = (int) Math.floor((mouseY - topPos - gridY) / RULE_ROW);
        if (row >= ruleRows()) {
            return -1;
        }
        row += scrollRow;
        int count = rules().size();
        return row < count || row == count && count < Rules.limit(currentDeck()) ? row : -1;
    }

    private String trimmed(String text, int room) {
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("...")) + "...";
    }

    private void drawRules(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<CraftRule> rules = rules();
        int limit = Rules.limit(currentDeck());
        int hovered = ruleRowAt(mouseX, mouseY);
        for (int row = 0; row < ruleRows(); row++) {
            int index = scrollRow + row;
            int ry = topPos + gridY + row * RULE_ROW;
            int rx = leftPos + gridX;
            if (index == hovered) {
                graphics.fill(rx, ry, rx + COLUMNS * 18 - 2, ry + RULE_ROW - 1, JasmGui.HOVER);
            }
            ry += 2;
            if (index < rules.size()) {
                CraftRule rule = rules.get(index);
                graphics.item(rule.item().toStack(1), rx, ry);
                // Two short lines: when it runs, then what it crafts and where it goes.
                Component when = rule.timed()
                        ? Component.translatable("screen.jasm.rule.line_timed", rule.seconds())
                        : Component.translatable("screen.jasm.rule.line_below", GridEntries.abbreviate(rule.threshold()));
                Component what = Component.translatable(rule.toPlayer() ? "screen.jasm.rule.line_to_player" : "screen.jasm.rule.line_to_deck",
                        GridEntries.abbreviate(rule.amount()));
                int room = COLUMNS * 18 - 20 - 16;
                graphics.text(font, trimmed(when.getString(), room), rx + 20, ry, rule.enabled() ? JasmGui.TEXT : JasmGui.MUTED, false);
                graphics.text(font, trimmed(what.getString(), room), rx + 20, ry + 10, JasmGui.MUTED, false);
                String stalled = menu.view().stalled().get(rule.id());
                Identifier icon = !rule.enabled() ? RULE_OFF : stalled != null ? RULE_WAITING : RULE_ON;
                int ix = rx + COLUMNS * 18 - 13;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, ix, ry + 4, 8, 8);
                if (mouseX >= ix - 1 && mouseX < ix + 9 && mouseY >= ry + 3 && mouseY < ry + 13) {
                    Component tip = !rule.enabled()
                            ? Component.translatable("screen.jasm.rule.state.off")
                            : stalled != null
                                    ? Component.translatable("screen.jasm.rule.state.waiting", Component.translatable(stalled))
                                    : Component.translatable("screen.jasm.rule.state.on");
                    graphics.setTooltipForNextFrame(font, tip, mouseX, mouseY);
                }
            } else if (index == rules.size() && index < limit) {
                graphics.text(font, Component.translatable("screen.jasm.rule.add", rules.size(), limit), rx + 20, ry + 4, JasmGui.ACCENT, false);
            }
        }
    }

    // --- wafer settings ---

    private boolean inWindow(double mouseX, double mouseY) {
        return filterWindow != null && filterWindow.contains(mouseX, mouseY);
    }

    private void openSettings(int slot) {
        search.setFocused(false);
        craftWindow.close();
        ruleWindow.close();
        jobsWindow.close();
        filterWindow.open(slot, leftPos + mainX, topPos + 20, width, height);
    }

    private void closeSettings() {
        if (filterWindow != null) filterWindow.close();
    }

    /** An item dragged from JEI fills the new filter's ghost input. */
    public void setFilter(int index, @Nullable Item item) {
        if (filterWindow != null && filterWindow.isOpen()) filterWindow.setItem(item == null ? ItemStack.EMPTY : new ItemStack(item));
    }

    // --- for item list mods (JEI) ---

    /** An item drawn on this screen outside the normal slots, and where it is drawn. */
    public record ShownItem(ItemStack stack, int x, int y) {}

    /** The settings window's area on screen, with its shadow, while it is open. */
    public Optional<Rect2i> settingsWindowArea() {
        if (craftWindow != null && craftWindow.isOpen()) {
            return craftWindow.area();
        }
        if (ruleWindow != null && ruleWindow.isOpen()) {
            return ruleWindow.area();
        }
        return filterWindow == null ? Optional.empty() : filterWindow.area();
    }

    /** The rule window's item slot while it is open, for dropping items from JEI. */
    public Optional<Rect2i> ruleSlotArea() {
        return ruleWindow != null && ruleWindow.isOpen() ? Optional.of(ruleWindow.slotArea()) : Optional.empty();
    }

    /** Puts an item dragged from JEI into the open rule window. */
    public void setRuleItem(ItemStack stack) {
        if (ruleWindow != null && ruleWindow.isOpen()) {
            ruleWindow.setItem(stack);
        }
    }

    /** The new filter's ghost slot while the settings window is open. */
    public List<Rect2i> filterSlotAreas() {
        return filterWindow != null && filterWindow.isOpen() ? List.of(filterWindow.slotArea()) : List.of();
    }

    /** The grid item or filter item under the mouse. */
    public Optional<ShownItem> itemAt(double mouseX, double mouseY) {
        if (inWindow(mouseX, mouseY)) {
            ItemStack ghost = filterWindow.ghost();
            Rect2i slot = filterWindow.slotArea();
            return ghost.isEmpty() || !slot.contains((int) mouseX, (int) mouseY)
                    ? Optional.empty()
                    : Optional.of(new ShownItem(ghost, slot.getX(), slot.getY()));
        }
        GridEntries.Entry<ItemResource> entry = entryAt(mouseX, mouseY);
        if (entry == null) {
            return Optional.empty();
        }
        int column = (int) Math.floor((mouseX - leftPos - gridX) / 18);
        int row = (int) Math.floor((mouseY - topPos - gridY) / 18);
        return Optional.of(new ShownItem(entry.key().toStack(1), leftPos + gridX + column * 18, topPos + gridY + row * 18));
    }

    private Component sortLabel() {
        return Component.translatable(sort == GridEntries.Sort.NAME ? "screen.jasm.deck.sort_name" : "screen.jasm.deck.sort_amount");
    }

    private Component directionLabel() {
        return Component.translatable(ascending ? "screen.jasm.deck.ascending" : "screen.jasm.deck.descending");
    }

    // --- grid contents ---

    private void rebuildIfNeeded() {
        DeckView view = menu.view();
        if (view.version() == builtVersion && search.getValue().equals(builtQuery) && tab == builtTab) {
            return;
        }
        builtVersion = view.version();
        builtQuery = search.getValue();
        builtTab = tab;
        List<GridEntries.Entry<ItemResource>> entries = new ArrayList<>();
        if (tab == Tab.ITEMS) {
            for (Map.Entry<ItemResource, Long> e : view.contents().entrySet()) {
                entries.add(entry(e.getKey(), e.getValue()));
            }
        }
        // Add unstored craftable items on Items; Craft shows every known recipe output.
        for (ItemResource key : tab == Tab.RULES || tab == Tab.NETWORK ? Set.<ItemResource>of() : view.craftable()) {
            if (tab == Tab.CRAFT || !view.contents().containsKey(key)) {
                entries.add(entry(key, view.contents().getOrDefault(key, 0L)));
            }
        }
        visible = GridEntries.view(entries, SearchQuery.parse(builtQuery), sort, ascending);
        scrollRow = Math.min(scrollRow, maxScroll());
    }

    private static GridEntries.Entry<ItemResource> entry(ItemResource key, long count) {
        String id = key.typeHolder().getRegisteredName();
        String modId = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        return new GridEntries.Entry<>(key, key.getHoverName().getString(), modId, count);
    }

    private int maxScroll() {
        if (tab == Tab.RULES) {
            return Math.max(0, Math.min(rules().size() + 1, Rules.limit(currentDeck())) - ruleRows());
        }
        if (tab == Tab.NETWORK) {
            return networkPanel == null ? 0 : networkPanel.maxScroll();
        }
        return Math.max(0, (visible.size() + COLUMNS - 1) / COLUMNS - rows);
    }

    private GridEntries.@Nullable Entry<ItemResource> entryAt(double mouseX, double mouseY) {
        int column = (int) Math.floor((mouseX - leftPos - gridX) / 18);
        int row = (int) Math.floor((mouseY - topPos - gridY) / 18);
        if (!inGrid(mouseX, mouseY) || column < 0 || column >= COLUMNS || row < 0 || row >= rows) {
            return null;
        }
        int index = (scrollRow + row) * COLUMNS + column;
        return index < visible.size() ? visible.get(index) : null;
    }

    private boolean inGrid(double mouseX, double mouseY) {
        return mouseX >= leftPos + gridX && mouseX < leftPos + gridX + COLUMNS * 18 && mouseY >= topPos + gridY
                && mouseY < topPos + gridY + rows * 18;
    }

    private boolean hasPower() {
        return menu.view().energy() > 0;
    }

    // --- drawing ---

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        frame().draw(graphics, x, y);
        // A line between the wafers and the Dimension Upgrade slot under them.
        JasmGui.divider(graphics, x + 3, y + DeckMenu.SIDE_TOP + 9 + menu.sideRows() * 18, mainX - 5);
        drawWell(graphics, x + mainX + 5, y + 33, 194, menu.wellBottom() - 33 + 1);
        if (menu.isCrafting()) {
            int bx = x + mainX + 7;
            int by = y + menu.craftY();
            graphics.fill(bx, by, bx + 189, by + 76, BOX);
            graphics.fill(bx + 189, by, bx + 190, by + 77, BOX_SHADE);
            graphics.fill(bx, by + 76, bx + 189, by + 77, BOX_SHADE);
            // The player in a dark window between their armour and off-hand, turning to follow the mouse.
            int px = x + mainX + 28;
            int py = by + 2;
            drawSlotWell(graphics, px, py, 40, 72);
            InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, px + 1, py + 1, px + 39, py + 71, 30, 0.0625F,
                    mouseX, mouseY, minecraft.player);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CRAFT_ARROW, x + mainX + 157, by + 33, 9, 10);
        }
        for (Slot slot : menu.slots) {
            if (slot.isActive()) JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        drawCharge(graphics, x, y);
        if (filterWindow != null && filterWindow.isOpen() && filterWindow.selected() < menu.slots.size()) {
            Slot wafer = menu.slots.get(filterWindow.selected());
            graphics.outline(x + wafer.x - 1, y + wafer.y - 1, 18, 18, JasmGui.ACCENT);
        }
        JasmGui.track(graphics, x + trackX, y + TRACK_Y, trackHeight(), handleOffset(), HANDLE_HEIGHT, maxScroll() > 0);
    }

    /** The grid's well: dark, with a light edge right and below; the crafting box sits inside it. */
    private static void drawWell(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, WELL);
        graphics.fill(x + width - 1, y + 1, x + width, y + height, WELL_LIGHT);
        graphics.fill(x + 1, y + height - 1, x + width, y + height, WELL_LIGHT);
        graphics.fill(x + width - 1, y, x + width, y + 1, WELL_CORNER);
        graphics.fill(x, y + height - 1, x + 1, y + height, WELL_CORNER);
    }

    /** A slot's frame stretched to any size, filled with the panel's outline colour so the player stands out. */
    private static void drawSlotWell(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, BOX_SHADE);
        graphics.fill(x + 1, y + 1, x + width, y + height, WELL_LIGHT);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF11111B);
        graphics.fill(x + width - 1, y, x + width, y + 1, WELL_CORNER);
        graphics.fill(x, y + height - 1, x + 1, y + height, WELL_CORNER);
    }

    private JasmFrame frame;

    /**
     * The panels as one shape: the wafers on the left, the main panel with its wider search row, the scroll column on
     * the right and the tab column, as tall as its keys. Rebuilt when the grid changes height.
     */
    private JasmFrame frame() {
        if (frame == null) {
            List<int[]> rects = new ArrayList<>(List.of(
                    new int[]{mainX - 3, 0, DeckMenu.MAIN_WIDTH + 5, 31},
                    new int[]{mainX, 0, DeckMenu.MAIN_WIDTH, imageHeight},
                    new int[]{0, DeckMenu.SIDE_TOP, mainX, menu.sideRows() * 18 + 38},
                    new int[]{mainX + 190, 25, 28, menu.gridEnd() - 18}));
            // A Crafting Deck's wider column takes in the scroll column's top; a normal Deck's holds only its two keys.
            if (menu.isCrafting()) rects.add(new int[]{mainX + 190, 25, 51, tabColumnHeight(shown.size() + 1)});
            else rects.add(new int[]{mainX + 214, 25, 27, tabColumnHeight(shown.size())});
            panels = rects;
            frame = JasmFrame.rounded(rects.toArray(int[][]::new));
        }
        return frame;
    }

    private List<int[]> panels = List.of();

    /** Height of the tab column holding {@code keys} keys. */
    private static int tabColumnHeight(int keys) {
        return TAB_Y - 25 + keys * (TAB_HEIGHT - 1) + 6;
    }

    private int handleOffset() {
        int travel = trackHeight() - HANDLE_HEIGHT;
        return maxScroll() == 0 ? 0 : Math.round(travel * scrollRow / (float) maxScroll());
    }

    private boolean onScrollBar(double mouseX, double mouseY) {
        return mouseX >= leftPos + trackX - 1 && mouseX < leftPos + trackX + 11
                && mouseY >= topPos + TRACK_Y && mouseY < topPos + TRACK_Y + trackHeight();
    }

    /** Scrolls so the handle's middle sits under the mouse. */
    private void scrollToMouse(double mouseY) {
        float travel = trackHeight() - HANDLE_HEIGHT;
        float along = (float) (mouseY - (topPos + TRACK_Y) - HANDLE_HEIGHT / 2.0) / travel;
        scrollRow = Math.max(0, Math.min(maxScroll(), Math.round(along * maxScroll())));
    }

    private DeckTier tier() {
        return menu.deck().getItem() instanceof DeckItem deck ? deck.tier() : DeckTier.STARTER;
    }

    private void drawCharge(GuiGraphicsExtractor graphics, int x, int y) {
        int bx = x + mainX + 123;
        int by = y + 23;
        double charge = Math.clamp(menu.view().energy() / (double) tier().battery(), 0.0, 1.0);
        graphics.fill(bx, by, bx + CHARGE_WIDTH, by + 8, BOX_SHADE);
        graphics.fill(bx + 1, by + 1, bx + CHARGE_WIDTH - 1, by + 7, WELL);
        int filled = (int) Math.round((CHARGE_WIDTH - 2) * charge);
        if (filled > 0) JasmGui.barFill(graphics, bx + 1, by + 1, filled, 6);
        // The link light: green linked, yellow linked but out of reach, grey not linked.
        int lx = x + linkX();
        int colour = switch (menu.view().network()) {
            case 2 -> JasmGui.GOOD;
            case 1 -> JasmGui.WARN;
            default -> JasmGui.MUTED;
        };
        graphics.fill(lx, y + statusY, lx + LINK_SIZE, y + statusY + LINK_SIZE, colour);
    }

    /** Over the charge bar or the link light: the charge and whether the Deck is linked. */
    private void chargeTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = leftPos + linkX() - 1;
        int right = leftPos + mainX + 123 + CHARGE_WIDTH;
        if (mouseX < left || mouseX >= right || mouseY < topPos + 22 || mouseY >= topPos + 32) return;
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.translatable("tooltip.jasm.deck.energy",
                String.format("%,d", menu.view().energy()), String.format("%,d", tier().battery())).getVisualOrderText());
        int network = menu.view().network();
        String key = switch (network) {
            case 2 -> "screen.jasm.deck.link.linked";
            case 1 -> "screen.jasm.deck.link.unreachable";
            default -> "screen.jasm.deck.link.not_linked";
        };
        int colour = network == 2 ? JasmGui.GOOD : network == 1 ? JasmGui.WARN : JasmGui.MUTED;
        lines.addAll(font.split(Component.translatable(key).withColor(colour & 0xFFFFFF), 180));
        graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    /** Left edge of the link light, just before the charge bar. */
    private int linkX() {
        return mainX + 114;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        rebuildIfNeeded();
        if (!menu.dimensionAllowed()) {
            closeSettings();
            craftWindow.close();
            ruleWindow.close();
            jobsWindow.close();
        }
        if (filterWindow.isOpen()
                && !(filterWindow.selected() < menu.view().slots().size() && menu.view().slots().get(filterWindow.selected()).present())) {
            closeSettings();
        }
        // Under the settings or request window nothing lights up or shows a tooltip.
        boolean overWindow = inWindow(realMouseX, realMouseY) || craftWindow.contains(realMouseX, realMouseY)
                || ruleWindow.contains(realMouseX, realMouseY) || jobsWindow.contains(realMouseX, realMouseY);
        int mouseX = overWindow ? -1000 : realMouseX;
        int mouseY = overWindow ? -1000 : realMouseY;
        super.extractContents(graphics, mouseX, mouseY, a);
        if (hoveredSlot != null && hoveredSlot.index == menu.upgradeSlot()) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.deck.dimension_slot"), mouseX, mouseY);
        }
        drawGrid(graphics, mouseX, mouseY);
        chargeTooltip(graphics, mouseX, mouseY);
        // The last message lies over the bottom of the grid for a few seconds, under any open window.
        Component notice = menu.notices().current(minecraft.level.getGameTime());
        if (notice != null) {
            graphics.nextStratum();
            JasmGui.notice(graphics, font, notice, menu.notices().ok(), leftPos + gridX - 1, topPos + gridY + rows * 18 - 1, COLUMNS * 18);
        }
        if (filterWindow.isOpen()) {
            graphics.nextStratum();
            filterWindow.draw(graphics, realMouseX, realMouseY, a, width, height);
        }
        if (craftWindow.isOpen()) {
            graphics.nextStratum();
            craftWindow.draw(graphics, realMouseX, realMouseY, a);
        }
        if (ruleWindow.isOpen()) {
            graphics.nextStratum();
            ruleWindow.draw(graphics, realMouseX, realMouseY, a);
        }
        if (jobsWindow.isOpen()) {
            graphics.nextStratum();
            jobsWindow.draw(graphics, realMouseX, realMouseY, a);
        }
    }

    private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!menu.dimensionAllowed()) {
            JasmGui.interference(graphics, leftPos + gridX - 1, topPos + gridY - 1, COLUMNS * 18, rows * 18);
            var lines = font.split(Component.translatable("message.jasm.deck.dimension_upgrade"), COLUMNS * 18 - 12);
            int top = topPos + gridY + rows * 9 - lines.size() * font.lineHeight / 2;
            int textWidth = lines.stream().mapToInt(font::width).max().orElse(0);
            int textLeft = leftPos + gridX + (COLUMNS * 18 - textWidth) / 2;
            graphics.fill(textLeft - 4, top - 4, textLeft + textWidth + 4,
                    top + lines.size() * font.lineHeight + 3, JasmGui.SHADE);
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(font, lines.get(i), leftPos + gridX + (COLUMNS * 18 - font.width(lines.get(i))) / 2,
                        top + i * font.lineHeight, JasmGui.BAD, true);
            }
            return;
        }
        int x = leftPos;
        int y = topPos;
        if (tab == Tab.RULES) {
            drawRules(graphics, mouseX, mouseY);
            return;
        }
        if (tab == Tab.NETWORK) {
            networkPanel.draw(graphics, mouseX, mouseY, scrollRow);
            return;
        }
        GridEntries.Entry<ItemResource> hovered = entryAt(mouseX, mouseY);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = (scrollRow + row) * COLUMNS + column;
                if (index >= visible.size()) {
                    continue;
                }
                GridEntries.Entry<ItemResource> entry = visible.get(index);
                int sx = x + gridX + column * 18;
                int sy = y + gridY + row * 18;
                ItemStack stack = entry.key().toStack(1);
                graphics.item(stack, sx, sy);
                graphics.itemDecorations(font, stack, sx, sy, "");
                JasmGui.itemCount(graphics, font,
                        entry.count() <= 0 || tab == Tab.CRAFT ? "" : GridEntries.abbreviate(entry.count()), sx, sy);
                if (tab == Tab.CRAFT) {
                    graphics.nextStratum();
                    graphics.text(font, "+", sx + 17 - font.width("+"), sy + 9, JasmGui.ACCENT, true);
                } else if (menu.view().craftable().contains(entry.key())) {
                    graphics.nextStratum();
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CRAFTABLE, sx + 10, sy, 6, 6);
                }
                if (entry == hovered) {
                    graphics.fill(sx, sy, sx + 16, sy + 16, JasmGui.HOVER);
                }
            }
        }
        if (!hasPower()) {
            graphics.fill(x + gridX - 1, y + gridY - 1, x + gridX + COLUMNS * 18 - 1, y + gridY + rows * 18 - 1, JasmGui.SHADE);
            Component text = Component.translatable("screen.jasm.deck.no_power");
            graphics.text(font, text, x + gridX + (COLUMNS * 18 - font.width(text)) / 2, y + gridY + rows * 9 - 4, JasmGui.BAD, true);
        }
        if (menu.isCrafting() && tab == Tab.CRAFT && menu.view().network() < 2 && hasPower()) {
            graphics.fill(x + gridX - 1, y + gridY - 1, x + gridX + COLUMNS * 18 - 1, y + gridY + rows * 18 - 1, JasmGui.SHADE);
            Component text = Component.translatable(menu.view().network() == 0 ? "screen.jasm.craft.not_paired" : "screen.jasm.craft.unreachable");
            List<FormattedCharSequence> wrapped = font.split(text, COLUMNS * 18 - 12);
            for (int i = 0; i < wrapped.size(); i++) {
                graphics.text(font, wrapped.get(i), x + gridX + (COLUMNS * 18 - font.width(wrapped.get(i))) / 2,
                        y + gridY + rows * 9 - 4 - (wrapped.size() - 1) * 5 + i * 10, JasmGui.MUTED, true);
            }
        }
        if (hovered != null && menu.getCarried().isEmpty()) {
            List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(hovered.key().toStack(1)));
            lines.add(Component.translatable("screen.jasm.deck.stored", String.format("%,d", hovered.count())).withStyle(ChatFormatting.GRAY));
            if (menu.view().craftable().contains(hovered.key())) {
                lines.add(CraftRequestWindow.hint(tab == Tab.CRAFT));
            }
            graphics.setTooltipForNextFrame(font, lines, hovered.key().toStack(1).getTooltipImage(), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        long used = 0;
        long capacity = 0;
        long missing = 0;
        long typesUsed = 0;
        long types = 0;
        for (DeckStorage.SlotStatus slot : menu.view().slots()) {
            used += slot.used();
            capacity += slot.capacity();
            missing += slot.fromMissingMods();
            typesUsed += slot.typesUsed();
            types += slot.types();
        }
        if (menu.view().jobs().stream().anyMatch(job -> PauseReason.of(job.pause()) == PauseReason.WAITING_SPACE)) {
            // A job's results are waiting for room: say so until they are delivered.
            Component banner = Component.translatable("screen.jasm.craft.banner_full");
            graphics.text(font, banner, mainX + 6, statusY, JasmGui.BAD, false);
            return;
        }
        // Items from missing mods still take up space; the usage turns red and each wafer's tooltip says how many.
        Component status = Component.translatable("screen.jasm.deck.usage", GridEntries.abbreviate(used), GridEntries.abbreviate(capacity));
        graphics.text(font, status, mainX + 6, statusY, missing > 0 ? JasmGui.BAD : JasmGui.SUBTEXT, false);
        if (types > 0) {
            // Type Wafers: types used, right-aligned before the charge bar, when there is room for both.
            Component typeStatus = Component.translatable("screen.jasm.deck.types", typesUsed, types);
            int right = linkX() - 5;
            if (mainX + 6 + font.width(status) + 6 + font.width(typeStatus) <= right) {
                graphics.text(font, typeStatus, right - font.width(typeStatus), statusY, typesUsed >= types ? JasmGui.BAD : JasmGui.SUBTEXT, false);
            }
        }
    }

    /** Wafer slot tooltips add how full the wafer is and whether an Archive protects it. */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        if (hoveredSlot != null && hoveredSlot.index < menu.waferSlots() && hoveredSlot.index < menu.view().slots().size()) {
            DeckStorage.SlotStatus status = menu.view().slots().get(hoveredSlot.index);
            lines.add(Component
                    .translatable("screen.jasm.deck.wafer_used", String.format("%,d", status.used()), String.format("%,d", status.capacity()))
                    .withStyle(ChatFormatting.GRAY));
            if (status.types() > 0) {
                lines.add(Component.translatable("screen.jasm.deck.wafer_types", status.typesUsed(), status.types()).withStyle(ChatFormatting.GRAY));
            }
            WaferSettings settings = status.settings();
            if (!settings.rules().isEmpty())
                lines.add(Component.translatable("screen.jasm.filter.count", settings.rules().size()).withStyle(ChatFormatting.GRAY));
            if (status.fromMissingMods() > 0) {
                lines.add(Component.translatable("screen.jasm.deck.missing", String.format("%,d", status.fromMissingMods()))
                        .withStyle(ChatFormatting.RED));
            }
            lines.add(Component.translatable(status.linked() ? "screen.jasm.deck.linked" : "screen.jasm.deck.not_linked")
                    .withStyle(status.linked() ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        }
        return lines;
    }

    // --- input ---

    /** Only the panels count as the screen: items dropped anywhere else fall out as usual. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        if (inWindow(mouseX, mouseY)) {
            return false;
        }
        frame();
        for (int[] r : panels) {
            if (mouseX >= left + r[0] && mouseX < left + r[0] + r[2] && mouseY >= top + r[1] && mouseY < top + r[1] + r[3]) return false;
        }
        return true;
    }

    /**
     * Grid clicks, holding items: left stores all, right stores one, shift stores all. Empty-handed: left takes a
     * stack, right takes half, shift sends a stack to the inventory.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!menu.dimensionAllowed() && inGrid(event.x(), event.y())) return true;
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        // Right-click on the size button steps back a size.
        if (right && sizeButton.isMouseOver(event.x(), event.y()) && !inWindow(event.x(), event.y())) {
            sizeButton.playDownSound(minecraft.getSoundManager());
            changeSize(true);
            return true;
        }
        if (jobsWindow.contains(event.x(), event.y())) {
            return jobsWindow.mouseClicked(event, doubleClick);
        }
        if (craftWindow.contains(event.x(), event.y())) {
            return craftWindow.mouseClicked(event, doubleClick);
        }
        if (ruleWindow.contains(event.x(), event.y())) {
            return ruleWindow.mouseClicked(event, doubleClick);
        }
        // The settings window takes every click that lands on it, before the grid or tabs underneath
        // (whichever tab is open), so nothing there is touched.
        if (inWindow(event.x(), event.y())) {
            return filterWindow.mouseClicked(event, doubleClick);
        }
        // The Network tab holds no items: a click there picks a block to light up, or presses the list/tree key.
        if (tab == Tab.NETWORK && inGrid(event.x(), event.y())) {
            if (networkPanel.key().isMouseOver(event.x(), event.y())) {
                return super.mouseClicked(event, doubleClick);
            }
            networkPanel.mouseClicked(event.x(), event.y(), scrollRow);
            return true;
        }
        // The rule window stays open while items are picked up from the inventory for its slot.
        if (tab == Tab.RULES && inGrid(event.x(), event.y())) {
            int row = ruleRowAt(event.x(), event.y());
            if (row >= 0) {
                List<CraftRule> rules = rules();
                closeSettings();
                craftWindow.close();
                ruleWindow.open(row, row < rules.size() ? rules.get(row) : null, leftPos + mainX, topPos + 20);
            }
            return true;
        }
        if (craftWindow.isOpen() && !inGrid(event.x(), event.y())) {
            craftWindow.close();
        }
        // Middle-click on anything craftable, or any click on the Craft tab, opens a request.
        if (menu.isCrafting() && inGrid(event.x(), event.y()) && menu.getCarried().isEmpty()) {
            GridEntries.Entry<ItemResource> entry = entryAt(event.x(), event.y());
            boolean middle = event.button() == InputConstants.MOUSE_BUTTON_MIDDLE;
            if (entry != null && menu.view().craftable().contains(entry.key()) && (middle || tab == Tab.CRAFT)) {
                openCraft(entry.key());
                return true;
            }
            if (tab == Tab.CRAFT) {
                return true;
            }
        }
        // Right-clicking a wafer (with nothing on the cursor) opens its settings, or switches them to that wafer; the
        // same wafer again closes them.
        Slot clickedSlot = slotAt(event.x(), event.y());
        if (right && clickedSlot != null && clickedSlot.index < menu.waferSlots() && clickedSlot.hasItem() && menu.getCarried().isEmpty()) {
            if (filterWindow.selected() == clickedSlot.index) {
                closeSettings();
            } else {
                openSettings(clickedSlot.index);
            }
            return true;
        }
        if (onScrollBar(event.x(), event.y()) && maxScroll() > 0) {
            draggingHandle = true;
            scrollToMouse(event.y());
            return true;
        }
        if (inGrid(event.x(), event.y())) {
            if (!menu.getCarried().isEmpty()) {
                ClientPacketDistributor.sendToServer(new DeckPayloads.Insert(menu.containerId, right && !event.hasShiftDown()));
                return true;
            }
            GridEntries.Entry<ItemResource> entry = entryAt(event.x(), event.y());
            if (entry != null) {
                DeckPayloads.ExtractMode mode = event.hasShiftDown()
                        ? DeckPayloads.ExtractMode.TO_INVENTORY
                        : right ? DeckPayloads.ExtractMode.HALF : DeckPayloads.ExtractMode.STACK;
                ClientPacketDistributor.sendToServer(new DeckPayloads.Extract(menu.containerId, entry.key(), mode));
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (craftWindow.mouseDragged(event)) {
            return true;
        }
        if (filterWindow.isOpen() && filterWindow.mouseDragged(event, width, height)) return true;
        if (ruleWindow.isOpen() && ruleWindow.mouseDragged(event, width, height)) return true;
        if (draggingHandle) {
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** Releases over the grid or scroll bar are ours; vanilla would treat them as a click on "no slot". */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (craftWindow.mouseReleased()) {
            return true;
        }
        if (filterWindow.isOpen() && filterWindow.mouseReleased(event)) return true;
        if (ruleWindow.mouseReleased()) return true;
        if (draggingHandle) {
            draggingHandle = false;
            return true;
        }
        if (inGrid(event.x(), event.y()) || onScrollBar(event.x(), event.y())) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (jobsWindow.contains(x, y)) {
            return jobsWindow.mouseScrolled(scrollY);
        }
        if (craftWindow.contains(x, y)) {
            return craftWindow.mouseScrolled(scrollY);
        }
        if (inWindow(x, y)) {
            return filterWindow.mouseScrolled(scrollY);
        }
        if (inGrid(x, y) || onScrollBar(x, y)) {
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (filterWindow.isOpen() && filterWindow.charTyped(event)) return true;
        if (craftWindow.isOpen() && craftWindow.charTyped(event)) {
            return true;
        }
        if (ruleWindow.isOpen() && ruleWindow.charTyped(event)) {
            return true;
        }
        return super.charTyped(event);
    }

    private @Nullable Slot slotAt(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            if (mouseX >= leftPos + slot.x - 1 && mouseX < leftPos + slot.x + 17 && mouseY >= topPos + slot.y - 1 && mouseY < topPos + slot.y + 17) {
                return slot;
            }
        }
        return null;
    }

    /** While typing in the search box, keys go to the box (so "e" does not close the screen). Esc closes open settings first. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (jobsWindow.isOpen() && jobsWindow.keyPressed(event)) {
            return true;
        }
        if (craftWindow.isOpen() && craftWindow.keyPressed(event)) {
            return true;
        }
        if (ruleWindow.isOpen() && ruleWindow.keyPressed(event)) {
            return true;
        }
        if (filterWindow.isOpen() && filterWindow.keyPressed(event)) return true;
        if (search.isFocused() && !event.isEscape()) {
            search.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }
}
