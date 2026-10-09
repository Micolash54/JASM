package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.pool.MaterialKinds;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.storage.WaferSettings.Filter;
import dev.micolash.jasm.storage.WaferSettings.Mode;
import dev.micolash.jasm.transfer.TransferPortMenu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/** Ordered item filters and the ghost input for adding one, shared by wafers and inventory ports. */
class ItemFilterEditor {
    static final int WIDTH = 332;
    /** The width the controls were first laid out for; the port editors measure how much narrower they are from it. */
    private static final int BASE_WIDTH = 300;
    private final int height;
    private final int listY;
    private final int editorWidth;
    private static final int TAG_ROW_HEIGHT = 14;
    private final int rowHeight;
    private final boolean compact;
    private final int rows;
    private final int slotY;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);
    private static final JasmButton.Icon UP = new JasmButton.Icon(Jasm.id("icon/arrow_up"), 5, 3);
    private static final JasmButton.Icon DOWN = new JasmButton.Icon(Jasm.id("icon/arrow_down"), 5, 3);
    private static final JasmButton.Icon CHECK = new JasmButton.Icon(Jasm.id("icon/check"), 9, 7);

    private final Supplier<ItemStack> carried;
    private Consumer<WaferSettings> save = settings -> {};
    private final boolean movable;
    private Component title = Component.empty();
    private Component suffix = Component.empty();
    private ItemStack windowIcon = ItemStack.EMPTY;
    private final Font font;
    private final EditBox text;
    private final int textWidth;
    private final List<Placed> buttons = new ArrayList<>();
    private final Button[][] rowButtons;
    private final Button modeButton;
    private final Button action;
    private final Button confirm;
    // Stock fields, shown only while the port holds a Stock Upgrade: one per visible row, and one on the add line.
    private static final int STOCK_WIDTH = 36;
    /** Allow, On and the Stock field are shorter than the arrow keys, sitting on the same bottom line. */
    private static final int SMALL_KEY = 14;
    private static final int SMALL_DROP = 17 - SMALL_KEY;
    // the row field ends 6px before the arrow keys
    private final int stockX;
    private static final int ADD_STOCK_WIDTH = 34;
    private final EditBox[] stockFields;
    private final EditBox addStock;
    private BooleanSupplier stockShown = () -> false;
    private @Nullable EditBox stockFocus;
    // the rule the focused row field belongs to
    private int stockRule = -1;
    private final HashMap<Selector, List<ItemStack>> icons = new HashMap<>();
    private WaferSettings draft = WaferSettings.DEFAULT;
    private Mode mode = Mode.MATERIAL;
    // the last pick was a shift-click: use what the item holds, not the item
    private boolean contents;
    private boolean allow = true;
    private ItemStack ghost = ItemStack.EMPTY;
    private List<String> tagChoices = List.of();
    private boolean opened;
    private int scroll;
    private int tagScroll;
    private int x;
    private int y;
    private int grabX = -1;
    private int grabY;
    private boolean draggingScroll;
    private record Placed(Button button, int x, int y) {}
    private record Selector(Mode mode, String value) {}

    ItemFilterEditor(Font font, int rows, boolean movable, Supplier<ItemStack> carried) {
        this(font, rows, movable, carried, movable ? WIDTH : BASE_WIDTH - 16);
    }

    ItemFilterEditor(Font font, int rows, boolean movable, Supplier<ItemStack> carried, int editorWidth) {
        this.rows = rows;
        this.movable = movable;
        this.carried = carried;
        this.editorWidth = editorWidth;
        this.compact = !movable;
        this.rowHeight = compact ? TransferPortMenu.FILTER_ROW : 24;
        this.listY = movable ? 37 : 16;
        this.slotY = movable ? 168 - (4 - rows) * rowHeight : listY + rows * rowHeight + 28;
        this.height = slotY + (movable ? 24 : 20);
        int shrink = BASE_WIDTH - editorWidth;
        this.rowButtons = new Button[rows][6];
        this.font = font;
        textWidth = 236 - shrink;
        text = new JasmField(font, 0, 0, textWidth, 14, label("value"));
        text.setMaxLength(256);
        text.setResponder(value -> { icons.clear(); update(); });
        stockFields = new EditBox[rows];
        for (int row = 0; row < rows; row++) {
            stockFields[row] = stockField(STOCK_WIDTH, SMALL_KEY);
            ((JasmField) stockFields[row]).setSmallText();
        }
        addStock = stockField(ADD_STOCK_WIDTH, 14);
        if (movable) place(JasmButton.icon(() -> CLOSE, label("close"), b -> close(), 0, 0, 11, 11), WIDTH - 16, 5);
        // The kind and allow/deny keys share one width, just enough for the longest word either can show.
        int keyWidth = Math.max(font.width(label("allow")), font.width(label("deny")));
        for (Mode m : Mode.values()) keyWidth = Math.max(keyWidth, font.width(label(m.getSerializedName())));
        keyWidth += 16;
        modeButton = place(JasmButton.text(Component.empty(), b -> {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            setItem(ghost, contents);
        }, 0, 0, keyWidth, 17), 8, slotY - 24);
        action = place(JasmButton.text(Component.empty(), b -> { allow = !allow; update(); }, 0, 0, keyWidth, 17), 10 + keyWidth, slotY - 24);
        confirm = place(JasmButton.icon(() -> CHECK, label("add"), b -> add(), 0, 0, 17, 17), 274 - shrink, slotY - 1);
        confirm.setTooltip(Tooltip.create(label("add")));
        // The wafer window has room for a Void key between the On key and the arrows.
        int controlsX = compact ? 10 : 159;
        // Allow and On use the smaller text, so a row's keys and its Stock field fit on one line.
        int toggleWidth = Math.max(Math.max(JasmGui.smallWidth(font, label("allow")), JasmGui.smallWidth(font, label("deny"))),
                Math.max(JasmGui.smallWidth(font, label("on")), JasmGui.smallWidth(font, label("off")))) + 10;
        int actionWidth = toggleWidth;
        int enabledWidth = toggleWidth;
        int arrowsX = compact ? editorWidth - 60 : controlsX + 102;
        stockX = arrowsX - 6 - STOCK_WIDTH;
        for (int row = 0; row < rows; row++) {
            final int visible = row;
            int py = listY + row * rowHeight + (compact ? 16 : 4);
            rowButtons[row][0] = place(JasmButton.smallText(Component.empty(), b -> change(visible, 0), 0, 0, actionWidth, SMALL_KEY),
                    controlsX, py + SMALL_DROP);
            rowButtons[row][1] = place(JasmButton.smallText(Component.empty(), b -> change(visible, 1), 0, 0, enabledWidth, SMALL_KEY),
                    controlsX + actionWidth + 2, py + SMALL_DROP);
            if (!compact) {
                rowButtons[row][5] = place(JasmButton.text(Component.empty(), b -> change(visible, 5), 0, 0, 30, 17),
                        controlsX + actionWidth + 2 + enabledWidth + 2, py);
                rowButtons[row][5].setTooltip(Tooltip.create(label("void_tip")));
            }
            rowButtons[row][2] = place(JasmButton.icon(() -> UP, label("up"), b -> change(visible, 2), 0, 0, 13, 17), arrowsX, py);
            rowButtons[row][3] = place(JasmButton.icon(() -> DOWN, label("down"), b -> change(visible, 3), 0, 0, 13, 17), arrowsX + 15, py);
            rowButtons[row][4] = place(JasmButton.icon(() -> CLOSE, label("remove"), b -> change(visible, 4), 0, 0, 13, 17), arrowsX + 30, py);
        }
    }

    private EditBox stockField(int width, int height) {
        EditBox field = new JasmField(font, 0, 0, width, height, label("stock"));
        field.setMaxLength(6);
        field.setFilter(value -> value.chars().allMatch(Character::isDigit));
        field.setHint(label("stock_any"));
        field.visible = false;
        return field;
    }

    /** Shows the Stock fields and badges while {@code shown} says so: an Output list on a port with a Stock Upgrade. */
    void setStock(BooleanSupplier shown) { stockShown = shown; }
    private boolean stock() { return stockShown.getAsBoolean(); }
    /** A field's number; empty or 0 means no limit. */
    private static int stockOf(EditBox field) {
        String value = field.getValue();
        return value.isEmpty() ? 0 : (int) Math.min(Filter.MAX_STOCK, Long.parseLong(value));
    }
    private static String stockText(int stock) { return stock > 0 ? Integer.toString(stock) : ""; }

    /** Saves the focused row field's number and lets go of it. The add line's field keeps its number until the row is added. */
    private void commitStock() {
        EditBox field = stockFocus;
        stockFocus = null;
        if (field == null) return;
        field.setFocused(false);
        int at = stockRule;
        stockRule = -1;
        if (field == addStock || at < 0 || at >= draft.rules().size()) return;
        Filter rule = draft.rules().get(at);
        int stock = stockOf(field);
        if (stock == rule.stock()) { field.setValue(stockText(stock)); return; }
        List<Filter> rules = new ArrayList<>(draft.rules());
        rules.set(at, rule.withStock(stock));
        send(new WaferSettings(rules));
    }

    private void focusStock(EditBox field, int rule) {
        if (stockFocus != field) commitStock();
        text.setFocused(false);
        stockFocus = field;
        stockRule = rule;
        field.setFocused(true);
    }

    /** The line above the rows. The wafer window shows the last emptying result there instead. */
    Component rulesLine() { return label("rules"); }
    private Component label(String key) { return Component.translatable("screen.jasm.filter." + key); }
    Button place(Button button, int px, int py) { buttons.add(new Placed(button, px, py)); return button; }
    boolean isOpen() { return opened; }
    void setSave(Consumer<WaferSettings> save) { this.save = save; }
    void unfocus() { text.setFocused(false); commitStock(); }
    boolean contains(double mx, double my) { return isOpen() && mx >= x && mx < x + editorWidth && my >= y && my < y + height; }
    Optional<Rect2i> area() {
        return isOpen() ? Optional.of(new Rect2i(x, y, editorWidth + (movable ? 3 : 0), height + (movable ? 3 : 0))) : Optional.empty();
    }
    Rect2i slotArea() { return new Rect2i(x + 8, y + slotY, 16, 16); }
    ItemStack ghost() { return tagChoices.isEmpty() && !text.getValue().isEmpty() ? icon(candidate()) : ghost; }
    void fit(int width, int screenHeight) {
        if (movable) { x = Math.clamp(x, 0, Math.max(0, width - WIDTH)); y = Math.clamp(y, 0, Math.max(0, screenHeight - height)); }
        layout();
    }

    void open(WaferSettings settings, Component title, Component suffix, ItemStack icon, int left, int top, int width, int screenHeight) {
        opened = true;
        draft = settings;
        this.title = title;
        this.suffix = suffix;
        windowIcon = icon;
        scroll = 0;
        mode = Mode.MATERIAL;
        contents = false;
        allow = true;
        ghost = ItemStack.EMPTY;
        tagChoices = List.of();
        icons.clear();
        text.setValue("");
        text.setFocused(false);
        stockFocus = null;
        stockRule = -1;
        addStock.setValue("");
        x = left;
        y = top;
        fit(width, screenHeight);
        update();
    }

    /** The compact editor's heading: its title, or the prompt while it offers a choice of tags. */
    Component heading() { return tagChoices.isEmpty() ? title : label("choose_tag"); }

    void close() { opened = false; grabX = -1; draggingScroll = false; text.setFocused(false); commitStock(); }
    private void layout() {
        for (Placed placed : buttons) placed.button().setPosition(x + placed.x(), y + placed.y());
        text.setPosition(x + 30, y + slotY + 1);
        for (int row = 0; row < rows; row++) stockFields[row].setPosition(x + stockX, y + listY + row * rowHeight + 16 + SMALL_DROP);
        addStock.setPosition(x + 30 + textWidth - ADD_STOCK_WIDTH + 2, y + slotY + 1);
    }
    private void send(WaferSettings settings) {
        draft = settings;
        scroll = Math.min(scroll, maxScroll());
        save.accept(settings);
        update();
    }
    private int visibleRows() { return tagChoices.isEmpty() ? rows : rows * rowHeight / TAG_ROW_HEIGHT; }
    private int maxScroll() { return Math.max(0, (tagChoices.isEmpty() ? draft.rules().size() : tagChoices.size()) - visibleRows()); }
    private int offset() { return tagChoices.isEmpty() ? scroll : tagScroll; }
    private Filter candidate() { return new Filter(mode, text.getValue(), allow, true); }
    private void update() {
        if (modeButton == null) return;
        modeButton.setMessage(label(mode.getSerializedName()));
        action.setMessage(JasmGui.state(label(allow ? "allow" : "deny"), allow));
        if (confirm != null) confirm.active = tagChoices.isEmpty() && candidate().valid();
        boolean stock = compact && stock();
        // The name field gives the add line's Stock field its room.
        text.setWidth(stock ? textWidth - ADD_STOCK_WIDTH - 2 : textWidth);
        addStock.visible = stock && tagChoices.isEmpty();
        for (int row = 0; row < rows; row++) {
            int at = scroll + row;
            boolean visible = tagChoices.isEmpty() && at < draft.rules().size();
            for (Button button : rowButtons[row]) if (button != null) button.visible = visible;
            EditBox field = stockFields[row];
            // Deny rows show no field.
            field.visible = visible && stock && draft.rules().get(at).allow();
            if (field.visible && field != stockFocus && !field.getValue().equals(stockText(draft.rules().get(at).stock())))
                field.setValue(stockText(draft.rules().get(at).stock()));
            if (!visible) continue;
            Filter rule = draft.rules().get(at);
            rowButtons[row][0].setMessage(JasmGui.state(label(rule.allow() ? "allow" : "deny"), rule.allow()));
            rowButtons[row][1].setMessage(JasmGui.state(label(rule.enabled() ? "on" : "off"), rule.enabled()));
            Button voidKey = rowButtons[row][5];
            if (voidKey != null) {
                // Only an Allow row can destroy leftovers; Deny rows show no key.
                voidKey.visible = rule.allow();
                voidKey.setMessage(label("void").copy().withColor((rule.voidExcess() ? JasmGui.WARN : JasmGui.MUTED) & 0xFFFFFF));
            }
            rowButtons[row][2].active = at > 0;
            rowButtons[row][3].active = at + 1 < draft.rules().size();
        }
        // The upgrade was taken out, or the row turned to Deny, while its field was being typed in.
        if (stockFocus != null && !stockFocus.visible) commitStock();
    }
    private void change(int row, int control) {
        int at = scroll + row;
        if (at >= draft.rules().size()) return;
        List<Filter> rules = new ArrayList<>(draft.rules());
        Filter rule = rules.get(at);
        switch (control) {
            case 0 -> rules.set(at, new Filter(rule.mode(), rule.value(), !rule.allow(), rule.enabled(), rule.voidExcess(), rule.stock()));
            case 1 -> rules.set(at, new Filter(rule.mode(), rule.value(), rule.allow(), !rule.enabled(), rule.voidExcess(), rule.stock()));
            case 5 -> rules.set(at, new Filter(rule.mode(), rule.value(), rule.allow(), rule.enabled(), !rule.voidExcess(), rule.stock()));
            case 2 -> {
                if (at > 0) Collections.swap(rules, at, at - 1);
            }
            case 3 -> {
                if (at + 1 < rules.size()) Collections.swap(rules, at, at + 1);
            }
            case 4 -> rules.remove(at);
        }
        send(new WaferSettings(rules));
    }
    private void add() {
        if (!tagChoices.isEmpty() || !candidate().valid()) return;
        List<Filter> rules = new ArrayList<>(draft.rules());
        rules.add(compact && stock() ? candidate().withStock(stockOf(addStock)) : candidate());
        if (stockFocus == addStock) commitStock();
        addStock.setValue("");
        send(new WaferSettings(rules));
        scroll = Math.max(0, rules.size() - rows);
        ghost = ItemStack.EMPTY;
        text.setValue("");
        allow = true;
        update();
    }

    private record Held(Identifier id, List<String> tags) {}

    // a fluid first (water in a bucket), then anything else a mod lets an item hold
    private static @Nullable Held held(ItemStack stack) {
        FluidResource fluid = FluidGrid.containedFluid(stack);
        if (fluid != null)
            return new Held(BuiltInRegistries.FLUID.getKey(fluid.getFluid()),
                    fluid.getFluid().builtInRegistryHolder().tags().map(tag -> tag.location().toString()).toList());
        MaterialKinds.Held material = MaterialKinds.held(stack);
        return material == null ? null
                : new Held(material.key().id(), material.holder().tags().map(tag -> tag.location().toString()).toList());
    }

    /** Pick the ID, namespace or tags of the item, or with {@code contents} of what it holds. The carried stack is never changed. */
    void setItem(ItemStack stack, boolean contents) {
        ghost = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        this.contents = contents;
        tagChoices = List.of();
        tagScroll = 0;
        Held held = contents ? held(ghost) : null;
        if (ghost.isEmpty() || contents && held == null) { text.setValue(""); return; }
        Identifier id = held != null ? held.id() : BuiltInRegistries.ITEM.getKey(ghost.getItem());
        switch (mode) {
            case MATERIAL -> text.setValue(id.toString());
            case MOD_ID -> text.setValue(id.getNamespace());
            case TAG -> {
                List<String> tags = (held != null ? held.tags().stream()
                        : ghost.getItem().builtInRegistryHolder().tags().map(tag -> tag.location().toString())).distinct().sorted().toList();
                text.setValue(tags.size() == 1 ? tags.getFirst() : "");
                if (tags.size() > 1) tagChoices = tags;
            }
        }
        update();
    }

    /** Something that isn't an item, dragged in from a recipe viewer: a fluid or a modded material. */
    void setMaterial(Identifier id) {
        mode = Mode.MATERIAL;
        ghost = ItemStack.EMPTY;
        contents = false;
        tagChoices = List.of();
        tagScroll = 0;
        text.setValue(id.toString());
        update();
    }

    private ItemStack icon(Filter rule) {
        List<ItemStack> matches = icons.computeIfAbsent(new Selector(rule.mode(), rule.value()),
                key -> {
                    List<ItemStack> items = BuiltInRegistries.ITEM.stream().filter(rule::matches).map(ItemStack::new).toList();
                    // A fluid row shows the fluid's bucket.
                    return !items.isEmpty() ? items : BuiltInRegistries.FLUID.stream().filter(rule::matches).map(Fluid::getBucket)
                            .filter(bucket -> bucket != Items.AIR).distinct().map(ItemStack::new).toList();
                });
        return matches.isEmpty() ? ItemStack.EMPTY : matches.get((int) ((Util.getMillis() / 1000) % matches.size()));
    }
    /** An item at nine tenths of its size, with its top left corner at the given spot. */
    private static void smallItem(GuiGraphicsExtractor graphics, ItemStack stack, int px, int py) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(px, py);
        graphics.pose().scale(0.9F, 0.9F);
        graphics.item(stack, 0, 0);
        graphics.pose().popMatrix();
    }
    /** True while something lies over the window and takes its clicks and keys. */
    boolean locked() { return false; }
    void drawOverlay(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {}
    void draw(GuiGraphicsExtractor graphics, int mx, int my, float a, int width, int screenHeight) {
        if (!isOpen()) return;
        fit(width, screenHeight);
        update();
        if (grabX >= 0 || grabbable(mx, my)) graphics.requestCursor(CursorTypes.RESIZE_ALL);
        if (movable) {
            JasmGui.window(graphics, x, y, editorWidth, height);
            graphics.item(windowIcon, x + 6, y + 4);
            graphics.text(font, title, x + 26, y + 8, JasmGui.TEXT, false);
            graphics.text(font, suffix, x + editorWidth - 23 - font.width(suffix), y + 8, JasmGui.MUTED, false);
            JasmGui.divider(graphics, x + 4, y + 21, editorWidth - 8);
            graphics.text(font, tagChoices.isEmpty() ? rulesLine() : label("choose_tag"), x + 8, y + 26, JasmGui.SUBTEXT, false);
            JasmGui.divider(graphics, x + 7, y + slotY - 29, editorWidth - 14);
        } else {
            graphics.text(font, heading(), x + 7, y + 3, JasmGui.SUBTEXT, false);
        }
        JasmGui.inset(graphics, x + 7, y + listY - 1, editorWidth - (movable ? 30 : 20), rows * rowHeight + 2);
        int displayedRowHeight = tagChoices.isEmpty() ? rowHeight : TAG_ROW_HEIGHT;
        for (int row = 0; row < visibleRows(); row++) {
            int at = offset() + row;
            int py = y + listY + row * displayedRowHeight;
            if (tagChoices.isEmpty()) {
                if (at >= draft.rules().size()) break;
                Filter rule = draft.rules().get(at);
                int rowRight = x + editorWidth - (movable ? 23 : 13);
                if (mx >= x + 8 && mx < rowRight && my >= py && my < py + rowHeight) graphics.fill(x + 8, py, rowRight, py + rowHeight, JasmGui.HOVER);
                smallItem(graphics, icon(rule), x + 10, py + (compact ? 1 : 5));
                // A row with a Stock number shows it on its icon, the way the Deck shows counts.
                if (compact && stock() && rule.allow() && rule.stock() > 0)
                    JasmGui.itemCount(graphics, font, GridEntries.abbreviate(rule.stock()), x + 8, py);
                if (stockFields[row].visible) {
                    Component stockLabel = label("stock");
                    JasmGui.smallText(graphics, font, stockLabel, x + stockX - 4 - JasmGui.smallWidth(font, stockLabel),
                            py + 16 + SMALL_DROP + Math.round((SMALL_KEY - 7 * JasmGui.SMALL_TEXT) / 2), JasmGui.SUBTEXT);
                }
                int color = rule.enabled() ? JasmGui.TEXT : JasmGui.MUTED;
                Component kind = label(rule.mode().getSerializedName());
                if (compact) {
                    // The kind sits on the first line, over the arrow keys.
                    int kindRight = x + editorWidth - 17;
                    graphics.text(font, kind, kindRight - font.width(kind), py + 4, JasmGui.MUTED, false);
                    graphics.text(font, font.plainSubstrByWidth(rule.value(), kindRight - font.width(kind) - 8 - (x + 30)), x + 30, py + 4, color, false);
                } else {
                    graphics.text(font, font.plainSubstrByWidth(rule.value(), editorWidth - 203), x + 30, py + 4, color, false);
                    graphics.text(font, kind, x + 30, py + 14, JasmGui.MUTED, false);
                }
                if (mx >= x + 8 && mx < x + editorWidth - (compact ? 13 : 171) && my >= py && my < py + (compact ? 15 : rowHeight))
                    graphics.setTooltipForNextFrame(font, Component.literal(rule.value()), mx, my);
            } else {
                if (at >= tagChoices.size()) break;
                boolean over = mx >= x + 8 && mx < x + editorWidth - (movable ? 23 : 13) && my >= py && my < py + TAG_ROW_HEIGHT;
                if (over) {
                    graphics.fill(x + 8, py, x + editorWidth - (movable ? 23 : 13), py + TAG_ROW_HEIGHT, JasmGui.HOVER);
                    graphics.setTooltipForNextFrame(font, Component.literal(tagChoices.get(at)), mx, my);
                }
                graphics.text(font, font.plainSubstrByWidth(tagChoices.get(at), editorWidth - (movable ? 37 : 27)),
                        x + 11, py + (TAG_ROW_HEIGHT - font.lineHeight) / 2, JasmGui.TEXT, false);
            }
        }
        int travel = rows * rowHeight - 15;
        JasmGui.scrollBar(graphics, x + scrollX(), y + listY - 1, 8, rows * rowHeight + 2,
                maxScroll() == 0 ? 0 : Math.round(travel * offset() / (float) maxScroll()), 15, maxScroll() > 0);
        JasmGui.slot(graphics, x + 8, y + slotY);
        ItemStack preview = ghost();
        if (!preview.isEmpty()) graphics.item(preview, x + 8, y + slotY);
        if (!preview.isEmpty() && slotArea().contains(mx, my)) graphics.setTooltipForNextFrame(font, preview.getHoverName(), mx, my);
        text.extractRenderState(graphics, mx, my, a);
        for (Placed placed : buttons) if (placed.button().visible) placed.button().extractRenderState(graphics, mx, my, a);
        for (EditBox field : stockFields) if (field.visible) field.extractRenderState(graphics, mx, my, a);
        if (addStock.visible) {
            graphics.text(font, label("stock"), addStock.getX(), y + slotY - 9, JasmGui.SUBTEXT, false);
            addStock.extractRenderState(graphics, mx, my, a);
        }
        drawOverlay(graphics, x, y, editorWidth, height);
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (locked()) return contains(event.x(), event.y());
        layout();
        update();
        EditBox hit = addStock.visible && addStock.isMouseOver(event.x(), event.y()) ? addStock : null;
        int hitRule = -1;
        for (int row = 0; row < rows && hit == null; row++) {
            if (stockFields[row].visible && stockFields[row].isMouseOver(event.x(), event.y())) {
                hit = stockFields[row];
                hitRule = scroll + row;
            }
        }
        if (hit != null) {
            focusStock(hit, hitRule);
            hit.mouseClicked(event, doubleClick);
            return true;
        }
        // Clicking away confirms a Stock number.
        commitStock();
        for (Placed placed : buttons) if (placed.button().visible && placed.button().mouseClicked(event, doubleClick)) return true;
        if (slotArea().contains((int) event.x(), (int) event.y())) { setItem(carried.get(), event.hasShiftDown()); return true; }
        if (event.x() >= x + scrollX() && event.y() >= y + listY && event.y() < y + listY + rows * rowHeight) {
            draggingScroll = true;
            scrollTo(event.y());
            return true;
        }
        if (!tagChoices.isEmpty() && event.y() >= y + listY && event.y() < y + listY + rows * rowHeight) {
            int row = (int) (event.y() - y - listY) / TAG_ROW_HEIGHT;
            int at = tagScroll + row;
            if (row < visibleRows() && at < tagChoices.size()) {
                String tag = tagChoices.get(at);
                tagChoices = List.of();
                text.setValue(tag);
            }
            return true;
        }
        text.setFocused(text.isMouseOver(event.x(), event.y()));
        if (text.isFocused()) {
            tagChoices = List.of();
            text.mouseClicked(event, doubleClick);
        } else if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && grabbable(event.x(), event.y())) {
            grabX = (int) event.x() - x;
            grabY = (int) event.y() - y;
        }
        return true;
    }
    /** A movable window can be picked up by any empty spot, not just its title bar; the rule list and the field don't count. */
    private boolean grabbable(double mx, double my) {
        if (!movable || !contains(mx, my) || text.isMouseOver(mx, my) || slotArea().contains((int) mx, (int) my)) return false;
        for (Placed placed : buttons) if (placed.button().visible && placed.button().isMouseOver(mx, my)) return false;
        return my < y + listY - 1 || my >= y + listY + rows * rowHeight + 1;
    }
    private int scrollX() { return editorWidth - (movable ? 18 : 10); }

    private void scrollTo(double my) {
        int value = Math.clamp(Math.round((float) (my - y - listY - 7) / (rows * rowHeight - 15) * maxScroll()), 0, maxScroll());
        if (tagChoices.isEmpty()) scroll = value;
        else tagScroll = value;
    }
    boolean mouseDragged(MouseButtonEvent event, int width, int screenHeight) {
        if (locked()) return contains(event.x(), event.y());
        if (grabX >= 0) { x = (int) event.x() - grabX; y = (int) event.y() - grabY; fit(width, screenHeight); return true; }
        if (draggingScroll) { scrollTo(event.y()); return true; }
        return contains(event.x(), event.y());
    }
    boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = grabX >= 0 || draggingScroll || contains(event.x(), event.y());
        grabX = -1;
        draggingScroll = false;
        return handled;
    }
    boolean mouseScrolled(double delta) {
        if (locked()) return true;
        commitStock();
        int value = Math.clamp(offset() - (int) Math.signum(delta), 0, maxScroll());
        if (tagChoices.isEmpty()) scroll = value;
        else tagScroll = value;
        return true;
    }
    boolean keyPressed(KeyEvent event) {
        if (locked()) return true;
        if (stockFocus != null && !event.isEscape()) {
            if (!event.isConfirmation()) stockFocus.keyPressed(event);
            else if (stockFocus == addStock) add();
            else commitStock();
            return true;
        }
        if (event.isEscape()) {
            commitStock();
            if (!tagChoices.isEmpty()) {
                tagChoices = List.of();
                update();
            } else if (movable) close();
            else {
                text.setFocused(false);
                return false;
            }
            return true;
        }
        if (text.isFocused()) {
            if (event.isConfirmation()) add();
            else text.keyPressed(event);
            return true;
        }
        return false;
    }
    boolean charTyped(CharacterEvent event) {
        if (stockFocus != null) return stockFocus.charTyped(event);
        return locked() || text.isFocused() && text.charTyped(event);
    }
}
