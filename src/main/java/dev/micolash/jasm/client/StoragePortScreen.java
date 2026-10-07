package dev.micolash.jasm.client;

import dev.micolash.jasm.pool.StorageAccess;
import dev.micolash.jasm.pool.StorageNetwork;
import dev.micolash.jasm.pool.StoragePortMenu;
import dev.micolash.jasm.pool.StorageSettings;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** A Storage Port's filter, what the network may do with the block in front of it, and its priority. */
public final class StoragePortScreen extends JasmScreen<StoragePortMenu> {
    private ItemFilterEditor editor;
    private JasmButton access;
    private JasmField priority;
    private JasmFrame frame;

    public StoragePortScreen(StoragePortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, StoragePortMenu.WIDTH, menu.height());
        inventoryLabelX = (StoragePortMenu.WIDTH - 162) / 2;
        inventoryLabelY = StoragePortMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        frame = JasmFrame.rounded(new int[]{0, 0, StoragePortMenu.WIDTH, imageHeight});
        addHelp(StoragePortMenu.WIDTH - 7, "items/storage-port.md");
        editor = new ItemFilterEditor(font, 2, false, menu::getCarried, StoragePortMenu.WIDTH - 16);
        editor.setSave(filter -> send(new StorageSettings(filter, menu.settings().access(), menu.settings().priority())));
        editor.open(menu.settings().filter(), Component.translatable("screen.jasm.storage.filter"), Component.empty(),
                ItemStack.EMPTY, leftPos + 8, topPos + StoragePortMenu.FILTER_TOP, width, height);
        // Wide enough for the longest of the three labels, so none is cut off.
        int accessWidth = 0;
        for (StorageAccess mode : StorageAccess.values()) accessWidth = Math.max(accessWidth, font.width(accessLabel(mode)) + 12);
        access = addRenderableWidget(JasmButton.text(accessLabel(menu.settings().access()), b -> {
            send(new StorageSettings(menu.settings().filter(), menu.settings().access().next(), menu.settings().priority()));
            access.setMessage(accessLabel(menu.settings().access()));
        }, leftPos + 8, topPos + StoragePortMenu.CONTROLS_TOP, accessWidth, 14));
        access.setTooltip(Tooltip.create(Component.translatable("screen.jasm.storage.access_hint")));
        priority = addRenderableWidget(new JasmField(font, leftPos + 8 + accessWidth + 4, topPos + StoragePortMenu.CONTROLS_TOP, 40, 14,
                Component.translatable("screen.jasm.storage.priority")));
        priority.setFilter(text -> text.matches("-?\\d{0,3}"));
        priority.setValue(Integer.toString(menu.settings().priority()));
        priority.setTooltip(Tooltip.create(Component.translatable("screen.jasm.storage.priority_hint")));
        priority.setResponder(text -> {
            try {
                send(new StorageSettings(menu.settings().filter(), menu.settings().access(), Integer.parseInt(text)));
            } catch (NumberFormatException ignored) {
                // An empty field or a lone minus sign sends nothing.
            }
        });
    }

    private static Component accessLabel(StorageAccess access) {
        return Component.translatable("screen.jasm.storage.access_" + access.getSerializedName());
    }

    private void send(StorageSettings settings) {
        menu.configure(settings);
        ClientPacketDistributor.sendToServer(new StorageNetwork.Configure(menu.containerId, settings));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mx, int my, float a) {
        super.extractBackground(graphics, mx, my, a);
        frame.draw(graphics, leftPos, topPos);
        for (var slot : menu.slots) JasmGui.slot(graphics, leftPos + slot.x, topPos + slot.y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mx, int my) {
        graphics.text(font, title, titleLabelX, titleLabelY, JasmGui.TEXT, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, JasmGui.SUBTEXT, false);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int realX, int realY, float a) {
        super.extractContents(graphics, realX, realY, a);
        graphics.nextStratum();
        editor.draw(graphics, realX, realY, a, width, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (editor.contains(event.x(), event.y())) return editor.mouseClicked(event, doubleClick);
        editor.unfocus();
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return editor.mouseDragged(event, width, height) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return editor.mouseReleased(event) | super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        return editor.contains(x, y) ? editor.mouseScrolled(sy) : super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return editor.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return editor.charTyped(event) || super.charTyped(event);
    }

    /** The filter's drop spots, for JEI. */
    public List<Rect2i> filterSlots() { return List.of(editor.slotArea()); }
    public void setFilterItem(int index, Item item) { if (index == 0) editor.setItem(new ItemStack(item), false); }
    public void setFilterMaterial(int index, Identifier id) { if (index == 0) editor.setMaterial(id); }
}
