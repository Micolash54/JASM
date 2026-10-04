package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.bay.BayKind;
import dev.micolash.jasm.bay.BayMenu;
import dev.micolash.jasm.bay.BayRedstone;
import dev.micolash.jasm.bay.BayStatus;
import dev.micolash.jasm.bay.DemolitionBayBlockEntity;
import dev.micolash.jasm.bay.DeployMode;
import dev.micolash.jasm.config.JasmClientConfig;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * A bay's panel: the grid with the tank beside it, what the bay is doing and how often, Place / Drop or the enchantments,
 * the power, and the upgrade column past the right edge with the redstone key under it.
 */
public class BayScreen extends JasmScreen<BayMenu> {
    private static final int HEIGHT = BayMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int KEY_X = BayMenu.WIDTH;
    private static final int TANK_X = 66;
    private static final int TANK_Y = 17;
    private static final int TANK_WIDTH = 22;
    private static final int TANK_HEIGHT = 54;
    private static final int SIDE_X = 94;
    private static final int SIDE_WIDTH = 74;
    private static final int MODE_Y = 44;
    private static final int ENCHANT_Y = 43;
    private static final int ENCHANT_HEIGHT = 29;
    private static final int POWER_X = 7;
    private static final int POWER_Y = 76;
    private static final int POWER_WIDTH = 162;
    private static final int POWER_HEIGHT = 11;
    /** The keys under the upgrade slots: the Demolition Bay's sound key, then the redstone key. */
    private static final int KEYS_Y = BayMenu.UPGRADE_Y + 4 * 18 + 4;
    private static final int KEY_STEP = JasmGui.SIDE_KEY_HEIGHT + 2;
    private JasmFrame frame;
    private boolean frameRedstone;
    private @Nullable JasmButton redstone;
    private @Nullable JasmButton sound;
    private boolean shownMuted;
    private @Nullable JasmButton place;
    private @Nullable JasmButton drop;
    private @Nullable BayRedstone shownRedstone;

    public BayScreen(BayMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KEY_X + JasmGui.SIDE_KEY_WIDTH + 3, HEIGHT);
        this.inventoryLabelY = BayMenu.INVENTORY_Y - 11;
        this.frame = frame(false);
    }

    /** The sound key on a Demolition Bay, else nothing, under the upgrade slots; the redstone key goes below it. */
    private int redstoneY() {
        return menu.kind() == BayKind.DEMOLITION ? KEYS_Y + KEY_STEP : KEYS_Y;
    }

    /** The main panel with the upgrade column joined to it, long enough for the keys under the slots. */
    private JasmFrame frame(boolean withRedstone) {
        int bottom = withRedstone ? redstoneY() + JasmGui.SIDE_KEY_HEIGHT
                : menu.kind() == BayKind.DEMOLITION ? KEYS_Y + JasmGui.SIDE_KEY_HEIGHT : BayMenu.UPGRADE_Y + 3 * 18 + 17;
        return JasmFrame.rounded(new int[]{0, 0, BayMenu.WIDTH, imageHeight},
                new int[]{KEY_X - 14, BayMenu.UPGRADE_Y - 4, 38, bottom + 5 - (BayMenu.UPGRADE_Y - 4)});
    }

    @Override
    protected void init() {
        super.init();
        frameRedstone = menu.hasRedstoneUpgrade();
        frame = frame(frameRedstone);
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
                    leftPos + KEY_X, topPos + KEYS_Y, JasmGui.SIDE_KEY_WIDTH, JasmGui.SIDE_KEY_HEIGHT));
            soundTooltip();
        }
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
        BayStatus status = menu.status();
        dot(graphics, SIDE_X, 20, statusColor(status));
        graphics.text(font, Component.translatable(status.shortKey()), SIDE_X + 8, 19, JasmGui.TEXT, false);
        graphics.text(font, Component.translatable("screen.jasm.bay.every", seconds(menu.cycleTicks())), SIDE_X, 31, JasmGui.SUBTEXT, false);
        Component power = Component.translatable("screen.jasm.workshop.power_amount", String.format("%,d", menu.energy()),
                String.format("%,d", menu.capacity()));
        JasmGui.labelledBar(graphics, font, power, POWER_X, POWER_Y, POWER_WIDTH, POWER_HEIGHT,
                menu.capacity() <= 0 ? 0 : menu.energy() / (double) menu.capacity());
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
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
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
    }
}
