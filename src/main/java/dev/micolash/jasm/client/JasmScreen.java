package dev.micolash.jasm.client;

import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.network.LinkWindowCover;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/** What every JASM screen shares: the slot under the mouse lights up lavender instead of the game's flat white. */
public abstract class JasmScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    /** The size of the "?" key, and the room it takes at the end of the title row with the gap before it. */
    public static final int HELP_SIZE = 13;
    public static final int HELP_HEIGHT = 15;
    public static final int HELP_ROOM = HELP_SIZE + 3;

    private @Nullable Slot hidden;

    protected JasmScreen(T menu, Inventory inventory, Component title, int width, int height) {
        super(menu, inventory, title, width, height);
    }

    /**
     * Adds the "?" key at the end of the title row, its right edge {@code right} pixels from the screen's left. It opens
     * {@code page} of the guide.
     */
    protected void addHelp(int right, String page) {
        JasmButton help = JasmButton.text(Component.literal("?"), b -> JasmGuide.open(page), leftPos + right - HELP_SIZE,
                topPos + titleLabelY - 3, HELP_SIZE, HELP_HEIGHT);
        help.setTooltip(Tooltip.create(Component.translatable("screen.jasm.help")));
        addRenderableWidget(help);
    }

    /** Where the Deck Link window lies, for a screen that has one; null for the rest. */
    protected @Nullable LinkWindowCover linkCover() {
        return null;
    }

    /** A Deck shift-clicked in from the inventory only goes to the link slots while their window is open. */
    @Override
    protected void slotClicked(@Nullable Slot slot, int slotId, int buttonNum, ContainerInput input) {
        LinkWindowCover cover = linkCover();
        if (input == ContainerInput.QUICK_MOVE && slot != null && cover != null && !cover.open()
                && slot.container instanceof Inventory && DeckItem.isDeck(slot.getItem())) {
            return;
        }
        super.slotClicked(slot, slotId, buttonNum, input);
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
