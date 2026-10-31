package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.AccessPortBlock;
import dev.micolash.jasm.autocraft.AccessPortBlockEntity;
import dev.micolash.jasm.autocraft.AccessPortMenu;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.autocraft.InputFace;
import dev.micolash.jasm.autocraft.ProcessingCard;
import dev.micolash.jasm.config.JasmClientConfig;
import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.network.LinkWindowCover;
import dev.micolash.jasm.transfer.PortOperations;
import dev.micolash.jasm.transfer.PortUpgradeLayout;
import dev.micolash.jasm.transfer.RedstoneMode;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Access Port's screen. The main panel, always centred, holds the name and power bar, the kinds of machine in range
 * (click one to serve it) with the port's state, the recipes it serves (click one to take it off), its incoming items
 * and the inventory. On its left, a list of the processing recipes in the network's Recipe Racks with a search box
 * (click one to serve it); the list folds away to a thin strip. A column on the right holds the side keys (Deck Link,
 * blocking mode, rename) above the upgrade slots, and the redstone key under them while a Redstone Upgrade is in.
 */
public class AccessPortScreen extends JasmScreen<AccessPortMenu> {
    private static final int WIDTH = AccessPortMenu.WIDTH;
    private static final int HEIGHT = AccessPortMenu.HEIGHT;
    private static final int KEY_X = AccessPortMenu.KEY_X;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - HELP_ROOM - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int MACHINE_Y = 23;
    private static final int MACHINE_KEY_Y = 16;
    private static final int MACHINE_KEY_WIDTH = 20;
    private static final int MACHINE_KEY_HEIGHT = 22;
    /** Served recipes: at most 16, a slot grid under their heading, lined up with the incoming items below. */
    private static final int ACTIVE_LABEL_Y = 44;
    private static final int ACTIVE_COLS = 8;
    private static final int ACTIVE_ROWS = 2;
    private static final int ACTIVE_X = (WIDTH - ACTIVE_COLS * 18) / 2 - 1;
    private static final int ACTIVE_Y = 54;
    /** How long the "port is full" line stays over the served recipes, and how long a copy or paste line does. */
    private static final long FULL_NOTICE_MS = 3_000;
    private static final long PASTE_NOTICE_MS = 4_000;
    /** The Copy and Paste keys at the right end of the heading's row; the count sits just left of them. */
    private static final int COPY_KEY_SIZE = 13;
    private static final int COPY_KEY_X = ACTIVE_X + ACTIVE_COLS * 18 - 2 * COPY_KEY_SIZE - 2;
    private static final int PASTE_KEY_X = ACTIVE_X + ACTIVE_COLS * 18 - COPY_KEY_SIZE;
    private static final int COPY_KEY_Y = ACTIVE_LABEL_Y - 4;
    private static final int COUNT_RIGHT = COPY_KEY_X - 2;
    /** The recipe list's panel, left of the main one, and its grid. */
    private static final int LIST = 113;
    private static final int LIST_X = -LIST;
    private static final int GRID_X = LIST_X + 8;
    private static final int GRID_Y = 36;
    private static final int COLS = 5;
    private static final int ROWS = 10;
    private static final int LIST_SCROLL_X = LIST_X + 100;
    /** The strip holding the fold key, on the left edge of the list, or of the main panel while the list is folded. */
    private static final int[] STRIP = {-13, 25, 16, 30};
    private static final int KNOB = 18;
    private static final int TICK_X = 11;
    private static final int MISSING_TINT = 0x66F38BA8;
    private static final int MISSING_DIM = 0x991E1E2E;
    private static final Identifier TICK = Jasm.id("icon/recipe_tick");
    private static final Identifier MISSING = Jasm.id("icon/recipe_missing");
    private static final JasmButton.Icon LINK = new JasmButton.Icon(Jasm.id("icon/deck_link"), 12, 12);
    private static final JasmButton.Icon BLOCKING_ON = new JasmButton.Icon(Jasm.id("icon_blocking_on"), 12, 12);
    private static final JasmButton.Icon BLOCKING_OFF = new JasmButton.Icon(Jasm.id("icon_blocking_off"), 12, 12);
    private static final JasmButton.Icon RENAME = new JasmButton.Icon(Jasm.id("icon/rename"), 12, 12);
    private static final JasmButton.Icon FOLD = new JasmButton.Icon(Jasm.id("icon/triangle_right"), 4, 6);
    private static final JasmButton.Icon UNFOLD = new JasmButton.Icon(Jasm.id("icon/triangle_left"), 4, 6);
    /** The Copy and Paste keys' own face, drawn as is at their 13 x 13. */
    private static final JasmButton.Skin COPY_PASTE_KEY = new JasmButton.Skin(Jasm.id("copy_paste_key"),
            Jasm.id("copy_paste_key_highlighted"), Jasm.id("copy_paste_key_disabled"), Jasm.id("copy_paste_key_pressed"));
    private static final JasmButton.Icon COPY = new JasmButton.Icon(Jasm.id("icon/copy"), 7, 7);
    private static final JasmButton.Icon PASTE = new JasmButton.Icon(Jasm.id("icon/paste"), 7, 7);


    private JasmButton blocking;
    private JasmButton link;
    private JasmButton renameKey;
    private JasmButton copyKey;
    private JasmButton pasteKey;
    private JasmButton redstone;
    private JasmButton fold;
    /** One key per kind of machine while there are several; rebuilt when the kinds change. */
    private final List<JasmButton> machineKeys = new ArrayList<>();
    private @Nullable List<AccessPortMenu.MachineView> keysFor;
    private JasmField search;
    private boolean frameRedstone;
    private boolean frameFolded;
    private @Nullable RedstoneMode shownRedstoneMode;
    private JasmFrame frame;
    private @Nullable DeckLinkWindow linkWindow;
    private @Nullable PortRenameWindow renameWindow;
    /** The name as last set from this screen; the title follows it. */
    private String label;
    private Component shownTitle;
    private int listRow;
    private boolean draggingList;
    /** The line over the served recipes: what it says, whether it is good news, and until when. */
    private Component notice = Component.empty();
    private boolean noticeOk;
    private long noticeUntil;
    /** The copied settings the Paste key's tooltip was written for, and how many pastes the server has answered. */
    private PortClipboard.@Nullable Copied pasteTipFor;
    private int seenPastes;
    /** The recipes the search lets through, worked out again only when the list or the search changes. */
    private List<ProcessingCard> shown = List.of();
    private @Nullable List<ProcessingCard> shownFrom;
    private String shownQuery = "";
    private Set<ProcessingCard> activeSet = Set.of();
    private @Nullable List<AccessPortMenu.ActiveRecipe> activeFrom;

    public AccessPortScreen(AccessPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KEY_X + JasmGui.SIDE_KEY_WIDTH + 3, HEIGHT);
        label = menu.label();
        shownTitle = title;
        inventoryLabelX = (WIDTH - 162) / 2;
        inventoryLabelY = AccessPortMenu.INVENTORY_Y - 10;
    }

    @Override
    protected LinkWindowCover linkCover() {
        return menu.linkCover();
    }

    private static boolean folded() {
        return JasmClientConfig.portRecipesFolded();
    }

    @Override
    protected void init() {
        super.init();
        // The main panel stays in the middle whether the recipe list is open or folded; on a window too narrow for the
        // open list and its fold key, it moves right just enough, the same both ways, as long as the side keys still fit.
        int leftmost = -(LIST_X + STRIP[0]);
        leftPos = Math.max((width - WIDTH) / 2, Math.min(leftmost, width - imageWidth));
        frameRedstone = menu.hasRedstoneUpgrade();
        // With the redstone key the side column reaches below the panel: lifted just enough to stay on screen.
        int[] column = PortUpgradeLayout.column(KEY_X, AccessPortMenu.SIDE_KEYS, true);
        topPos = Math.max(0, Math.min(topPos, height - (column[1] + column[3])));
        frameFolded = folded();
        frame = buildFrame();
        addHelp(WIDTH - 7, "items/access-port.md");
        Component linkLabel = Component.translatable("screen.jasm.deck_link");
        link = addRenderableWidget(JasmButton.icon(() -> LINK, linkLabel, b -> toggleLink(),
                leftPos + KEY_X, topPos + JasmGui.sideKeyY(0), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        link.setTooltip(Tooltip.create(linkLabel));
        blocking = addRenderableWidget(JasmButton.icon(() -> menu.blockingMode() ? BLOCKING_ON : BLOCKING_OFF, blockingLabel(),
                b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, AccessPortMenu.TOGGLE_BLOCKING),
                leftPos + KEY_X, topPos + JasmGui.sideKeyY(1), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        blocking.setTooltip(blockingTooltip());
        Component renameLabel = Component.translatable("screen.jasm.port.rename");
        renameKey = addRenderableWidget(JasmButton.icon(() -> RENAME, renameLabel, b -> toggleRename(),
                leftPos + KEY_X, topPos + JasmGui.sideKeyY(2), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        renameKey.setTooltip(Tooltip.create(renameLabel));
        Component copyLabel = Component.translatable("screen.jasm.port.copy");
        copyKey = addRenderableWidget(JasmButton.icon(() -> COPY, copyLabel, b -> copySettings(),
                leftPos + COPY_KEY_X, topPos + COPY_KEY_Y, COPY_KEY_SIZE, COPY_KEY_SIZE).withSkin(COPY_PASTE_KEY));
        copyKey.setTooltip(Tooltip.create(copyLabel.copy().append("\n")
                .append(Component.translatable("screen.jasm.port.copy_hint").withColor(JasmGui.MUTED & 0xFFFFFF))));
        pasteKey = addRenderableWidget(JasmButton.icon(() -> PASTE, Component.translatable("screen.jasm.port.paste"),
                b -> pasteSettings(Minecraft.getInstance().hasShiftDown()),
                leftPos + PASTE_KEY_X, topPos + COPY_KEY_Y, COPY_KEY_SIZE, COPY_KEY_SIZE).withSkin(COPY_PASTE_KEY));
        pasteTipFor = PortClipboard.get();
        pasteKey.active = pasteTipFor != null;
        pasteKey.setTooltip(pasteTooltip(pasteTipFor));
        if (linkWindow == null) {
            linkWindow = new DeckLinkWindow(font, menu.getSlot(AccessPortMenu.SLOT_DECK_IN), menu.getSlot(AccessPortMenu.SLOT_DECK_OUT),
                    menu.linkCover(), Component.translatable("screen.jasm.archive.reset_deck"),
                    () -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, AccessPortMenu.RESET_DECK));
        }
        if (renameWindow == null) {
            renameWindow = new PortRenameWindow(menu.containerId, font, this::renamed);
        }
        link.setLatched(linkWindow.isOpen());
        renameKey.setLatched(renameWindow.isOpen());
        redstone = addRenderableWidget(JasmButton.icon(
                () -> new JasmButton.Icon(Jasm.id("icon/redstone_" + menu.redstoneMode().getSerializedName()), 12, 12),
                redstoneLabel(), b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, AccessPortMenu.CYCLE_REDSTONE),
                leftPos + KEY_X, topPos + PortUpgradeLayout.redstoneY(AccessPortMenu.SIDE_KEYS), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        shownRedstoneMode = null;
        updateRedstoneButton();

        String query = search == null ? "" : search.getValue();
        search = new JasmField(font, leftPos + LIST_X + 8, topPos + 18, 102, 14, Component.translatable("screen.jasm.port.search_hint"));
        search.setHint(Component.translatable("screen.jasm.port.search_hint"));
        search.setSelectAllOnClick();
        search.setValue(query);
        search.setResponder(text -> listRow = 0);
        addRenderableWidget(search);
        // One key in the same spot either way, on the left edge of whatever is leftmost; only its arrow flips.
        fold = addRenderableWidget(JasmButton.icon(() -> folded() ? UNFOLD : FOLD, Component.translatable("screen.jasm.port.fold"),
                b -> setFolded(!folded()), 0, topPos + 29, 11, 22));
        machineKeys.clear();
        keysFor = null;
        showFold();
    }

    /** The left edge the fold key's strip hangs from: the list's while it is open, the main panel's while folded. */
    private static int foldEdge(boolean folded) {
        return folded ? 0 : LIST_X;
    }

    private JasmFrame buildFrame() {
        int edge = foldEdge(frameFolded);
        int[] strip = {edge + STRIP[0], STRIP[1], STRIP[2], STRIP[3]};
        int[] column = PortUpgradeLayout.column(KEY_X, AccessPortMenu.SIDE_KEYS, frameRedstone);
        return frameFolded ? JasmFrame.rounded(new int[]{0, 0, WIDTH, HEIGHT}, column, strip)
                : JasmFrame.rounded(new int[]{0, 0, WIDTH, HEIGHT}, column, new int[]{LIST_X, 0, LIST + 3, HEIGHT}, strip);
    }

    private void setFolded(boolean folded) {
        JasmClientConfig.setPortRecipesFolded(folded);
        showFold();
    }

    private void showFold() {
        boolean open = !folded();
        search.visible = open;
        if (!open) search.setFocused(false);
        fold.setX(leftPos + foldEdge(!open) - 11);
        Component label = Component.translatable(open ? "screen.jasm.port.fold" : "screen.jasm.port.unfold");
        fold.setMessage(label);
        fold.setTooltip(Tooltip.create(label));
        if (frameFolded != !open) {
            frameFolded = !open;
            frame = buildFrame();
        }
    }

    private Component redstoneLabel() {
        return Component.translatable("screen.jasm.transfer.redstone",
                Component.translatable("screen.jasm.transfer.redstone_" + menu.redstoneMode().getSerializedName()));
    }

    private void updateRedstoneButton() {
        boolean installed = menu.hasRedstoneUpgrade();
        redstone.visible = installed;
        redstone.active = installed;
        redstone.setLatched(menu.redstoneMode() != RedstoneMode.IGNORE);
        if (shownRedstoneMode != menu.redstoneMode()) {
            shownRedstoneMode = menu.redstoneMode();
            Component label = redstoneLabel();
            redstone.setMessage(label);
            redstone.setTooltip(Tooltip.create(label.copy().append("\n").append(Component.translatable("screen.jasm.bay.redstone_hint"))));
        }
        if (frameRedstone != installed) {
            frameRedstone = installed;
            frame = buildFrame();
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateRedstoneButton();
        PortClipboard.Copied copied = PortClipboard.get();
        pasteKey.active = copied != null;
        if (copied != pasteTipFor) {
            pasteTipFor = copied;
            pasteKey.setTooltip(pasteTooltip(copied));
        }
        if (menu.pastes() != seenPastes) {
            seenPastes = menu.pastes();
            if (menu.pasted() != null) pasted(menu.pasted());
        }
    }

    private void showNotice(Component message, boolean ok, long millis) {
        notice = message;
        noticeOk = ok;
        noticeUntil = Util.getMillis() + millis;
    }

    private static Component twoLines(Component first, Component second) {
        return Component.empty().append(first).append("\n").append(second);
    }

    /** The name the port shows as its title, cut to fit a notice line. */
    private String titleText() {
        return font.plainSubstrByWidth(shownTitle.getString(), 100);
    }

    /** Remembers this port's name and recipes for Paste. */
    private void copySettings() {
        List<ProcessingCard> recipes = new ArrayList<>();
        for (AccessPortMenu.ActiveRecipe recipe : menu.active()) recipes.add(recipe.recipe());
        PortClipboard.set(label, shownTitle.getString(), recipes);
        showNotice(Component.translatable("screen.jasm.port.copied", titleText(), recipeCount(recipes.size())), true, PASTE_NOTICE_MS);
    }

    /** Sends the copied settings: replacing this port's name and recipes, or with Shift only adding the recipes. */
    private void pasteSettings(boolean add) {
        PortClipboard.Copied copied = PortClipboard.get();
        if (copied == null) return;
        ClientPacketDistributor.sendToServer(new CraftPayloads.PortPaste(menu.containerId, copied.name(), copied.recipes(), add));
    }

    private Tooltip pasteTooltip(PortClipboard.@Nullable Copied copied) {
        Component head = Component.translatable("screen.jasm.port.paste");
        if (copied == null) {
            return Tooltip.create(head.copy().append("\n")
                    .append(Component.translatable("screen.jasm.port.paste_nothing").withColor(JasmGui.MUTED & 0xFFFFFF)));
        }
        String shown = font.plainSubstrByWidth(copied.shown(), 100);
        return Tooltip.create(head.copy().append("\n")
                .append(Component.translatable("screen.jasm.port.paste_what", shown, recipeCount(copied.recipes().size()))
                        .withColor(JasmGui.SUBTEXT & 0xFFFFFF))
                .append("\n").append(Component.translatable("screen.jasm.port.paste_click").withColor(JasmGui.MUTED & 0xFFFFFF))
                .append("\n").append(Component.translatable("screen.jasm.port.paste_shift").withColor(JasmGui.MUTED & 0xFFFFFF)));
    }

    /** The server's answer to a paste: the title follows a new name, and a line says how it went. */
    private void pasted(AccessPortMenu.Pasted result) {
        if (!result.add()) renamed(result.name());
        Component missing = Component.translatable("screen.jasm.port.paste_missing", result.missing());
        Component message;
        boolean ok = true;
        if (!result.add()) {
            message = Component.translatable("screen.jasm.port.pasted", titleText(), recipeCount(result.added()));
            if (result.missing() > 0) message = twoLines(message, missing);
        } else if (result.added() == 0 && result.notFit() == 0) {
            message = Component.translatable("screen.jasm.port.already");
        } else {
            Component notFit = Component.translatable("screen.jasm.port.not_fit", result.notFit(), AccessPortBlockEntity.MAX_RECIPES);
            Component added = result.added() == 1 ? Component.translatable("screen.jasm.port.added_one")
                    : Component.translatable("screen.jasm.port.added", result.added());
            ok = result.notFit() == 0;
            if (result.added() == 0) message = notFit;
            else if (result.notFit() > 0) message = twoLines(added, notFit);
            else message = result.missing() > 0 ? twoLines(added, missing) : added;
        }
        showNotice(message, ok, PASTE_NOTICE_MS);
    }

    private void toggleLink() {
        renameWindow.close();
        linkWindow.toggle(leftPos + KEY_X, topPos);
        link.setLatched(linkWindow.isOpen());
    }

    private void toggleRename() {
        if (linkWindow.isOpen()) linkWindow.close();
        renameWindow.toggle(leftPos + KEY_X, topPos, label);
        renameKey.setLatched(renameWindow.isOpen());
        if (renameWindow.isOpen()) search.setFocused(false);
    }

    /** The name was sent: the title shows it now, or the port's own name for an empty one. */
    private void renamed(String name) {
        label = name;
        shownTitle = name.isEmpty() ? defaultName() : Component.literal(name);
    }

    private Component defaultName() {
        BlockPos pos = menu.openedAt();
        if (pos != null && minecraft.level != null && minecraft.level.getBlockState(pos).getBlock() instanceof AccessPortBlock block) {
            return block.getName();
        }
        return Component.translatable("item.jasm.thin_access_port");
    }

    /** Where the recipe list (or the strip that brings it back) and any open window are, so JEI's lists stay clear of them. */
    public List<Rect2i> extraAreas() {
        List<Rect2i> areas = new ArrayList<>();
        int edge = foldEdge(folded());
        areas.add(new Rect2i(leftPos + edge + STRIP[0], topPos + STRIP[1], -STRIP[0], STRIP[3]));
        if (!folded()) areas.add(new Rect2i(leftPos + LIST_X, topPos, LIST, HEIGHT));
        if (linkWindow != null) linkWindow.area().ifPresent(areas::add);
        if (renameWindow != null) renameWindow.area().ifPresent(areas::add);
        return areas;
    }

    /** Clicks on the Deck Link slots belong to the slots, not to a key behind them. */
    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        return linkWindow != null && linkWindow.overSlot(mouseX, mouseY) ? Optional.empty() : super.getChildAt(mouseX, mouseY);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        for (Rect2i area : extraAreas()) {
            if (area.contains((int) mouseX, (int) mouseY)) return false;
        }
        return super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    private Component blockingLabel() {
        return Component.translatable("screen.jasm.port.blocking", Component.translatable(menu.blockingMode()
                ? "screen.jasm.port.blocking_on"
                : "screen.jasm.port.blocking_off"));
    }

    private Tooltip blockingTooltip() {
        return Tooltip.create(blockingLabel().copy().append("\n").append(Component.translatable("screen.jasm.port.blocking_hint")));
    }

    // --- the recipes ---

    private List<ProcessingCard> shown() {
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        if (shownFrom != menu.listed() || !query.equals(shownQuery)) {
            shownFrom = menu.listed();
            shownQuery = query;
            if (query.isEmpty()) {
                shown = menu.listed();
            } else {
                List<ProcessingCard> found = new ArrayList<>();
                for (ProcessingCard card : menu.listed()) {
                    if (matches(card, query)) found.add(card);
                }
                shown = found;
            }
        }
        return shown;
    }

    /** A recipe is found by the name of anything it makes. */
    private static boolean matches(ProcessingCard card, String query) {
        for (ProcessingCard.Amount output : card.outputs()) {
            if (!output.isEmpty() && name(output).getString().toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private Set<ProcessingCard> activeSet() {
        if (activeFrom != menu.active()) {
            activeFrom = menu.active();
            Set<ProcessingCard> set = new HashSet<>();
            for (AccessPortMenu.ActiveRecipe recipe : menu.active()) set.add(recipe.recipe());
            activeSet = set;
        }
        return activeSet;
    }

    private static Component name(ProcessingCard.Amount amount) {
        return amount.isMaterial() ? MaterialIcons.name(amount.material()) : amount.stack().getHoverName();
    }

    /** "8 × Iron Ore", "1 B of Water" or "40 of Mana": one amount as the card's own tooltip writes it. */
    private static Component amountLine(GridKey key, int count) {
        boolean fluid = key instanceof GridKey.Fluid;
        boolean units = fluid || key instanceof GridKey.Material;
        ProcessingCard.Amount one = ProcessingCard.Amount.of(key, 1);
        return Component.translatable(units ? "tooltip.jasm.card.input_fluid" : "tooltip.jasm.card.input",
                fluid ? FluidAmounts.label(count) : String.valueOf(count), name(one));
    }

    private record FacedInput(GridKey key, InputFace face) {}

    /** The recipe's name, what goes in, what comes out, then {@code hint}. */
    private List<FormattedCharSequence> details(ProcessingCard card, @Nullable Component hint) {
        List<Component> lines = new ArrayList<>();
        lines.add(name(card.main()).copy().withColor(JasmGui.TEXT & 0xFFFFFF));
        Map<FacedInput, Integer> counts = new LinkedHashMap<>();
        List<ProcessingCard.Amount> inputs = card.usedInputs();
        List<InputFace> faces = card.usedFaces();
        for (int i = 0; i < inputs.size(); i++) {
            counts.merge(new FacedInput(inputs.get(i).key(), faces.get(i)), inputs.get(i).count(), Integer::sum);
        }
        counts.forEach((input, n) -> {
            Component line = amountLine(input.key(), n);
            if (input.face() != InputFace.DEFAULT) {
                line = Component.translatable("tooltip.jasm.card.input_face", line, Component.translatable(input.face().translationKey()));
            }
            lines.add(line.copy().withColor(JasmGui.SUBTEXT & 0xFFFFFF));
        });
        for (ProcessingCard.Amount output : card.outputs()) {
            if (output.isEmpty()) continue;
            lines.add(Component.translatable("screen.jasm.port.recipe_output", amountLine(output.key(), output.count()))
                    .withColor(JasmGui.GOOD & 0xFFFFFF));
        }
        if (hint != null) lines.add(hint.copy().withColor(JasmGui.MUTED & 0xFFFFFF));
        return lines.stream().map(Component::getVisualOrderText).toList();
    }

    private List<FormattedCharSequence> missingDetails(ProcessingCard card) {
        return List.of(
                name(card.main()).copy().withColor(JasmGui.TEXT & 0xFFFFFF).getVisualOrderText(),
                Component.translatable("tooltip.jasm.card.recipe_missing").withColor(JasmGui.BAD & 0xFFFFFF).getVisualOrderText(),
                Component.translatable("screen.jasm.port.missing_hint").withColor(JasmGui.MUTED & 0xFFFFFF).getVisualOrderText(),
                Component.translatable("screen.jasm.port.click_remove").withColor(JasmGui.MUTED & 0xFFFFFF).getVisualOrderText());
    }

    private int listMax() {
        return Math.max(0, (shown().size() + COLS - 1) / COLS - ROWS);
    }

    /** The recipe list's entry under the mouse, or -1. */
    private int listIndexAt(double mouseX, double mouseY) {
        if (folded()) return -1;
        int x = (int) Math.floor(mouseX) - leftPos - GRID_X;
        int y = (int) Math.floor(mouseY) - topPos - GRID_Y;
        if (x < 0 || y < 0 || x >= COLS * 18 || y >= ROWS * 18) return -1;
        int index = (listRow + y / 18) * COLS + x / 18;
        return index < shown().size() ? index : -1;
    }

    /** The served recipe under the mouse, or -1. */
    private int activeIndexAt(double mouseX, double mouseY) {
        int x = (int) Math.floor(mouseX) - leftPos - ACTIVE_X;
        int y = (int) Math.floor(mouseY) - topPos - ACTIVE_Y;
        if (x < 0 || y < 0 || x >= ACTIVE_COLS * 18 || y >= ACTIVE_ROWS * 18) return -1;
        int index = y / 18 * ACTIVE_COLS + x / 18;
        return index < menu.active().size() ? index : -1;
    }

    // --- the machines ---

    private int machineX() {
        return 8 + font.width(Component.translatable("screen.jasm.port.machine_row")) + 3;
    }

    /** How far apart the machine keys stand: a little closer when many kinds would run into the state on the right. */
    private int machineStep() {
        int count = menu.machines().size();
        int room = WIDTH - 8 - font.width(status()) - 3 - machineX();
        return count <= 1 ? MACHINE_KEY_WIDTH + 1 : Math.clamp(room / count, 17, MACHINE_KEY_WIDTH + 1);
    }

    /** Whether the mouse is on the icon shown when only one kind of machine is in range. */
    private boolean overOnlyMachine(double mouseX, double mouseY) {
        double x = mouseX - leftPos - machineX();
        double y = mouseY - topPos;
        return menu.machines().size() == 1 && x >= 0 && x < 16 && y >= MACHINE_KEY_Y + 3 && y < MACHINE_KEY_Y + 19;
    }

    private void chooseMachine(AccessPortMenu.MachineView machine) {
        Block block = Block.byItem(machine.icon().getItem());
        if (machine.chosen() || machine.icon().isEmpty()) return;
        ClientPacketDistributor.sendToServer(new CraftPayloads.PortMachineType(menu.containerId, BuiltInRegistries.BLOCK.getKey(block)));
    }

    /**
     * With several kinds in range, one key per kind, made like the encoder's Crafting / Processing keys: the chosen
     * one is pressed in and its item sinks with it. Made again when the kinds change, moved when the state beside them
     * changes width.
     */
    private void placeMachineKeys() {
        List<AccessPortMenu.MachineView> machines = menu.machines();
        if (keysFor != machines) {
            keysFor = machines;
            machineKeys.forEach(this::removeWidget);
            machineKeys.clear();
            if (machines.size() > 1) {
                for (AccessPortMenu.MachineView machine : machines) {
                    JasmButton key = JasmButton.item(machine.icon(), machine.name(), b -> chooseMachine(machine), 0, 0,
                            MACHINE_KEY_WIDTH, MACHINE_KEY_HEIGHT);
                    key.setSelected(machine.chosen());
                    key.setTooltip(Tooltip.create(machine.name()));
                    machineKeys.add(addRenderableWidget(key));
                }
            }
        }
        int step = machineStep();
        for (int i = 0; i < machineKeys.size(); i++) {
            JasmButton key = machineKeys.get(i);
            key.setPosition(leftPos + machineX() + i * step, topPos + MACHINE_KEY_Y);
            key.setWidth(step - 1);
        }
    }

    /** The port's state for the right end of the machine row, and its colour. */
    private Component status() {
        if (!menu.running()) {
            return MachineStatusText.noPower(menu.containerId, "screen.jasm.machine.no_power", font, WIDTH / 2);
        }
        if (menu.paused()) return Component.translatable("screen.jasm.bay.status.paused");
        return Component.translatable(menu.locked() ? "screen.jasm.port.in_use_short" : "screen.jasm.port.idle");
    }

    private int statusColor() {
        if (!menu.running()) return JasmGui.BAD;
        if (menu.paused()) return JasmGui.WARN;
        return menu.locked() ? JasmGui.GOOD : JasmGui.MUTED;
    }

    // --- drawing ---

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        frame.draw(graphics, leftPos, topPos);
        JasmGui.divider(graphics, leftPos + KEY_X, topPos + PortUpgradeLayout.dividerY(AccessPortMenu.SIDE_KEYS), JasmGui.SIDE_KEY_WIDTH);
        JasmGui.bar(graphics, leftPos + BAR_X, topPos + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
        // The Deck Link window draws its own two slots.
        for (var slot : menu.slots) {
            if (slot.isActive() && slot.index != AccessPortMenu.SLOT_DECK_IN && slot.index != AccessPortMenu.SLOT_DECK_OUT) {
                JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        for (int row = 0; row < ACTIVE_ROWS; row++) {
            for (int col = 0; col < ACTIVE_COLS; col++) {
                JasmGui.slot(graphics, leftPos + ACTIVE_X + 1 + col * 18, topPos + ACTIVE_Y + 1 + row * 18);
            }
        }
        if (!folded()) {
            listRow = Math.clamp(listRow, 0, listMax());
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    JasmGui.slot(graphics, leftPos + GRID_X + 1 + col * 18, topPos + GRID_Y + 1 + row * 18);
                }
            }
            track(graphics, LIST_SCROLL_X, GRID_Y, ROWS * 18, listRow, listMax());
        }
    }

    private void track(GuiGraphicsExtractor graphics, int x, int y, int height, int first, int max) {
        int offset = max == 0 ? 0 : Math.round((height - KNOB) * first / (float) max);
        JasmGui.track(graphics, leftPos + x, topPos + y, height, offset, KNOB, max > 0);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float a) {
        linkWindow.sync(leftPos, topPos);
        link.setLatched(linkWindow.isOpen());
        renameKey.setLatched(renameWindow.isOpen());
        // Under a window nothing lights up or shows a tooltip, except the Deck Link window's own slots.
        boolean hidden = linkWindow.hidesMouse(realMouseX, realMouseY) || renameWindow.contains(realMouseX, realMouseY);
        int mouseX = hidden ? -1000 : realMouseX;
        int mouseY = hidden ? -1000 : realMouseY;
        placeMachineKeys();
        super.extractContents(graphics, mouseX, mouseY, a);
        linkWindow.setResetActive(menu.canResetDeck());
        blocking.setLatched(menu.blockingMode());
        if (!blocking.getMessage().equals(blockingLabel())) {
            blocking.setMessage(blockingLabel());
            blocking.setTooltip(blockingTooltip());
        }
        drawMachines(graphics, mouseX, mouseY);
        drawRecipes(graphics, mouseX, mouseY);
        slotHints(graphics, mouseX, mouseY);
        if (linkWindow.isOpen()) {
            graphics.nextStratum();
            linkWindow.draw(graphics, realMouseX, realMouseY, a, DeckLinkWindow.linkedTo(menu.linkedPlayer(), menu.deckLinked()));
        }
        if (renameWindow.isOpen()) {
            graphics.nextStratum();
            renameWindow.draw(graphics, realMouseX, realMouseY, a);
        }
    }

    /** The one machine's icon when only one kind is in range; several kinds are keys of their own. */
    private void drawMachines(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<AccessPortMenu.MachineView> machines = menu.machines();
        if (machines.size() != 1) return;
        graphics.item(machines.getFirst().icon(), leftPos + machineX(), topPos + MACHINE_KEY_Y + 3);
        if (overOnlyMachine(mouseX, mouseY)) graphics.setTooltipForNextFrame(font, machines.getFirst().name(), mouseX, mouseY);
    }

    private void drawRecipes(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<AccessPortMenu.ActiveRecipe> active = menu.active();
        Set<ProcessingCard> served = activeSet();
        List<ProcessingCard> list = shown();
        boolean open = !folded();
        int hoveredActive = activeIndexAt(mouseX, mouseY);
        int hoveredList = listIndexAt(mouseX, mouseY);
        // Under the items: the hover tint and the missing tint.
        int shownActive = Math.min(active.size(), ACTIVE_ROWS * ACTIVE_COLS);
        for (int i = 0; i < shownActive; i++) {
            int x = activeX(i);
            int y = activeY(i);
            if (i == hoveredActive) JasmGui.slotHover(graphics, x, y);
            if (active.get(i).missing()) graphics.fill(x, y, x + 16, y + 16, MISSING_TINT);
            FluidGrid.drawStack(graphics, active.get(i).recipe().result(), x, y);
        }
        if (open) {
            for (int i = listRow * COLS; i < Math.min(list.size(), (listRow + ROWS) * COLS); i++) {
                int x = listX(i);
                int y = listY(i);
                if (i == hoveredList) JasmGui.slotHover(graphics, x, y);
                FluidGrid.drawStack(graphics, list.get(i).result(), x, y);
            }
        }
        // Over the items: missing recipes dimmed and marked, a tick on recipes already served, the hover rim.
        graphics.nextStratum();
        for (int i = 0; i < shownActive; i++) {
            int x = activeX(i);
            int y = activeY(i);
            if (active.get(i).missing()) {
                graphics.fill(x, y, x + 16, y + 16, MISSING_DIM);
                graphics.fill(x, y, x + 16, y + 16, MISSING_TINT);
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MISSING, x, y, 2, 5);
            }
            if (i == hoveredActive) JasmGui.slotHoverFrame(graphics, x, y);
        }
        if (open) {
            for (int i = listRow * COLS; i < Math.min(list.size(), (listRow + ROWS) * COLS); i++) {
                int x = listX(i);
                int y = listY(i);
                if (served.contains(list.get(i))) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TICK, x + TICK_X, y, 5, 5);
                if (i == hoveredList) JasmGui.slotHoverFrame(graphics, x, y);
            }
        }
        if (Util.getMillis() < noticeUntil) {
            JasmGui.notice(graphics, font, notice, noticeOk, leftPos + ACTIVE_X, topPos + ACTIVE_Y + ACTIVE_ROWS * 18, ACTIVE_COLS * 18);
        }
        if (hoveredActive >= 0) {
            AccessPortMenu.ActiveRecipe recipe = active.get(hoveredActive);
            graphics.setTooltipForNextFrame(font, recipe.missing() ? missingDetails(recipe.recipe())
                    : details(recipe.recipe(), Component.translatable("screen.jasm.port.click_remove")), mouseX, mouseY);
        } else if (hoveredList >= 0) {
            ProcessingCard card = list.get(hoveredList);
            List<FormattedCharSequence> lines = new ArrayList<>(details(card, served.contains(card) || full() ? null
                    : Component.translatable("screen.jasm.port.click_add")));
            if (!served.contains(card) && full()) lines.add(fullLine().copy().withColor(JasmGui.BAD & 0xFFFFFF).getVisualOrderText());
            graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    private int activeX(int index) { return leftPos + ACTIVE_X + 1 + index % ACTIVE_COLS * 18; }

    private int activeY(int index) { return topPos + ACTIVE_Y + 1 + index / ACTIVE_COLS * 18; }

    /** Whether the port serves as many recipes as it can. */
    private boolean full() {
        return menu.active().size() >= AccessPortBlockEntity.MAX_RECIPES;
    }

    private static Component fullLine() {
        return Component.translatable("screen.jasm.port.full", AccessPortBlockEntity.MAX_RECIPES);
    }

    private int listX(int index) { return leftPos + GRID_X + 1 + index % COLS * 18; }

    private int listY(int index) { return topPos + GRID_Y + 1 + (index / COLS - listRow) * 18; }

    private void slotHints(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (hoveredSlot == menu.getSlot(AccessPortMenu.SLOT_DECK_IN) && !hoveredSlot.hasItem()) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.port.deck_hint"), 180), mouseX, mouseY);
        }
        if (hoveredSlot == menu.getSlot(0)) {
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("screen.jasm.port.power_hint"), 180), mouseX, mouseY);
        }
        for (int i = 0; i < PortOperations.UPGRADE_SLOTS; i++) {
            if (hoveredSlot == menu.getSlot(AccessPortMenu.SLOT_SPEED + i)) {
                graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.transfer.speed_hint"), mouseX, mouseY);
            }
        }
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH
                && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, List.of(
                    Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                            String.format("%,d", menu.capacity())).getVisualOrderText(),
                    Component.translatable("screen.jasm.archive.drain", menu.drain())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText()),
                    mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        String title = font.plainSubstrByWidth(shownTitle.getString(), BAR_X - 12 - titleLabelX);
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, Component.translatable("screen.jasm.port.machine_row"), 8, MACHINE_Y, JasmGui.SUBTEXT, false);
        if (menu.machines().isEmpty()) {
            graphics.text(font, Component.translatable("screen.jasm.port.no_machine"), machineX(), MACHINE_Y, JasmGui.MUTED, false);
        }
        Component state = status();
        graphics.text(font, state, WIDTH - 8 - font.width(state) + 1, MACHINE_Y, statusColor(), false);
        graphics.text(font, Component.translatable("screen.jasm.port.active"), ACTIVE_X + 1, ACTIVE_LABEL_Y, JasmGui.SUBTEXT, false);
        int served = menu.active().size();
        Component count = Component.translatable("screen.jasm.port.recipe_total", served, AccessPortBlockEntity.MAX_RECIPES);
        graphics.text(font, count, COUNT_RIGHT - font.width(count) + 1, ACTIVE_LABEL_Y,
                served >= AccessPortBlockEntity.MAX_RECIPES ? JasmGui.WARN : JasmGui.MUTED, false);
        graphics.text(font, Component.translatable("screen.jasm.port.buffer"), AccessPortMenu.BUFFER_X, AccessPortMenu.BUFFER_Y - 10,
                JasmGui.SUBTEXT, false);
        if (!folded()) {
            graphics.text(font, Component.translatable("screen.jasm.port.recipes"), LIST_X + 8, 6, JasmGui.TEXT, false);
            String listed = String.valueOf(shown().size());
            graphics.text(font, listed, LIST_X + 110 - font.width(listed) + 1, 6, JasmGui.MUTED, false);
        }
    }

    private static Component recipeCount(int count) {
        return count == 1 ? Component.translatable("screen.jasm.port.recipe_count_one")
                : Component.translatable("screen.jasm.port.recipe_count", count);
    }

    // --- input ---

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (renameWindow.isOpen() && renameWindow.keyPressed(event)) {
            renameKey.setLatched(renameWindow.isOpen());
            return true;
        }
        // Escape shuts the Deck Link window first, then the screen.
        if (linkWindow.isOpen() && event.isEscape()) {
            linkWindow.close();
            link.setLatched(false);
            return true;
        }
        if (search.isFocused() && !event.isEscape()) {
            search.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (renameWindow.isOpen() && renameWindow.charTyped(event)) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (renameWindow.contains(x, y) || linkWindow.contains(x, y)) return true;
        int step = (int) Math.signum(scrollY);
        if (!folded() && x >= leftPos + GRID_X && x < leftPos + LIST_SCROLL_X + 10 && y >= topPos + GRID_Y && y < topPos + GRID_Y + ROWS * 18) {
            listRow = Math.clamp(listRow - step, 0, listMax());
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (renameWindow.contains(x, y)) {
            renameWindow.mouseClicked(event, doubleClick);
            renameKey.setLatched(renameWindow.isOpen());
            return true;
        }
        if (linkWindow.contains(x, y)) {
            return linkWindow.mouseClicked(event, doubleClick) || super.mouseClicked(event, doubleClick);
        }
        if (menu.getCarried().isEmpty()) {
            int listed = listIndexAt(x, y);
            if (listed >= 0) {
                ProcessingCard card = shown().get(listed);
                if (activeSet().contains(card)) return true;
                if (full()) {
                    showNotice(fullLine(), false, FULL_NOTICE_MS);
                } else {
                    ClientPacketDistributor.sendToServer(new CraftPayloads.PortRecipe(menu.containerId, card, true));
                }
                return true;
            }
            int served = activeIndexAt(x, y);
            if (served >= 0) {
                ClientPacketDistributor.sendToServer(new CraftPayloads.PortRecipe(menu.containerId, menu.active().get(served).recipe(), false));
                return true;
            }
        }
        if (!folded() && onTrack(LIST_SCROLL_X, GRID_Y, ROWS * 18, x, y) && listMax() > 0) {
            draggingList = true;
            dragTo(y);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean onTrack(int trackX, int trackY, int height, double x, double y) {
        return x >= leftPos + trackX - 1 && x < leftPos + trackX + 11 && y >= topPos + trackY && y < topPos + trackY + height;
    }

    private void dragTo(double y) {
        double fraction = Math.clamp((y - topPos - GRID_Y - KNOB / 2.0) / (ROWS * 18 - KNOB), 0.0, 1.0);
        listRow = (int) Math.round(fraction * listMax());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (linkWindow.mouseDragged(event, width, height) || renameWindow.mouseDragged(event, width, height)) {
            return true;
        }
        if (draggingList) {
            dragTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (linkWindow.mouseReleased() | renameWindow.mouseReleased()) {
            return true;
        }
        if (draggingList) {
            draggingList = false;
            return true;
        }
        return super.mouseReleased(event);
    }
}
