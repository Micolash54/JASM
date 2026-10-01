package dev.micolash.jasm.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/** What every JASM screen shares: the slot under the mouse lights up lavender instead of the game's flat white. */
public abstract class JasmScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    private @Nullable Slot hidden;

    protected JasmScreen(T menu, Inventory inventory, Component title, int width, int height) {
        super(menu, inventory, title, width, height);
    }

    @Override
    protected void extractSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Slot hovered = hoveredSlot;
        boolean lit = hovered != null && hovered.isHighlightable();
        if (lit) JasmGui.slotHover(graphics, hovered.x, hovered.y);
        super.extractSlots(graphics, mouseX, mouseY);
        if (lit) {
            JasmGui.slotHoverFrame(graphics, hovered.x, hovered.y);
            // Hidden until the frame is drawn, so the game's own white highlight is skipped; tooltips still find it.
            hidden = hovered;
            hoveredSlot = null;
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (hidden != null) {
            hoveredSlot = hidden;
            hidden = null;
        }
    }
}
