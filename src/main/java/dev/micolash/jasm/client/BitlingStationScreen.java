package dev.micolash.jasm.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.micolash.jasm.station.BitlingStationBlockEntity;
import dev.micolash.jasm.station.BitlingStationMenu;
import dev.micolash.jasm.station.StationStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Bitling Station screen: the critter slot with its name and what it is doing, its battery, and the slider for how
 * far it may roam.
 */
public class BitlingStationScreen extends JasmScreen<BitlingStationMenu> {
    private static final int WIDTH = BitlingStationMenu.WIDTH;
    private static final int HEIGHT = BitlingStationMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int PAD = 8;
    private static final int TEXT_X = 36;
    private static final int NAME_Y = 24;
    private static final int STATUS_Y = 36;
    private static final int STATUS_HEIGHT = 14;
    private static final int BAR_HEIGHT = 8;
    private static final int BATTERY_LABEL_Y = 58;
    private static final int BATTERY_Y = 68;
    private static final int RADIUS_LABEL_Y = 82;
    private static final int SLIDER_Y = 92;
    private static final int SLIDER_WIDTH = WIDTH - 2 * PAD;
    private static final int HANDLE_WIDTH = 8;

    private boolean dragging;
    private int shownRadius = -1;

    public BitlingStationScreen(BitlingStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.inventoryLabelY = BitlingStationMenu.INVENTORY_Y - 10;
    }

    private boolean hasCritter() {
        return !menu.critter().isEmpty();
    }

    private int radius() {
        return dragging && shownRadius > 0 ? shownRadius : menu.radius();
    }

    private double radiusFraction() {
        int span = menu.maxRadius() - BitlingStationBlockEntity.MIN_RADIUS;
        return span <= 0 ? 1 : (radius() - BitlingStationBlockEntity.MIN_RADIUS) / (double) span;
    }

    // --- the slider ---

    private boolean overSlider(double mouseX, double mouseY) {
        return mouseX >= leftPos + PAD && mouseX < leftPos + PAD + SLIDER_WIDTH && mouseY >= topPos + SLIDER_Y - 2 && mouseY < topPos + SLIDER_Y + BAR_HEIGHT + 2;
    }

    private void dragTo(double mouseX) {
        int span = menu.maxRadius() - BitlingStationBlockEntity.MIN_RADIUS;
        double fraction = Math.clamp((mouseX - (leftPos + PAD + HANDLE_WIDTH / 2.0)) / (SLIDER_WIDTH - HANDLE_WIDTH), 0.0, 1.0);
        int value = BitlingStationBlockEntity.MIN_RADIUS + (int) Math.round(fraction * span);
        if (value != shownRadius) {
            shownRadius = value;
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, BitlingStationMenu.BUTTON_RADIUS + value);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overSlider(event.x(), event.y())) {
            dragging = true;
            shownRadius = -1;
            dragTo(event.x());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            dragTo(event.x());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    // --- drawing ---

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        JasmGui.panel(graphics, x, y, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        JasmGui.inset(graphics, x + TEXT_X, y + STATUS_Y, WIDTH - TEXT_X - PAD, STATUS_HEIGHT);
        int battery = menu.battery();
        JasmGui.bar(graphics, x + PAD, y + BATTERY_Y, SLIDER_WIDTH, BAR_HEIGHT, hasCritter() && battery > 0 ? menu.critterEnergy() / (double) battery : 0);
        JasmGui.slider(graphics, x + PAD, y + SLIDER_Y, SLIDER_WIDTH, BAR_HEIGHT, HANDLE_WIDTH, radiusFraction(),
                overSlider(mouseX, mouseY), dragging);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (hasCritter() && mouseX >= leftPos + PAD && mouseX < leftPos + PAD + SLIDER_WIDTH
                && mouseY >= topPos + BATTERY_LABEL_Y && mouseY < topPos + BATTERY_Y + BAR_HEIGHT) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.station.battery_tip",
                    String.format("%,d", menu.critterEnergy()), String.format("%,d", menu.battery())), mouseX, mouseY);
        }
    }

    private static int colour(StationStatus status) {
        return switch (status) {
            case ROAMING, RECHARGING -> JasmGui.GOOD;
            case HEADING_HOME, RESTING -> JasmGui.WARN;
            case WAITING_FOR_POWER, KNOCKED_OUT -> JasmGui.BAD;
            case EMPTY -> JasmGui.MUTED;
        };
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);

        ItemStack critter = menu.critter();
        StationStatus status = hasCritter() ? menu.status() : StationStatus.EMPTY;
        graphics.text(font, hasCritter() ? critter.getHoverName() : Component.translatable("screen.jasm.station.no_critter_name"), TEXT_X, NAME_Y,
                hasCritter() ? JasmGui.TEXT : JasmGui.MUTED, false);
        Component state = Component.translatable("screen.jasm.station.status." + status.key());
        if (status == StationStatus.KNOCKED_OUT) {
            state = Component.translatable("screen.jasm.station.knocked_out_in", menu.knockedOutSeconds());
        }
        Component label = Component.translatable("screen.jasm.station.status", state.copy().withColor(colour(status)));
        graphics.text(font, label, TEXT_X + 4, STATUS_Y + 3, JasmGui.SUBTEXT, false);

        Component battery = Component.translatable("screen.jasm.station.battery");
        graphics.text(font, battery, PAD, BATTERY_LABEL_Y, JasmGui.SUBTEXT, false);
        if (hasCritter()) {
            Component figures = Component.translatable("screen.jasm.station.battery_amount", String.format("%,d", menu.critterEnergy()),
                    String.format("%,d", menu.battery()));
            graphics.text(font, figures, PAD + SLIDER_WIDTH - font.width(figures), BATTERY_LABEL_Y, JasmGui.TEXT, false);
        }
        Component radius = Component.translatable("screen.jasm.station.radius");
        graphics.text(font, radius, PAD, RADIUS_LABEL_Y, JasmGui.SUBTEXT, false);
        Component blocks = Component.translatable("screen.jasm.station.radius_blocks", radius());
        graphics.text(font, blocks, PAD + SLIDER_WIDTH - font.width(blocks), RADIUS_LABEL_Y, JasmGui.TEXT, false);
    }
}
