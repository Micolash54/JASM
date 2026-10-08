package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.bay.BayKind;
import dev.micolash.jasm.bay.BayNetwork;
import dev.micolash.jasm.bay.BayMenu;
import dev.micolash.jasm.bay.BayRedstone;
import dev.micolash.jasm.bay.BayStatus;
import dev.micolash.jasm.bay.DemolitionBayBlockEntity;
import dev.micolash.jasm.bay.DeployMode;
import dev.micolash.jasm.config.JasmClientConfig;
import dev.micolash.jasm.storage.WaferSettings;
import dev.micolash.jasm.transfer.TransferPortMenu;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * A bay's panel: the power in the title row, the grid with the tank beside it, what the bay is doing and how often, Place /
 * Drop or the enchantments, the filter (which folds away), and the upgrade column past the right edge with the redstone key
 * under it.
 */
public class BayScreen extends JasmScreen<BayMenu> {
    private static final int KEY_X = BayMenu.WIDTH;
    private static final int TANK_X = 66;
    private static final int TANK_Y = 17;
    private static final int TANK_WIDTH = 22;
    private static final int TANK_HEIGHT = 54;
    private static final int SIDE_X = 94;
    private static final int SIDE_WIDTH = BayMenu.WIDTH - 8 - SIDE_X;
    private static final int MODE_Y = 44;
    private static final int ENCHANT_Y = 43;
    private static final int ENCHANT_HEIGHT = 29;
    // title row, same spot as the rack's
    private static final int POWER_WIDTH = 50;
    private static final int POWER_X = BayMenu.WIDTH - 8 - HELP_ROOM - POWER_WIDTH;
    private static final int POWER_Y = 6;
    private static final int POWER_HEIGHT = 7;
    private static final int HEIGHT_WITHOUT_ROWS = BayMenu.inventoryY(false, 0) + 58 + 18 + 6;
    /** The keys under the upgrade slots: the I/O grid's, the Demolition Bay's sound key, then the redstone key. */
    private static final int KEYS_Y = BayMenu.UPGRADE_Y + 4 * 18 + 4;
    private static final int KEY_STEP = JasmGui.SIDE_KEY_HEIGHT + 2;
    /** The filter's heading, as the editor draws it, and the fold key just after it. */
    private static final int FILTER_LABEL_X = 15;
    private static final int FILTER_LABEL_Y = BayMenu.FILTER_Y + 3;
    private static final int FOLD_SIZE = 12;
    private static final JasmButton.Icon FOLDED = new JasmButton.Icon(Jasm.id("icon/triangle_right"), 4, 6);
    private static final JasmButton.Icon UNFOLDED = new JasmButton.Icon(Jasm.id("icon/triangle_down"), 6, 4);
    private JasmFrame frame;
    private boolean frameRedstone;
    private @Nullable JasmButton redstone;
    private @Nullable JasmButton sound;
    private @Nullable IoGridWindow io;
    private boolean shownMuted;
    private @Nullable JasmButton place;
    private @Nullable JasmButton drop;
    private @Nullable BayRedstone shownRedstone;
    private @Nullable ItemFilterEditor editor;
    private @Nullable JasmButton fold;
    private int filterRows = 1;

    public BayScreen(BayMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KEY_X + JasmGui.SIDE_KEY_WIDTH + 3, 0);
        this.inventoryLabelX = BayMenu.INVENTORY_X;
        layout();
        this.frame = frame(false);
    }

    private static boolean collapsed() {
        return JasmClientConfig.bayFilterCollapsed();
    }

    /**
     * Sizes the panel to the filter, shown or folded away, and moves the inventory under it. The filter shows as many rows
     * as the game window has room for, down to one, like the Deck's grid.
     */
    private void layout() {
        filterRows = Math.clamp((height - HEIGHT_WITHOUT_ROWS) / TransferPortMenu.FILTER_ROW, 1, BayMenu.FILTER_ROWS);
        int inventoryY = BayMenu.inventoryY(collapsed(), filterRows);
        menu.layout(collapsed(), filterRows);
        imageHeight = inventoryY + 58 + 18 + 6;
        inventoryLabelY = inventoryY - 11;
    }

    private void toggleFilter() {
        JasmClientConfig.setBayFilterCollapsed(!collapsed());
        rebuildWidgets();
    }

    /** The sound key on a Demolition Bay, under the I/O key. */
    private static int soundY() {
        return KEYS_Y + KEY_STEP;
    }

    /** The redstone key goes below the others. */
    private int redstoneY() {
        return menu.kind() == BayKind.DEMOLITION ? soundY() + KEY_STEP : KEYS_Y + KEY_STEP;
    }

    /** The main panel with the upgrade column joined to it, long enough for the keys under the slots. */
    private JasmFrame frame(boolean withRedstone) {
        int bottom = withRedstone ? redstoneY() + JasmGui.SIDE_KEY_HEIGHT
                : menu.kind() == BayKind.DEMOLITION ? soundY() + JasmGui.SIDE_KEY_HEIGHT : KEYS_Y + JasmGui.SIDE_KEY_HEIGHT;
        return JasmFrame.rounded(new int[]{0, 0, BayMenu.WIDTH, imageHeight},
                new int[]{KEY_X - 14, BayMenu.UPGRADE_Y - 4, 38, bottom + 5 - (BayMenu.UPGRADE_Y - 4)});
    }

    @Override
    protected void init() {
        layout();
        super.init();
        frameRedstone = menu.hasRedstoneUpgrade();
        frame = frame(frameRedstone);
        addHelp(BayMenu.WIDTH - 7, menu.kind() == BayKind.DEMOLITION ? "items/demolition-bay.md" : "items/deployment-bay.md");
        shownRedstone = null;
        redstone = addRenderableWidget(JasmButton.icon(
                () -> new JasmButton.Icon(Jasm.id("icon/redstone_" + menu.redstone().getSerializedName()), 12, 12),
                redstoneLabel(), b -> click(BayMenu.BUTTON_REDSTONE),
                leftPos + KEY_X, topPos + redstoneY(), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
        if (menu.kind() == BayKind.DEMOLITION) {
            shownMuted = JasmClientConfig.bayLaserMuted();
            sound = addRenderableWidget(JasmButton.icon(
                    () -> new JasmButton.Icon(Jasm.id(JasmClientConfig.bayLaserMuted() ? "icon/sound_off" : "icon/sound_on"), 12, 12),
                    soundLabel(), b -> JasmClientConfig.setBayLaserMuted(!JasmClientConfig.bayLaserMuted()),
                    leftPos + KEY_X, topPos + soundY(), JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
            soundTooltip();
        }
        if (io == null) io = new IoGridWindow(font, true, menu::sides, this::click);
        addRenderableWidget(io.key(leftPos + KEY_X, topPos + KEYS_Y, topPos));
        editor = null;
        if (!collapsed()) {
            editor = new ItemFilterEditor(font, filterRows, false, menu::getCarried, BayMenu.WIDTH - 16);
            editor.setSave(this::sendFilter);
            editor.open(menu.filter(), Component.translatable("screen.jasm.bay.filter"), Component.empty(),
                    ItemStack.EMPTY, leftPos + 8, topPos + BayMenu.FILTER_Y, width, height);
        }
        Component foldLabel = Component.translatable(collapsed() ? "screen.jasm.bay.filter_show" : "screen.jasm.bay.filter_hide");
        fold = addRenderableWidget(JasmButton.icon(() -> collapsed() ? FOLDED : UNFOLDED, foldLabel, b -> toggleFilter(),
                0, 0, FOLD_SIZE, FOLD_SIZE));
        fold.setTooltip(Tooltip.create(foldLabel));
        placeFold();
        if (menu.kind() == BayKind.DEPLOYMENT) {
            int half = SIDE_WIDTH / 2;
            place = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.bay.mode.place"),
                    b -> click(BayMenu.BUTTON_MODE), leftPos + SIDE_X, topPos + MODE_Y, half, 16));
            drop = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.bay.mode.drop"),
                    b -> click(BayMenu.BUTTON_MODE), leftPos + SIDE_X + half, topPos + MODE_Y, SIDE_WIDTH - half, 16));
            place.setTooltip(Tooltip.create(Component.translatable("screen.jasm.bay.mode.place_hint")));
            drop.setTooltip(Tooltip.create(Component.translatable("screen.jasm.bay.mode.drop_hint")));
        }
        containerTick();
    }

    // the heading turns into "Choose a tag" while picking tags, so the key follows its width
    private void placeFold() {
        if (fold == null) return;
        Component heading = editor != null ? editor.heading() : Component.translatable("screen.jasm.bay.filter");
        fold.setPosition(leftPos + FILTER_LABEL_X + font.width(heading) + 4, topPos + FILTER_LABEL_Y + 4 - FOLD_SIZE / 2);
    }

    private void sendFilter(WaferSettings filter) {
        menu.configureFilter(filter);
        ClientPacketDistributor.sendToServer(new BayNetwork.Filter(menu.containerId, filter));
    }

    private Component soundLabel() {
        return Component.translatable(JasmClientConfig.bayLaserMuted() ? "screen.jasm.bay.sound_off" : "screen.jasm.bay.sound_on");
    }

    private void soundTooltip() {
        if (sound == null) return;
        Component label = soundLabel();
        sound.setMessage(label);
        sound.setTooltip(Tooltip.create(label.copy().append("\n").append(Component.translatable("screen.jasm.bay.sound_hint"))));
    }

    private Component redstoneLabel() {
        return Component.translatable("screen.jasm.bay.redstone_key",
                Component.translatable("screen.jasm.bay.redstone." + menu.redstone().getSerializedName()));
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean installed = menu.hasRedstoneUpgrade();
        if (installed != frameRedstone) {
            frameRedstone = installed;
            frame = frame(installed);
        }
        if (redstone != null) {
            redstone.visible = installed;
            redstone.active = installed;
            redstone.setLatched(menu.redstone() != BayRedstone.IGNORE);
            if (shownRedstone != menu.redstone()) {
                shownRedstone = menu.redstone();
                Component label = redstoneLabel();
                redstone.setMessage(label);
                redstone.setTooltip(Tooltip.create(label.copy().append("\n").append(Component.translatable("screen.jasm.bay.redstone_hint"))));
            }
        }
        if (sound != null) {
            sound.setLatched(JasmClientConfig.bayLaserMuted());
            if (shownMuted != JasmClientConfig.bayLaserMuted()) {
                shownMuted = JasmClientConfig.bayLaserMuted();
                soundTooltip();
            }
        }
        if (place != null && drop != null) {
            // The chosen mode can't be pressed; the other one switches to it.
            place.setSelected(menu.mode() == DeployMode.PLACE);
            drop.setSelected(menu.mode() == DeployMode.DROP);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        frame.draw(graphics, leftPos, topPos);
        for (Slot slot : menu.slots) JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
        tank(graphics, leftPos + TANK_X, topPos + TANK_Y);
        if (menu.kind() == BayKind.DEMOLITION) {
            JasmGui.inset(graphics, leftPos + SIDE_X, topPos + ENCHANT_Y, SIDE_WIDTH, ENCHANT_HEIGHT);
        }
    }

    /** A tall well, the fluid rising from the bottom, and glass with tick marks over it. */
    private void tank(GuiGraphicsExtractor graphics, int x, int y) {
        JasmGui.inset(graphics, x, y, TANK_WIDTH, TANK_HEIGHT);
        int inner = TANK_HEIGHT - 2;
        FluidResource fluid = menu.fluid();
        if (!fluid.isEmpty() && menu.tankCapacity() > 0) {
            int filled = (int) Math.ceil(inner * Math.min(1.0, menu.fluidAmount() / (double) menu.tankCapacity()));
            FluidGrid.drawTank(graphics, fluid, x + 1, y + 1 + inner - filled, TANK_WIDTH - 2, filled);
        }
        graphics.fill(x + 2, y + 1, x + 4, y + 1 + inner, 0x26FFFFFF);
        graphics.fill(x + 4, y + 1, x + 5, y + 1 + inner, 0x14FFFFFF);
        for (int i = 1; i < 8; i++) {
            int ty = y + 1 + inner * i / 8;
            int length = i % 2 == 0 ? 7 : 4;
            int right = x + TANK_WIDTH - 1;
            graphics.fill(right - length, ty, right, ty + 1, 0xC011111B);
            graphics.fill(right - length, ty + 1, right, ty + 2, 0x30FFFFFF);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        // Folded away, the editor isn't there to draw its heading.
        if (editor == null) graphics.text(font, Component.translatable("screen.jasm.bay.filter"), FILTER_LABEL_X, FILTER_LABEL_Y, JasmGui.SUBTEXT, false);
        BayStatus status = menu.status();
        dot(graphics, SIDE_X, 20, statusColor(status));
        graphics.text(font, Component.translatable(status.shortKey()), SIDE_X + 8, 19, JasmGui.TEXT, false);
        graphics.text(font, Component.translatable("screen.jasm.bay.every", seconds(menu.cycleTicks())), SIDE_X, 31, JasmGui.SUBTEXT, false);
        JasmGui.bar(graphics, POWER_X, POWER_Y, POWER_WIDTH, POWER_HEIGHT, menu.capacity() <= 0 ? 0 : menu.energy() / (double) menu.capacity());
        if (menu.kind() == BayKind.DEMOLITION) {
            List<Component> lines = enchantmentLines();
            if (!lines.isEmpty()) {
                graphics.text(font, lines.getFirst(), SIDE_X + 4, ENCHANT_Y + 4, JasmGui.ACCENT, false);
                if (lines.size() == 2) {
                    graphics.text(font, lines.get(1), SIDE_X + 4, ENCHANT_Y + 16, JasmGui.ACCENT, false);
                } else if (lines.size() > 2) {
                    graphics.text(font, Component.translatable("screen.jasm.bay.more_enchantments", lines.size() - 1),
                            SIDE_X + 4, ENCHANT_Y + 16, JasmGui.SUBTEXT, false);
                }
            }
        }
    }

    /** A 5 × 5 light with its corners cut off. */
    private static void dot(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 1, y, x + 4, y + 5, color);
        graphics.fill(x, y + 1, x + 5, y + 4, color);
    }

    private static int statusColor(BayStatus status) {
        return switch (status) {
            case WORKING -> JasmGui.GOOD;
            case SLEEPING -> JasmGui.MUTED;
            case NO_POWER, NOT_ALLOWED -> JasmGui.BAD;
            default -> JasmGui.WARN;
        };
    }

    /** Ticks as seconds, without trailing zeros: 20 is "1", 8 is "0.4". */
    private static String seconds(int ticks) {
        return BigDecimal.valueOf(Math.max(1, ticks)).divide(BigDecimal.valueOf(20)).stripTrailingZeros().toPlainString();
    }

    /** The Demolition Bay's enchantments, from the block as this game last heard of it. */
    private List<Component> enchantmentLines() {
        List<Component> lines = new ArrayList<>();
        BlockPos pos = menu.bayPos();
        if (minecraft == null || minecraft.level == null || pos == null
                || !(minecraft.level.getBlockEntity(pos) instanceof DemolitionBayBlockEntity bay)) return lines;
        for (var entry : bay.enchantments().entrySet()) {
            lines.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()));
        }
        return lines;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realX, int realY, float a) {
        placeFold();
        // Under the I/O window nothing else lights up or shows a tooltip.
        boolean hidden = io.contains(realX, realY);
        int mouseX = hidden ? -1000 : realX;
        int mouseY = hidden ? -1000 : realY;
        super.extractContents(graphics, mouseX, mouseY, a);
        int x = mouseX - leftPos;
        int y = mouseY - topPos;
        if (x >= TANK_X && x < TANK_X + TANK_WIDTH && y >= TANK_Y && y < TANK_Y + TANK_HEIGHT) {
            FluidResource fluid = menu.fluid();
            Component tip = fluid.isEmpty()
                    ? Component.translatable("screen.jasm.bay.tank_empty", String.format("%,d", menu.tankCapacity()))
                    : Component.translatable("screen.jasm.bay.tank", fluid.getHoverName(), String.format("%,d", menu.fluidAmount()),
                            String.format("%,d", menu.tankCapacity()));
            graphics.setTooltipForNextFrame(font, tip, mouseX, mouseY);
        } else if (x >= POWER_X && x < POWER_X + POWER_WIDTH && y >= POWER_Y && y < POWER_Y + POWER_HEIGHT) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.workshop.power_amount",
                    String.format("%,d", menu.energy()), String.format("%,d", menu.capacity())), mouseX, mouseY);
        } else if (x >= SIDE_X && x < SIDE_X + SIDE_WIDTH && y >= 18 && y < 28) {
            // The short word on the panel; the whole line when it says more.
            Component full = Component.translatable(menu.status().key());
            if (!full.getString().equals(Component.translatable(menu.status().shortKey()).getString())) {
                graphics.setTooltipForNextFrame(font, full, mouseX, mouseY);
            }
        } else if (menu.kind() == BayKind.DEMOLITION && x >= SIDE_X && x < SIDE_X + SIDE_WIDTH && y >= ENCHANT_Y
                && y < ENCHANT_Y + ENCHANT_HEIGHT) {
            List<Component> lines = enchantmentLines();
            if (lines.size() > 2) graphics.setTooltipForNextFrame(font, lines, Optional.empty(), mouseX, mouseY);
        }
        if (editor != null) {
            graphics.nextStratum();
            editor.draw(graphics, mouseX, mouseY, a, width, height);
        }
        if (io.isOpen()) {
            graphics.nextStratum();
            io.draw(graphics, realX, realY, a);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (io.contains(event.x(), event.y())) return io.mouseClicked(event, doubleClick);
        // The fold key sits on the filter's heading, inside the editor's area.
        if (editor != null && !(fold != null && fold.isMouseOver(event.x(), event.y()))) {
            if (editor.contains(event.x(), event.y())) return editor.mouseClicked(event, doubleClick);
            editor.unfocus();
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return io.mouseDragged(event, width, height) || editor != null && editor.mouseDragged(event, width, height)
                || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return io.mouseReleased() | (editor != null && editor.mouseReleased(event)) | super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (io.contains(x, y)) return true;
        return editor != null && editor.contains(x, y) ? editor.mouseScrolled(sy) : super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (io.isOpen() && event.isEscape()) {
            io.close();
            return true;
        }
        return editor != null && editor.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return editor != null && editor.charTyped(event) || super.charTyped(event);
    }

    @Override
    protected boolean hasClickedOutside(double x, double y, int left, int top) {
        return !io.contains(x, y) && super.hasClickedOutside(x, y, left, top);
    }

    // JEI drop target, none while folded
    public List<Rect2i> filterSlots() {
        return editor == null ? List.of() : List.of(editor.slotArea());
    }

    public void setFilterItem(int index, Item item) {
        if (index == 0 && editor != null) editor.setItem(new ItemStack(item), false);
    }

    public void setFilterMaterial(int index, Identifier id) {
        if (index == 0 && editor != null) editor.setMaterial(id);
    }
}
