package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftRule;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The Deck screen: wafer slots in a side panel on the left; in the main panel, charge and wafer status, a
 * searchable, scrollable grid of everything on the Deck's wafers, and the player's inventory. A Crafting Deck adds a
 * panel on the right with its 3×3 crafting grid. Right-clicking a wafer opens its ordered filters in a
 * window on top, which can be dragged by its title bar.
 */
public class DeckScreen extends AbstractContainerScreen<DeckMenu> {
    /** Width of the main panel; the side panels add to it. */
    private static final int MAIN_WIDTH = DeckMenu.MAIN_WIDTH;
    private static final int COLUMNS = 9;
    /** Grid rows at the smallest size; the other sizes share out the room the game window has left. */
    private static final int SMALL_ROWS = 6;
    /** Room kept free above and below the screen at the largest size. */
    private static final int MARGIN = 16;
    /** The Rules tab lists taller rows, two lines of text each: five fit where the grid shows six. */
    private static final int RULE_ROW = 21;
    private static final int CHARGE_WIDTH = 60;
    private static final int LINK_SIZE = 5;
    private static final int SCROLL_WIDTH = 10;
    private static final int HANDLE_HEIGHT = 15;

    private static final JasmButton.Icon SORT_NAME = new JasmButton.Icon(Jasm.id("icon/sort_name"), 7, 5);
    private static final JasmButton.Icon SORT_AMOUNT = new JasmButton.Icon(Jasm.id("icon/sort_amount"), 7, 5);
    private static final JasmButton.Icon ARROW_UP = new JasmButton.Icon(Jasm.id("icon/arrow_up"), 5, 3);
    private static final JasmButton.Icon ARROW_DOWN = new JasmButton.Icon(Jasm.id("icon/arrow_down"), 5, 3);
    private static final JasmButton.Icon ARROW_LEFT = new JasmButton.Icon(Jasm.id("icon/arrow_left"), 3, 5);
    private static final Identifier CRAFT_ARROW = Jasm.id("icon/craft_arrow");
    private static final Identifier CRAFTABLE = Jasm.id("icon/craftable");
    private static final JasmButton.Icon JOBS = new JasmButton.Icon(Jasm.id("icon/jobs"), 8, 8);
    private static final JasmButton.Icon[] SIZE_ICONS = {
            new JasmButton.Icon(Jasm.id("icon/size_small"), 7, 8), new JasmButton.Icon(Jasm.id("icon/size_medium"), 7, 8),
            new JasmButton.Icon(Jasm.id("icon/size_tall"), 7, 8), new JasmButton.Icon(Jasm.id("icon/size_full"), 7, 8)};
    private static final Identifier RULE_ON = Jasm.id("icon/rule_on");
    private static final Identifier RULE_WAITING = Jasm.id("icon/rule_waiting");
    private static final Identifier RULE_OFF = Jasm.id("icon/rule_off");

    private EditBox search;
    private GridEntries.Sort sort = GridEntries.Sort.NAME;
    private boolean ascending = true;
    private int scrollRow;
    private int builtVersion = -1;
    private String builtQuery = "";
    private List<GridEntries.Entry<ItemResource>> visible = List.of();
    private boolean draggingHandle;
    /** Left edge of the main panel, and the grid and scroll bar inside it. */
    private final int mainX;
    private final int gridX;
    private final int scrollX;
    /** Grid rows at the chosen size; grid and status line sit just above the inventory. */
    private int rows = SMALL_ROWS;
    private int gridY;
    private int statusY;
    private Button sizeButton;
    private WaferFilterWindow filterWindow;

    /** A Crafting Deck's two views of its grid: what is stored, and what its network can craft. */
    private enum Tab { ITEMS, CRAFT, RULES }

    private Tab tab = Tab.ITEMS;
    private Tab builtTab = Tab.ITEMS;
    private final List<Button> tabs = new ArrayList<>();
    private CraftRequestWindow craftWindow;
    private RuleWindow ruleWindow;
    private JobsWindow jobsWindow;
    private @Nullable Button jobsButton;

    public DeckScreen(DeckMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.screenWidth(), menu.inventoryY() + 58 + 18 + 6);
        this.mainX = menu.mainX();
        this.gridX = mainX + 8;
        this.scrollX = gridX + COLUMNS * 18 + 3;
        this.titleLabelX = mainX + 8;
        this.inventoryLabelX = mainX + 8;
    }

    /**
     * Grid rows for the chosen size: the smallest always shows six, the largest as many as the game window fits, and
     * the two between split the difference.
     */
    private int rowsFor(JasmClientConfig.DeckSize size) {
        int fixed = DeckMenu.INVENTORY_Y + 58 + 18 + 6 - SMALL_ROWS * 18;
        int most = Math.max(SMALL_ROWS, (height - 2 * MARGIN - fixed) / 18);
        return SMALL_ROWS + (most - SMALL_ROWS) * size.ordinal() / (JasmClientConfig.DeckSize.values().length - 1);
    }

    /** Sizes the screen to the chosen grid height, moving the inventory down under the grid. */
    private void layout() {
        rows = rowsFor(JasmClientConfig.deckSize());
        menu.moveInventory(DeckMenu.INVENTORY_Y + (rows - SMALL_ROWS) * 18);
        imageHeight = menu.inventoryY() + 58 + 18 + 6;
        inventoryLabelY = menu.inventoryY() - 10;
        gridY = menu.inventoryY() - 12 - rows * 18;
        statusY = gridY - 11;
        scrollRow = Math.min(scrollRow, maxScroll());
    }

    private int ruleRows() {
        return rows * 18 / RULE_ROW;
    }

    private int scrollHeight() {
        return rows * 18 - 2;
    }

    /** Steps to the next size, or back one; the screen is laid out again around the new grid. */
    private void changeSize(boolean back) {
        JasmClientConfig.DeckSize[] sizes = JasmClientConfig.DeckSize.values();
        int next = Math.floorMod(JasmClientConfig.deckSize().ordinal() + (back ? -1 : 1), sizes.length);
        JasmClientConfig.setDeckSize(sizes[next]);
        rebuildWidgets();
    }

    private Component sizeLabel() {
        String name = JasmClientConfig.deckSize().name().toLowerCase(java.util.Locale.ROOT);
        return Component.translatable("screen.jasm.deck.size", Component.translatable("screen.jasm.deck.size." + name), rows);
    }

    @Override
    protected void init() {
        layout();
        super.init();
        // The search text stays when the screen is laid out again for a new size or window.
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, leftPos + mainX + SEARCH_X, topPos + 4, 51, 12, Component.translatable("screen.jasm.deck.search"));
        search.setHint(Component.translatable("screen.jasm.deck.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(text -> scrollRow = 0);
        addRenderableWidget(search);
        addRenderableWidget(JasmButton.icon(() -> sort == GridEntries.Sort.NAME ? SORT_NAME : SORT_AMOUNT, sortLabel(), b -> {
            sort = sort == GridEntries.Sort.NAME ? GridEntries.Sort.AMOUNT : GridEntries.Sort.NAME;
            b.setMessage(sortLabel());
            builtVersion = -1;
        }, leftPos + mainX + 140, topPos + 3, 14, 14));
        addRenderableWidget(JasmButton.icon(() -> ascending ? ARROW_UP : ARROW_DOWN, directionLabel(), b -> {
            ascending = !ascending;
            b.setMessage(directionLabel());
            builtVersion = -1;
        }, leftPos + mainX + 155, topPos + 3, 14, 14));
        sizeButton = JasmButton.icon(() -> SIZE_ICONS[JasmClientConfig.deckSize().ordinal()], sizeLabel(), b -> changeSize(false),
                leftPos + mainX + 170, topPos + 3, 14, 14);
        sizeButton.setTooltip(Tooltip.create(Component.empty().append(sizeLabel()).append("\n")
                .append(Component.translatable("screen.jasm.deck.size_hint").withStyle(ChatFormatting.GRAY))));
        addRenderableWidget(sizeButton);
        tabs.clear();
        craftWindow = new CraftRequestWindow(menu, font);
        ruleWindow = new RuleWindow(menu, font);
        jobsWindow = new JobsWindow(menu, font);
        jobsButton = null;
        if (menu.isCrafting()) {
            // The tabs take the title's place: a Crafting Deck's name shows in its tooltip anyway.
            for (Tab t : Tab.values()) {
                String name = t.name().toLowerCase(java.util.Locale.ROOT);
                JasmButton.Icon icon = new JasmButton.Icon(Jasm.id("icon/tab_" + name), 8, 8);
                Component label = Component.translatable("screen.jasm.deck.tab." + name);
                Button button = JasmButton.icon(() -> icon, label, b -> {
                    tab = t;
                    scrollRow = 0;
                    builtVersion = -1;
                    updateTabs();
                }, leftPos + mainX + 5 + t.ordinal() * 19, topPos + 3, 18, 14);
                button.setTooltip(Tooltip.create(label));
                tabs.add(addRenderableWidget(button));
            }
            // On the Craft tab: the list of this Deck's jobs.
            jobsButton = JasmButton.icon(() -> JOBS, Component.translatable("screen.jasm.jobs.button"), b -> openJobs(),
                    leftPos + mainX + 5 + Tab.values().length * 19 + 3, topPos + 3, 18, 14);
            jobsButton.setTooltip(Tooltip.create(Component.translatable("screen.jasm.jobs.button")));
            addRenderableWidget(jobsButton);
            updateTabs();
            Button clear = JasmButton.icon(() -> ARROW_LEFT, Component.translatable("screen.jasm.deck.clear_grid"),
                    b -> ClientPacketDistributor.sendToServer(new DeckPayloads.ClearGrid(menu.containerId)),
                    leftPos + menu.craftX() + DeckMenu.SIDE_PAD + 1, topPos + DeckMenu.CRAFT_RESULT_Y + 1, 14, 14);
            clear.setTooltip(Tooltip.create(Component.translatable("screen.jasm.deck.clear_grid")));
            addRenderableWidget(clear);
        }

        if (filterWindow == null) filterWindow = new WaferFilterWindow(menu, font);
    }

    /** The open tab's button is greyed out, so it reads as the one selected. */
    private void updateTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).active = i != tab.ordinal();
        }
        if (jobsButton != null) {
            jobsButton.visible = tab == Tab.CRAFT;
        }
    }

    /** The job list covers the main panel; other windows close. */
    private void openJobs() {
        closeSettings();
        craftWindow.close();
        ruleWindow.close();
        jobsWindow.open(leftPos + mainX, topPos, MAIN_WIDTH, imageHeight);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        craftWindow.tick();
    }

    /** Opens the request window for {@code key}, closing the wafer settings if they were open. */
    private void openCraft(ItemResource key) {
        closeSettings();
        ruleWindow.close();
        jobsWindow.close();
        craftWindow.open(key, leftPos + mainX, topPos, MAIN_WIDTH, imageHeight);
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
                Component when = rule.timed() ? Component.translatable("screen.jasm.rule.line_timed", rule.seconds())
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
                    Component tip = !rule.enabled() ? Component.translatable("screen.jasm.rule.state.off")
                            : stalled != null ? Component.translatable("screen.jasm.rule.state.waiting", Component.translatable(stalled))
                            : Component.translatable("screen.jasm.rule.state.on");
                    graphics.setTooltipForNextFrame(font, tip, mouseX, mouseY);
                }
            } else if (index == rules.size() && index < limit) {
                graphics.text(font, Component.translatable("screen.jasm.rule.add", rules.size(), limit), rx + 20, ry + 4, JasmGui.ACCENT, false);
            }
        }
    }

    // --- wafer settings ---

    private static final int SEARCH_X = 86;

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
            return ghost.isEmpty() || !slot.contains((int) mouseX, (int) mouseY) ? Optional.empty()
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
        for (ItemResource key : tab == Tab.RULES ? Set.<ItemResource>of() : view.craftable()) {
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
        JasmGui.panel(graphics, x, y, menu.sideWidth(), menu.sideHeight());
        JasmGui.panel(graphics, x + menu.upgradeX(), y + menu.upgradeY(), DeckMenu.UPGRADE_SIZE, DeckMenu.UPGRADE_SIZE);
        JasmGui.panel(graphics, x + mainX, y, MAIN_WIDTH, imageHeight);
        if (menu.isCrafting()) {
            int cx = x + menu.craftX();
            JasmGui.panel(graphics, cx, y, DeckMenu.CRAFT_WIDTH, DeckMenu.CRAFT_HEIGHT);
            // An arrow from the grid down to the result.
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CRAFT_ARROW, cx + DeckMenu.SIDE_PAD + 1 + 18 + 4,
                    y + DeckMenu.SIDE_PAD + 1 + 3 * 18 + 2, 9, 9);
        }
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        drawCharge(graphics, x, y);
        if (filterWindow != null && filterWindow.isOpen() && filterWindow.selected() < menu.slots.size()) {
            Slot wafer = menu.slots.get(filterWindow.selected());
            graphics.outline(x + wafer.x - 1, y + wafer.y - 1, 18, 18, JasmGui.ACCENT);
        }
        JasmGui.inset(graphics, x + gridX - 1, y + gridY - 1, COLUMNS * 18, rows * 18);
        drawScrollBar(graphics, x, y);
    }

    /** A track beside the grid with a handle; the handle is greyed out when everything fits without scrolling. */
    private void drawScrollBar(GuiGraphicsExtractor graphics, int x, int y) {
        JasmGui.scrollBar(graphics, x + scrollX, y + gridY - 1, SCROLL_WIDTH, scrollHeight() + 2, handleOffset(), HANDLE_HEIGHT, maxScroll() > 0);
    }

    private int handleOffset() {
        int travel = scrollHeight() - HANDLE_HEIGHT;
        return maxScroll() == 0 ? 0 : Math.round(travel * scrollRow / (float) maxScroll());
    }

    private boolean onScrollBar(double mouseX, double mouseY) {
        return mouseX >= leftPos + scrollX && mouseX < leftPos + scrollX + SCROLL_WIDTH
                && mouseY >= topPos + gridY - 1 && mouseY < topPos + gridY + scrollHeight() + 1;
    }

    /** Scrolls so the handle's middle sits under the mouse. */
    private void scrollToMouse(double mouseY) {
        float travel = scrollHeight() - HANDLE_HEIGHT;
        float along = (float) (mouseY - (topPos + gridY) - HANDLE_HEIGHT / 2.0) / travel;
        scrollRow = Math.max(0, Math.min(maxScroll(), Math.round(along * maxScroll())));
    }

    private DeckTier tier() {
        return menu.deck().getItem() instanceof DeckItem deck ? deck.tier() : DeckTier.STARTER;
    }

    private void drawCharge(GuiGraphicsExtractor graphics, int x, int y) {
        int bx = x + mainX + MAIN_WIDTH - 8 - CHARGE_WIDTH;
        JasmGui.bar(graphics, bx - 1, y + statusY, CHARGE_WIDTH + 2, 7, menu.view().energy() / (double) tier().battery());
        if (menu.isCrafting()) {
            // The link light: green linked, yellow linked but out of reach, grey not linked.
            int lx = x + linkX();
            int colour = switch (menu.view().network()) {
                case 2 -> JasmGui.GOOD;
                case 1 -> JasmGui.WARN;
                default -> JasmGui.MUTED;
            };
            graphics.fill(lx, y + statusY + 1, lx + LINK_SIZE, y + statusY + 1 + LINK_SIZE, colour);
        }
    }

    /** Left edge of the link light, just before the charge bar. */
    private int linkX() {
        return mainX + MAIN_WIDTH - 8 - CHARGE_WIDTH - 4 - LINK_SIZE;
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
        if (filterWindow.isOpen() && !(filterWindow.selected() < menu.view().slots().size() && menu.view().slots().get(filterWindow.selected()).present())) {
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
        int barLeft = x + mainX + MAIN_WIDTH - 8 - CHARGE_WIDTH;
        if (mouseX >= barLeft && mouseX < barLeft + CHARGE_WIDTH && mouseY >= y + statusY && mouseY < y + statusY + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("tooltip.jasm.deck.energy",
                    String.format("%,d", menu.view().energy()), String.format("%,d", tier().battery())), mouseX, mouseY);
        }
        int linkLeft = x + linkX();
        if (menu.isCrafting() && mouseX >= linkLeft - 1 && mouseX < linkLeft + LINK_SIZE + 1 && mouseY >= y + statusY && mouseY < y + statusY + 7) {
            String key = switch (menu.view().network()) {
                case 2 -> "screen.jasm.deck.link.linked";
                case 1 -> "screen.jasm.deck.link.unreachable";
                default -> "screen.jasm.deck.link.not_linked";
            };
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable(key), 180), mouseX, mouseY);
        }
        if (menu.isCrafting() && tab == Tab.CRAFT && menu.view().network() < 2 && hasPower()) {
            graphics.fill(x + gridX - 1, y + gridY - 1, x + gridX + COLUMNS * 18 - 1, y + gridY + rows * 18 - 1, JasmGui.SHADE);
            Component text = Component.translatable(menu.view().network() == 0 ? "screen.jasm.craft.not_paired" : "screen.jasm.craft.unreachable");
            List<net.minecraft.util.FormattedCharSequence> wrapped = font.split(text, COLUMNS * 18 - 12);
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
        // Long names are cut short so they never run under the search box.
        int room = SEARCH_X - 8 - 3;
        String name = title.getString();
        if (font.width(name) > room) {
            name = font.plainSubstrByWidth(name, room - font.width("...")) + "...";
        }
        if (!menu.isCrafting()) {
            graphics.text(font, name, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        }
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
        if (menu.view().jobs().stream().anyMatch(job -> job.pause() == 4)) {
            // A job's results are waiting for room: say so until they are delivered.
            Component banner = Component.translatable("screen.jasm.craft.banner_full");
            graphics.text(font, banner, mainX + 8, statusY, JasmGui.BAD, false);
            return;
        }
        // Items from missing mods still take up space; the usage turns red and each wafer's tooltip says how many.
        Component status = Component.translatable("screen.jasm.deck.usage", GridEntries.abbreviate(used), GridEntries.abbreviate(capacity));
        graphics.text(font, status, mainX + 8, statusY, missing > 0 ? JasmGui.BAD : JasmGui.SUBTEXT, false);
        if (types > 0) {
            // Type Wafers: types used, right-aligned before the charge bar, when there is room for both.
            Component typeStatus = Component.translatable("screen.jasm.deck.types", typesUsed, types);
            int right = (menu.isCrafting() ? linkX() : mainX + MAIN_WIDTH - 8 - CHARGE_WIDTH) - 5;
            if (mainX + 8 + font.width(status) + 6 + font.width(typeStatus) <= right) {
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
            lines.add(Component.translatable("screen.jasm.deck.wafer_used", String.format("%,d", status.used()), String.format("%,d", status.capacity()))
                    .withStyle(ChatFormatting.GRAY));
            if (status.types() > 0) {
                lines.add(Component.translatable("screen.jasm.deck.wafer_types", status.typesUsed(), status.types()).withStyle(ChatFormatting.GRAY));
            }
            WaferSettings settings = status.settings();
            if (!settings.rules().isEmpty()) lines.add(Component.translatable("screen.jasm.filter.count", settings.rules().size()).withStyle(ChatFormatting.GRAY));
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

    /** Below the side panel is outside the screen, so items dropped there fall out as usual. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        if (inWindow(mouseX, mouseY)) {
            return false;
        }
        if (mouseX >= left + menu.upgradeX() && mouseX < left + menu.upgradeX() + DeckMenu.UPGRADE_SIZE
                && mouseY >= top + menu.upgradeY() && mouseY < top + menu.upgradeY() + DeckMenu.UPGRADE_SIZE) return false;
        boolean belowSide = mouseX < left + mainX && mouseY >= top + menu.sideHeight();
        boolean belowCraft = menu.isCrafting() && mouseX >= left + menu.craftX() && mouseY >= top + DeckMenu.CRAFT_HEIGHT;
        return belowSide || belowCraft || super.hasClickedOutside(mouseX, mouseY, left, top);
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
                DeckPayloads.ExtractMode mode = event.hasShiftDown() ? DeckPayloads.ExtractMode.TO_INVENTORY
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
