package dev.micolash.jasm.client;

import dev.micolash.jasm.crystal.CrystalFoundryBlockEntity;
import dev.micolash.jasm.crystal.CrystalFoundryMenu;
import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Foundry screen: the seed slot, the crystal it is growing with the progress under it and how many crystals the
 * seed has made so far, the output grid, and the power across the full width. The I/O grid's key hangs off the right
 * side.
 */
public class CrystalFoundryScreen extends JasmScreen<CrystalFoundryMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = CrystalFoundryMenu.INVENTORY_Y + 58 + 18 + 6;
    private static final int CRYSTAL_X = 68;
    private static final int CRYSTAL_Y = 28;
    private static final int PROGRESS_X = 58;
    private static final int PROGRESS_WIDTH = 36;
    private static final int PROGRESS_Y = 51;
    private static final int COUNT_Y = 59;
    private static final int POWER_X = 8;
    private static final int POWER_WIDTH = WIDTH - 16;
    private static final int ROW_HEIGHT = 12;
    private static final int KEY_X = WIDTH;
    private static final JasmFrame FRAME = JasmFrame.rounded(new int[]{0, 0, WIDTH, HEIGHT}, JasmGui.sideStrip(KEY_X, 1));
    private final ItemStack crystal = new ItemStack(JasmItems.DATA_CRYSTAL.get());
    private @Nullable IoGridWindow io;

    public CrystalFoundryScreen(CrystalFoundryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KEY_X + JasmGui.SIDE_KEY_WIDTH + 3, HEIGHT);
        this.inventoryLabelY = CrystalFoundryMenu.INVENTORY_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        // The panel stays centred; its key hangs off the side.
        leftPos = Math.max(0, (width - WIDTH) / 2);
        addHelp(WIDTH - 7, "items/crystal-foundry.md");
        if (io == null) {
            io = new IoGridWindow(font, false, kind -> menu.sides(), id -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id));
        }
        addRenderableWidget(io.key(leftPos + KEY_X, topPos + JasmGui.sideKeyY(0), topPos));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        FRAME.draw(graphics, x, y);
        for (Slot slot : menu.slots) {
            JasmGui.slot(graphics, x + slot.x, y + slot.y);
        }
        JasmGui.inset(graphics, x + CRYSTAL_X - 3, y + CRYSTAL_Y - 3, 22, 22);
        if (menu.growing()) {
            graphics.item(crystal, x + CRYSTAL_X, y + CRYSTAL_Y);
        }
        JasmGui.bar(graphics, x + PROGRESS_X, y + PROGRESS_Y, PROGRESS_WIDTH, 5, menu.growing() ? menu.progress() : 0);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
        Component count = menu.growing()
                ? Component.translatable("screen.jasm.foundry.seed_life")
                : Component.translatable("screen.jasm.foundry.no_seed");
        boolean networkFull = ClientNetworkStatus.full(menu.containerId) != null;
        int color = networkFull ? JasmGui.BAD : menu.full() ? JasmGui.WARN : !menu.powered() && menu.growing() ? JasmGui.BAD : JasmGui.SUBTEXT;
        boolean seedLife = menu.growing();
        if (networkFull) {
            seedLife = false;
            // A stopped network matters more than a full output or a missing seed.
            count = MachineStatusText.noPower(menu.containerId, "screen.jasm.foundry.no_power", font, PROGRESS_WIDTH + 24);
        } else if (menu.full()) {
            seedLife = false;
            count = Component.translatable("screen.jasm.foundry.full");
        } else if (!menu.powered() && menu.growing()) {
            seedLife = false;
            // Only the room between the seed slot and the output grid.
            count = MachineStatusText.noPower(menu.containerId, "screen.jasm.foundry.no_power", font, PROGRESS_WIDTH + 24);
        }
        graphics.text(font, count, PROGRESS_X + (PROGRESS_WIDTH - font.width(count)) / 2, COUNT_Y, color, false);
        if (seedLife) {
            // Counts down from the full number: a new seed shows 16 / 16.
            Component left = Component.literal(Math.max(0, menu.perSeed() - menu.made()) + " / " + menu.perSeed());
            graphics.text(font, left, PROGRESS_X + (PROGRESS_WIDTH - font.width(left)) / 2, COUNT_Y + font.lineHeight, color, false);
        }
        Component power = Component.translatable("screen.jasm.workshop.power_amount", String.format("%,d", menu.energy()),
                String.format("%,d", CrystalFoundryBlockEntity.CAPACITY));
        JasmGui.labelledBar(graphics, font, power, POWER_X, CrystalFoundryMenu.ROW_Y, POWER_WIDTH, ROW_HEIGHT,
                menu.energy() / (double) CrystalFoundryBlockEntity.CAPACITY);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realX, int realY, float a) {
        // Under the I/O window nothing else lights up or shows a tooltip.
        boolean hidden = io.contains(realX, realY);
        super.extractContents(graphics, hidden ? -1000 : realX, hidden ? -1000 : realY, a);
        if (io.isOpen()) {
            graphics.nextStratum();
            io.draw(graphics, realX, realY, a);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (io.contains(event.x(), event.y())) return io.mouseClicked(event, doubleClick);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return io.mouseDragged(event, width, height) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return io.mouseReleased() | super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        return io.contains(x, y) || super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (io.isOpen() && event.isEscape()) {
            io.close();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected boolean hasClickedOutside(double x, double y, int left, int top) {
        return !io.contains(x, y) && super.hasClickedOutside(x, y, left, top);
    }
}
