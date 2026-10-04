package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.delivery.Deliveries;
import dev.micolash.jasm.delivery.DeliveryPayloads;
import dev.micolash.jasm.delivery.DeliveryView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Deck to Deck window: the send grid with its Send key on the left, the trips on their way on the right, and the
 * inbox along the bottom. Send swaps the trip list for the people this Deck can send to; picking one sends. Drawn
 * above the Deck screen and moved by dragging any empty spot of it. Its slots are the menu's own and follow it around.
 */
final class DeckSendWindow {
    static final int WIDTH = 176;
    static final int HEIGHT = 186;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 27;
    private static final int LIST_X = 68;
    private static final int LIST_Y = 26;
    private static final int LIST_WIDTH = WIDTH - LIST_X - 7;
    private static final int LIST_HEIGHT = 78;
    private static final int ROW = 19;
    private static final int ROWS = LIST_HEIGHT / ROW;
    private static final int KEY_Y = 88;
    private static final int INBOX_LABEL_Y = 112;
    private static final int INBOX_Y = 126;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);
    private static final int CANCEL = 9;

    private final DeckMenu menu;
    private final Font font;
    private final JasmButton close;
    private final JasmButton send;
    private final JasmButton storeAll;
    private boolean open;
    /** True while the list shows who to send to instead of the trips. */
    private boolean picking;
    private int scroll;
    private int x;
    private int y;
    private int grabX = -1;
    private int grabY;

    DeckSendWindow(DeckMenu menu, Font font) {
        this.menu = menu;
        this.font = font;
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> close(), 0, 0, 11, 11);
        send = JasmButton.text(Component.translatable("screen.jasm.send.send"), b -> togglePicking(), 0, 0, 56, 16);
        Component store = Component.translatable("screen.jasm.send.store_all");
        storeAll = JasmButton.text(store, b -> ClientPacketDistributor.sendToServer(new DeliveryPayloads.StoreAll(menu.containerId)),
                0, 0, font.width(store) + 12, 14);
        storeAll.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.jasm.send.store_all_hint")));
    }

    boolean isOpen() {
        return open;
    }

    /** Opens the window left of the key at {@code keyX}, level with the screen's top; or shuts it. */
    void toggle(int keyX, int top, int screenWidth, int screenHeight) {
        if (open) {
            close();
            return;
        }
        open = true;
        picking = false;
        scroll = 0;
        x = Math.clamp(keyX - 6 - WIDTH, 0, Math.max(0, screenWidth - WIDTH));
        y = Math.clamp(top + 20, 0, Math.max(0, screenHeight - HEIGHT));
        ClientPacketDistributor.sendToServer(new DeliveryPayloads.Watch(menu.containerId, true));
    }

    void close() {
        if (open) ClientPacketDistributor.sendToServer(new DeliveryPayloads.Watch(menu.containerId, false));
        open = false;
        grabX = -1;
        menu.setWindow(null);
    }

    private void togglePicking() {
        picking = !picking;
        scroll = 0;
        if (picking) {
            menu.deliveries().clearPeople();
            ClientPacketDistributor.sendToServer(new DeliveryPayloads.AskPeople(menu.containerId));
        }
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    Optional<Rect2i> area() {
        return open ? Optional.of(new Rect2i(x, y, WIDTH + 3, HEIGHT + 3)) : Optional.empty();
    }

    /** Moves the window's slots to where it is and tells the menu what it covers. Call before the screen draws. */
    void sync(int left, int top) {
        if (!open) {
            menu.setWindow(null);
            return;
        }
        for (int i = 0; i < 9; i++) {
            Slot slot = menu.slots.get(menu.sendStart() + i);
            slot.x = x - left + GRID_X + i % 3 * 18;
            slot.y = y - top + GRID_Y + i / 3 * 18;
        }
        for (int i = 0; i < menu.slots.size() - menu.inboxStart(); i++) {
            Slot slot = menu.slots.get(menu.inboxStart() + i);
            slot.x = x - left + GRID_X + i % 9 * 18;
            slot.y = y - top + INBOX_Y + i / 9 * 18;
        }
        menu.setWindow(new int[]{x - left, y - top, WIDTH, HEIGHT});
    }

    /** Whether the mouse is over one of the window's working slots. */
    boolean overSlot(double mouseX, double mouseY) {
        if (!open) return false;
        for (int i = menu.sendStart(); i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive()) continue;
            int sx = x + slotX(slot);
            int sy = y + slotY(slot);
            if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) return true;
        }
        return false;
    }

    private int slotX(Slot slot) {
        int i = slot.index - (slot.index >= menu.inboxStart() ? menu.inboxStart() : menu.sendStart());
        return GRID_X + (slot.index >= menu.inboxStart() ? i % 9 : i % 3) * 18;
    }

    private int slotY(Slot slot) {
        int i = slot.index - (slot.index >= menu.inboxStart() ? menu.inboxStart() : menu.sendStart());
        return slot.index >= menu.inboxStart() ? INBOX_Y + i / 9 * 18 : GRID_Y + i / 3 * 18;
    }

    /** The screen treats the mouse as hidden over the window, except over its slots. */
    boolean hidesMouse(double mouseX, double mouseY) {
        return contains(mouseX, mouseY) && !overSlot(mouseX, mouseY);
    }

    private boolean gridEmpty() {
        for (int i = 0; i < menu.sendSlots(); i++) if (!menu.slots.get(menu.sendStart() + i).getItem().isEmpty()) return false;
        return true;
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, long now) {
        if (!open) return;
        if (picking && gridEmpty()) picking = false;
        close.setPosition(x + WIDTH - 16, y + 5);
        send.setPosition(x + 7, y + KEY_Y);
        send.setMessage(Component.translatable(picking ? "screen.jasm.send.back" : "screen.jasm.send.send"));
        send.active = picking || !gridEmpty();
        storeAll.setPosition(x + WIDTH - 7 - storeAll.getWidth(), y + INBOX_LABEL_Y - 3);
        storeAll.active = !menu.inbox().isEmpty();
        if (grabX >= 0 || grabbable(mouseX, mouseY)) graphics.requestCursor(CursorTypes.RESIZE_ALL);

        JasmGui.window(graphics, x, y, WIDTH, HEIGHT);
        graphics.text(font, Component.translatable("screen.jasm.send.title"), x + 7, y + 8, JasmGui.TEXT, false);
        JasmGui.divider(graphics, x + 4, y + 21, WIDTH - 8);
        for (int i = menu.sendStart(); i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.isActive()) drawSlot(graphics, slot, x + slotX(slot), y + slotY(slot), mouseX, mouseY);
        }
        JasmGui.inset(graphics, x + LIST_X, y + LIST_Y, LIST_WIDTH, LIST_HEIGHT);
        if (picking) drawPeople(graphics, mouseX, mouseY);
        else drawTrips(graphics, mouseX, mouseY, now);
        JasmGui.divider(graphics, x + 4, y + INBOX_LABEL_Y - 7, WIDTH - 8);
        graphics.text(font, Component.translatable("screen.jasm.send.inbox"), x + 7, y + INBOX_LABEL_Y, JasmGui.SUBTEXT, false);
        close.extractRenderState(graphics, mouseX, mouseY, a);
        send.extractRenderState(graphics, mouseX, mouseY, a);
        storeAll.extractRenderState(graphics, mouseX, mouseY, a);
        Component notice = menu.notices().current(now);
        if (notice != null) {
            JasmGui.notice(graphics, font, notice, menu.notices().ok(), x + LIST_X + 1, y + LIST_Y + LIST_HEIGHT - 1, LIST_WIDTH - 2);
        }
    }

    /** The slot well and the item again above the window, since the screen draws slots underneath it. */
    private void drawSlot(GuiGraphicsExtractor graphics, Slot slot, int sx, int sy, int mouseX, int mouseY) {
        JasmGui.slot(graphics, sx, sy);
        ItemStack stack = slot.getItem();
        if (!stack.isEmpty()) {
            graphics.item(stack, sx, sy);
            graphics.itemDecorations(font, stack, sx, sy);
        }
        if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
            graphics.fill(sx, sy, sx + 16, sy + 16, JasmGui.HOVER);
        }
    }

    private String trimmed(String text, int room) {
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("...")) + "...";
    }

    static String time(int ticks) {
        int seconds = (ticks + 19) / 20;
        return seconds >= 60 ? String.format("%dm %02ds", seconds / 60, seconds % 60) : seconds + "s";
    }

    /** A message in the middle of the list, for when there is nothing to show. */
    private void drawEmpty(GuiGraphicsExtractor graphics, Component text, int colour) {
        List<FormattedCharSequence> lines = font.split(text, LIST_WIDTH - 8);
        int top = y + LIST_Y + (LIST_HEIGHT - lines.size() * 10) / 2;
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), x + LIST_X + (LIST_WIDTH - font.width(lines.get(i))) / 2, top + i * 10, colour, false);
        }
    }

    private void drawTrips(GuiGraphicsExtractor graphics, int mouseX, int mouseY, long now) {
        DeliveryView view = menu.deliveries();
        List<DeliveryPayloads.TripView> trips = view.trips();
        if (trips.isEmpty()) {
            drawEmpty(graphics, Component.translatable("screen.jasm.send.no_trips"), JasmGui.MUTED);
            return;
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, trips.size() - ROWS));
        for (int row = 0; row < ROWS && scroll + row < trips.size(); row++) {
            DeliveryPayloads.TripView trip = trips.get(scroll + row);
            int rx = x + LIST_X + 2;
            int ry = y + LIST_Y + 1 + row * ROW;
            graphics.item(trip.icon(), rx, ry + 1);
            JasmGui.itemCount(graphics, font, trip.count() > 1 ? String.valueOf(trip.count()) : "", rx, ry + 1);
            int left = view.left(trip, now);
            boolean waiting = left <= 0;
            String arrow = trip.returning() ? "↩ " : trip.outgoing() ? "→ " : "← ";
            String clock = waiting ? "" : time(left);
            int textX = rx + 21;
            int right = x + LIST_X + LIST_WIDTH - 3;
            int room = right - textX - font.width(clock) - 3;
            graphics.text(font, trimmed(arrow + trip.other(), room), textX, ry + 1, trip.outgoing() ? JasmGui.TEXT : JasmGui.ACCENT, false);
            graphics.text(font, clock, right - font.width(clock), ry + 1, JasmGui.SUBTEXT, false);
            int barRight = trip.outgoing() ? right - CANCEL - 2 : right;
            if (waiting) {
                graphics.text(font, trimmed(Component.translatable("screen.jasm.send.waiting").getString(), barRight - textX), textX, ry + 10,
                        JasmGui.WARN, false);
            } else {
                JasmGui.bar(graphics, textX, ry + 11, barRight - textX, 5, 1.0 - left / (double) trip.total());
            }
            if (trip.outgoing()) {
                int cx = right - CANCEL;
                int cy = ry + 9;
                boolean hover = mouseX >= cx && mouseX < cx + CANCEL && mouseY >= cy && mouseY < cy + CANCEL;
                if (hover) {
                    graphics.fill(cx, cy, cx + CANCEL, cy + CANCEL, JasmGui.HOVER);
                    graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.send.cancel"), mouseX, mouseY);
                }
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CLOSE.sprite(), cx + 2, cy + 2, 5, 5);
                if (hover) continue;
            }
            if (mouseX >= rx && mouseX < right && mouseY >= ry && mouseY < ry + ROW - 1) {
                graphics.setTooltipForNextFrame(font, contents(trip), mouseX, mouseY);
            }
        }
    }

    /** One line per kind of item a trip carries, with how many. */
    private List<FormattedCharSequence> contents(DeliveryPayloads.TripView trip) {
        List<ItemStack> kinds = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (ItemStack stack : trip.items()) {
            int at = -1;
            for (int i = 0; i < kinds.size() && at < 0; i++) {
                if (ItemStack.isSameItemSameComponents(kinds.get(i), stack)) at = i;
            }
            if (at < 0) {
                kinds.add(stack);
                counts.add(stack.getCount());
            } else {
                counts.set(at, counts.get(at) + stack.getCount());
            }
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            lines.addAll(font.split(Component.literal(counts.get(i) + " × ").append(kinds.get(i).getHoverName()), 180));
        }
        return lines;
    }

    private void drawPeople(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        DeliveryPayloads.People people = menu.deliveries().people();
        if (people == null) {
            drawEmpty(graphics, Component.translatable("screen.jasm.send.looking"), JasmGui.MUTED);
            return;
        }
        Deliveries.Refusal overall = Deliveries.Refusal.byId(people.refusal());
        if (overall != Deliveries.Refusal.NONE) {
            drawEmpty(graphics, Component.translatable(overall.key()), JasmGui.BAD);
            return;
        }
        if (people.people().isEmpty()) {
            drawEmpty(graphics, Component.translatable("screen.jasm.send.nobody"), JasmGui.MUTED);
            return;
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, people.people().size() - ROWS));
        int hovered = personAt(mouseX, mouseY);
        for (int row = 0; row < ROWS && scroll + row < people.people().size(); row++) {
            DeliveryPayloads.Person person = people.people().get(scroll + row);
            int rx = x + LIST_X + 1;
            int ry = y + LIST_Y + 1 + row * ROW;
            boolean ok = person.reason() == Deliveries.Refusal.NONE;
            if (scroll + row == hovered && ok) graphics.fill(rx, ry, rx + LIST_WIDTH - 2, ry + ROW - 1, JasmGui.HOVER);
            graphics.text(font, trimmed(person.name(), LIST_WIDTH - 8), rx + 3, ry + 1, ok ? JasmGui.TEXT : JasmGui.MUTED, false);
            Component line = ok
                    ? Component.translatable("screen.jasm.send.takes", time(person.ticks()))
                    : Component.translatable(person.reason().key());
            graphics.text(font, trimmed(line.getString(), LIST_WIDTH - 8), rx + 3, ry + 10, ok ? JasmGui.SUBTEXT : JasmGui.BAD, false);
            // The row only has room for a short word; the sentence behind it shows on hover.
            boolean upgrade = person.reason() == Deliveries.Refusal.NEEDS_UPGRADE || person.reason() == Deliveries.Refusal.THEY_NEED_UPGRADE;
            if (upgrade && scroll + row == hovered) {
                graphics.setTooltipForNextFrame(font, font.split(Component.translatable(person.reason().key() + ".hint"), 180), mouseX, mouseY);
            }
        }
    }

    /** The index of the person under the mouse, or -1. */
    private int personAt(double mouseX, double mouseY) {
        DeliveryPayloads.People people = menu.deliveries().people();
        if (!picking || people == null || people.refusal() != 0 || !inList(mouseX, mouseY)) return -1;
        int index = scroll + (int) ((mouseY - y - LIST_Y - 1) / ROW);
        return index >= 0 && index < people.people().size() && index - scroll < ROWS ? index : -1;
    }

    /** The trip whose cancel key is under the mouse, or null. */
    private DeliveryPayloads.@Nullable TripView cancelAt(double mouseX, double mouseY) {
        if (picking || !inList(mouseX, mouseY)) return null;
        int row = (int) ((mouseY - y - LIST_Y - 1) / ROW);
        List<DeliveryPayloads.TripView> trips = menu.deliveries().trips();
        if (row < 0 || row >= ROWS || scroll + row >= trips.size()) return null;
        DeliveryPayloads.TripView trip = trips.get(scroll + row);
        int cx = x + LIST_X + LIST_WIDTH - 3 - CANCEL;
        int cy = y + LIST_Y + 1 + row * ROW + 9;
        return trip.outgoing() && mouseX >= cx && mouseX < cx + CANCEL && mouseY >= cy && mouseY < cy + CANCEL ? trip : null;
    }

    private boolean inList(double mouseX, double mouseY) {
        return mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_WIDTH && mouseY >= y + LIST_Y && mouseY < y + LIST_Y + LIST_HEIGHT;
    }

    /** True when the click was the window's own; clicks on its slots are left to the screen. */
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick) || send.mouseClicked(event, doubleClick) || storeAll.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (overSlot(event.x(), event.y())) return false;
        int person = personAt(event.x(), event.y());
        if (person >= 0) {
            DeliveryPayloads.Person picked = menu.deliveries().people().people().get(person);
            if (picked.reason() == Deliveries.Refusal.NONE) {
                ClientPacketDistributor.sendToServer(new DeliveryPayloads.Send(menu.containerId, picked.id()));
                picking = false;
                scroll = 0;
            }
            return true;
        }
        DeliveryPayloads.TripView trip = cancelAt(event.x(), event.y());
        if (trip != null) {
            ClientPacketDistributor.sendToServer(new DeliveryPayloads.Cancel(menu.containerId, trip.id()));
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && grabbable(event.x(), event.y())) {
            grabX = (int) event.x() - x;
            grabY = (int) event.y() - y;
        }
        return true;
    }

    /** Any spot of the window that isn't a key, a slot or a row of the list picks it up. */
    private boolean grabbable(double mx, double my) {
        return contains(mx, my) && !overSlot(mx, my) && !inList(mx, my) && !close.isMouseOver(mx, my) && !send.isMouseOver(mx, my)
                && !storeAll.isMouseOver(mx, my);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!inList(mouseX, mouseY)) return contains(mouseX, mouseY);
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        return true;
    }

    /** Moves the window with the mouse while it is held, kept on screen. */
    boolean mouseDragged(MouseButtonEvent event, int screenWidth, int screenHeight) {
        if (grabX < 0) return false;
        x = Math.clamp((int) event.x() - grabX, 0, Math.max(0, screenWidth - WIDTH));
        y = Math.clamp((int) event.y() - grabY, 0, Math.max(0, screenHeight - HEIGHT));
        return true;
    }

    boolean mouseReleased() {
        boolean held = grabX >= 0;
        grabX = -1;
        return held;
    }
}
