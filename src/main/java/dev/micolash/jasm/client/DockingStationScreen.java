package dev.micolash.jasm.client;

import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.dock.DockingStationMenu;
import dev.micolash.jasm.dock.DockPayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The Docking Station screen: the Deck slot and whose Deck it is, then a recessed section two lines tall with the lock
 * (or what to do with an empty station), then a key to lock or unlock and one to take the Deck out. Anything that stops
 * the network using the Deck takes turns with the lock text in the section, and a refusal briefly takes its place.
 *
 * <p>Layout, in GUI pixels (the same at every GUI scale): 176 wide, title and charge bar on row 6, Deck slot 22 to 38,
 * name line 44 to 53, section 55 to 81 with its two lines at 59 and 69 wrapped to 154 pixels, keys 85 to 101, inventory
 * label 105, inventory from 116. Every message fits two lines at that width; in the locked text a long player name is
 * cut short with an ellipsis so it does too. Anything longer would be cut off, never drawn over something else.
 */
public class DockingStationScreen extends JasmScreen<DockingStationMenu> {
    private static final int WIDTH = DockingStationMenu.WIDTH;
    private static final int HEIGHT = DockingStationMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int BAR_WIDTH = 50;
    private static final int BAR_X = WIDTH - 8 - HELP_ROOM - BAR_WIDTH;
    private static final int BAR_Y = 6;
    private static final int NAME_Y = 44;
    private static final int SECTION_X = 7;
    private static final int SECTION_Y = 55;
    private static final int SECTION_WIDTH = WIDTH - 2 * SECTION_X;
    private static final int LINE_HEIGHT = 10;
    private static final int SECTION_LINES = 2;
    private static final int SECTION_PAD = 4;
    /** Four pixels above the first line and below the last one's letters (each line is nine high plus one of gap). */
    private static final int SECTION_HEIGHT = SECTION_LINES * LINE_HEIGHT + 2 * SECTION_PAD - 2;
    private static final int TEXT_WIDTH = SECTION_WIDTH - 2 * SECTION_PAD;
    private static final int KEYS_Y = SECTION_Y + SECTION_HEIGHT + 4;
    private static final int KEY_WIDTH = 78;
    private static final int KEY_HEIGHT = 16;
    /** Ticks each message shows for when two take turns in the section. */
    private static final int TURN_TICKS = 60;

    private JasmButton lock;
    private JasmButton takeOut;
    /** The locked text last fitted, and the name it was fitted for. */
    private @Nullable Component lockedText;
    private String lockedFor = "";

    public DockingStationScreen(DockingStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelX = DockingStationMenu.INVENTORY_X;
        this.inventoryLabelY = DockingStationMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        addHelp(WIDTH - 7, "items/docking-station.md");
        lock = addRenderableWidget(JasmButton.text(Component.empty(), b -> send(menu.locked() ? DockPayloads.Action.UNLOCK : DockPayloads.Action.LOCK),
                leftPos + 8, topPos + KEYS_Y, KEY_WIDTH, KEY_HEIGHT));
        takeOut = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.docking_station.take_out"), b -> send(DockPayloads.Action.TAKE_OUT),
                leftPos + WIDTH - 8 - KEY_WIDTH, topPos + KEYS_Y, KEY_WIDTH, KEY_HEIGHT));
        showKeys();
    }

    private void send(DockPayloads.Action action) {
        ClientPacketDistributor.sendToServer(DockPayloads.Act.of(menu.containerId, action, menu.shownDeck()));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        showKeys();
    }

    /** Both keys stay pressable while a Deck is docked: who may use them is the server's call, and it says no in the panel. */
    private void showKeys() {
        boolean docked = !menu.deck().isEmpty();
        lock.setMessage(Component.translatable(menu.locked() ? "screen.jasm.docking_station.unlock" : "screen.jasm.docking_station.lock"));
        lock.active = docked;
        takeOut.active = docked;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
        JasmGui.bar(graphics, leftPos + BAR_X, topPos + BAR_Y, BAR_WIDTH, 7, menu.energy() / (double) menu.capacity());
        JasmGui.inset(graphics, leftPos + SECTION_X, topPos + SECTION_Y, SECTION_WIDTH, SECTION_HEIGHT);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (mouseX >= leftPos + BAR_X && mouseX < leftPos + BAR_X + BAR_WIDTH && mouseY >= topPos + BAR_Y && mouseY < topPos + BAR_Y + 7) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.machine.charge", String.format("%,d", menu.energy()),
                    String.format("%,d", menu.capacity())), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        boolean docked = !menu.deck().isEmpty();
        String name = menu.deckOwnerName().isEmpty() ? "?" : menu.deckOwnerName();
        if (docked) {
            Component whose = Component.translatable("screen.jasm.docking_station.whose", name);
            graphics.text(font, whose, (imageWidth - font.width(whose)) / 2, NAME_Y, JasmGui.TEXT, false);
        }
        long now = minecraft.level.getGameTime();
        List<Line> turns = sectionLines(docked, name, now);
        Line shown = turns.get((int) (now / TURN_TICKS % turns.size()));
        List<FormattedCharSequence> parts = font.split(shown.text(), TEXT_WIDTH);
        for (int i = 0; i < Math.min(SECTION_LINES, parts.size()); i++) {
            FormattedCharSequence part = parts.get(i);
            graphics.text(font, part, (imageWidth - font.width(part)) / 2, SECTION_Y + SECTION_PAD + i * LINE_HEIGHT, shown.colour(), false);
        }
    }

    /**
     * The messages that take turns in the section: a refusal or other notice alone while it lasts; else the lock text,
     * then what stops the network using the Deck or the network owner's override, if any.
     */
    private List<Line> sectionLines(boolean docked, String name, long now) {
        List<Line> lines = new ArrayList<>();
        Component notice = menu.notices().current(now);
        if (notice != null) {
            lines.add(new Line(notice, menu.notices().ok() ? JasmGui.GOOD : JasmGui.BAD));
            return lines;
        }
        if (!docked) {
            lines.add(new Line(Component.translatable("screen.jasm.docking_station.empty"), JasmGui.SUBTEXT));
            return lines;
        }
        lines.add(new Line(menu.locked() ? lockedText(name) : Component.translatable("screen.jasm.docking_station.unlocked"),
                menu.locked() ? JasmGui.WARN : JasmGui.GOOD));
        if (!menu.may(DockingStationMenu.LINKED)) {
            lines.add(new Line(Component.translatable("screen.jasm.docking_station.not_linked"), JasmGui.BAD));
        } else if (!menu.running()) {
            lines.add(new Line(MachineStatusText.noPower(menu.containerId, "screen.jasm.docking_station.no_power"), JasmGui.BAD));
        } else if (menu.energy() <= 0 && !full(menu.deck())) {
            lines.add(new Line(Component.translatable("screen.jasm.docking_station.no_power"), JasmGui.BAD));
        } else if (menu.locked() && menu.may(DockingStationMenu.NETWORK_OWNER) && !menu.may(DockingStationMenu.DECK_OWNER)) {
            lines.add(new Line(Component.translatable("screen.jasm.docking_station.override"), JasmGui.SUBTEXT));
        }
        return lines;
    }

    /** The locked text, with a name too long for its two lines cut short with an ellipsis; ordinary names show in full. */
    private Component lockedText(String name) {
        if (lockedText == null || !lockedFor.equals(name)) {
            String shown = name;
            Component text = Component.translatable("screen.jasm.docking_station.locked", shown);
            while (font.split(text, TEXT_WIDTH).size() > SECTION_LINES && shown.length() > 1) {
                shown = shown.substring(0, shown.length() - 1);
                text = Component.translatable("screen.jasm.docking_station.locked", shown + "\u2026");
            }
            lockedText = text;
            lockedFor = name;
        }
        return lockedText;
    }

    private static boolean full(ItemStack deck) {
        return deck.getItem() instanceof DeckItem item && DeckItem.energy(deck) >= item.tier().battery();
    }

    private record Line(Component text, int colour) {}
}
