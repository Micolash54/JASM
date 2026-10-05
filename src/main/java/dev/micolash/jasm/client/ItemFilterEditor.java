package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.storage.WaferSettings.Filter;
import dev.micolash.jasm.storage.WaferSettings.Mode;
import dev.micolash.jasm.transfer.TransferPortMenu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
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
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

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
    private final List<Placed> buttons = new ArrayList<>();
    private final Button[][] rowButtons;
    private final Button modeButton;
    private final Button action;
    private final Button confirm;
    private final HashMap<Selector, List<ItemStack>> icons = new HashMap<>();
    private WaferSettings draft = WaferSettings.DEFAULT;
    /** The kinds of row this editor offers, in the order its key steps through them. */
    private List<Mode> modes = List.of(Mode.ITEM, Mode.TAG, Mode.MOD_ID);
    private Mode mode = Mode.ITEM;
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
        text = new JasmField(font, 0, 0, 236 - shrink, 14, label("value"));
        text.setMaxLength(256);
        text.setResponder(value -> { icons.clear(); update(); });
        if (movable) place(JasmButton.icon(() -> CLOSE, label("close"), b -> close(), 0, 0, 11, 11), WIDTH - 16, 5);
        // The kind and allow/deny keys share one width, just enough for the longest word either can show.
        int keyWidth = Math.max(font.width(label("allow")), font.width(label("deny")));
        for (Mode m : Mode.values()) keyWidth = Math.max(keyWidth, font.width(label(m.getSerializedName())));
        keyWidth += 16;
        modeButton = place(JasmButton.text(Component.empty(), b -> {
            mode = modes.get((modes.indexOf(mode) + 1) % modes.size());
            setItem(ghost);
        }, 0, 0, keyWidth, 17), 8, slotY - 24);
        action = place(JasmButton.text(Component.empty(), b -> { allow = !allow; update(); }, 0, 0, keyWidth, 17), 10 + keyWidth, slotY - 24);
        confirm = place(JasmButton.icon(() -> CHECK, label("add"), b -> add(), 0, 0, 17, 17), 274 - shrink, slotY - 1);
        confirm.setTooltip(Tooltip.create(label("add")));
        // The wafer window has room for a Void key between the On key and the arrows.
        int controlsX = compact ? 10 : 159;
        int toggleWidth = Math.max(Math.max(font.width(label("allow")), font.width(label("deny"))),
                Math.max(font.width(label("on")), font.width(label("off")))) + 10;
        int actionWidth = compact ? toggleWidth : 38;
        int enabledWidth = compact ? toggleWidth : 28;
        int arrowsX = compact ? editorWidth - 60 : controlsX + 102;
        for (int row = 0; row < rows; row++) {
            final int visible = row;
            int py = listY + row * rowHeight + (compact ? 16 : 4);
            rowButtons[row][0] = place(JasmButton.text(Component.empty(), b -> change(visible, 0), 0, 0, actionWidth, 17), controlsX, py);
            rowButtons[row][1] = place(JasmButton.text(Component.empty(), b -> change(visible, 1), 0, 0, enabledWidth, 17),
                    controlsX + actionWidth + 2, py);
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

    /** The line above the rows. The wafer window shows the last emptying result there instead. */
    Component rulesLine() { return label("rules"); }
    private Component label(String key) { return Component.translatable("screen.jasm.filter." + key); }
    Button place(Button button, int px, int py) { buttons.add(new Placed(button, px, py)); return button; }
    boolean isOpen() { return opened; }
    /** Which kinds of row can be added: items, fluids, or both. Takes effect when the editor is next opened. */
    void setModes(List<Mode> modes) { this.modes = List.copyOf(modes); }
    private boolean fluidOnly() { return !modes.contains(Mode.ITEM); }
    void setSave(Consumer<WaferSettings> save) { this.save = save; }
    void unfocus() { text.setFocused(false); }
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
        mode = modes.getFirst();
        allow = true;
        ghost = ItemStack.EMPTY;
        tagChoices = List.of();
        icons.clear();
        text.setValue("");
        text.setFocused(false);
        x = left;
        y = top;
        fit(width, screenHeight);
        update();
    }

    void close() { opened = false; grabX = -1; draggingScroll = false; text.setFocused(false); }
    private void layout() {
        for (Placed placed : buttons) placed.button().setPosition(x + placed.x(), y + placed.y());
        text.setPosition(x + 30, y + slotY + 1);
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
        for (int row = 0; row < rows; row++) {
            int at = scroll + row;
            boolean visible = tagChoices.isEmpty() && at < draft.rules().size();
            for (Button button : rowButtons[row]) if (button != null) button.visible = visible;
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
    }
    private void change(int row, int control) {
        int at = scroll + row;
        if (at >= draft.rules().size()) return;
        List<Filter> rules = new ArrayList<>(draft.rules());
        Filter rule = rules.get(at);
        switch (control) {
            case 0 -> rules.set(at, new Filter(rule.mode(), rule.value(), !rule.allow(), rule.enabled(), rule.voidExcess()));
            case 1 -> rules.set(at, new Filter(rule.mode(), rule.value(), rule.allow(), !rule.enabled(), rule.voidExcess()));
            case 5 -> rules.set(at, new Filter(rule.mode(), rule.value(), rule.allow(), rule.enabled(), !rule.voidExcess()));
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
        rules.add(candidate());
        send(new WaferSettings(rules));
        scroll = Math.max(0, rules.size() - rows);
        ghost = ItemStack.EMPTY;
        text.setValue("");
        allow = true;
        update();
    }

    /** Pick the ID or namespace, or offer every tag on the dropped item. The carried stack is never changed. */
    void setItem(ItemStack stack) {
        ghost = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        tagChoices = List.of();
        tagScroll = 0;
        if (ghost.isEmpty()) { text.setValue(""); return; }
        var id = BuiltInRegistries.ITEM.getKey(ghost.getItem());
        FluidResource held = FluidGrid.containedFluid(ghost);
        switch (mode) {
            case ITEM -> text.setValue(id.toString());
            case FLUID -> text.setValue(held == null ? "" : BuiltInRegistries.FLUID.getKey(held.getFluid()).toString());
            case MOD_ID -> text.setValue(fluidOnly() && held != null ? BuiltInRegistries.FLUID.getKey(held.getFluid()).getNamespace() : id.getNamespace());
            case TAG -> {
                java.util.stream.Stream<String> itemTags = fluidOnly() ? java.util.stream.Stream.empty()
                        : ghost.getItem().builtInRegistryHolder().tags().map(tag -> tag.location().toString());
                java.util.stream.Stream<String> fluidTags = held == null ? java.util.stream.Stream.empty()
                        : held.getFluid().builtInRegistryHolder().tags().map(tag -> tag.location().toString());
                List<String> tags = java.util.stream.Stream.concat(itemTags, fluidTags).distinct().sorted().toList();
                text.setValue(tags.size() == 1 ? tags.getFirst() : "");
                if (tags.size() > 1) tagChoices = tags;
            }
        }
        update();
    }

    /** A fluid dragged in from a recipe viewer fills in a Fluid row, if this editor takes them. */
    void setFluid(Fluid fluid) {
        if (!modes.contains(Mode.FLUID)) return;
        mode = Mode.FLUID;
        ghost = ItemStack.EMPTY;
        tagChoices = List.of();
        tagScroll = 0;
        text.setValue(BuiltInRegistries.FLUID.getKey(fluid).toString());
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
            graphics.text(font, tagChoices.isEmpty() ? title : label("choose_tag"), x + 7, y + 3, JasmGui.SUBTEXT, false);
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
        if (slotArea().contains(mx, my)) graphics.setTooltipForNextFrame(font, preview.isEmpty() ? label("pick") : preview.getHoverName(), mx, my);
        text.extractRenderState(graphics, mx, my, a);
        for (Placed placed : buttons) if (placed.button().visible) placed.button().extractRenderState(graphics, mx, my, a);
        drawOverlay(graphics, x, y, editorWidth, height);
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (locked()) return contains(event.x(), event.y());
        layout();
        update();
        for (Placed placed : buttons) if (placed.button().visible && placed.button().mouseClicked(event, doubleClick)) return true;
        if (slotArea().contains((int) event.x(), (int) event.y())) { setItem(carried.get()); return true; }
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
        int value = Math.clamp(offset() - (int) Math.signum(delta), 0, maxScroll());
        if (tagChoices.isEmpty()) scroll = value;
        else tagScroll = value;
        return true;
    }
    boolean keyPressed(KeyEvent event) {
        if (locked()) return true;
        if (event.isEscape()) {
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
    boolean charTyped(CharacterEvent event) { return locked() || text.isFocused() && text.charTyped(event); }
}
