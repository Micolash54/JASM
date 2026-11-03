package dev.micolash.jasm.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The left-hand side of the settings screen: the sections, under a heading for the player's own settings and one for the world rules. */
final class SettingsSectionList extends SettingsPane<SettingsSectionList.Row> {
    private static final int HEADING_HEIGHT = 14;
    private static final int ITEM_HEIGHT = 18;

    private final Font font;
    private final JasmSettingsScreen screen;

    SettingsSectionList(Minecraft minecraft, JasmSettingsScreen screen, int x, int y, int width, int height) {
        super(minecraft, x, y, width, height, ITEM_HEIGHT);
        this.font = minecraft.font;
        this.screen = screen;
    }

    void heading(Component text) {
        addEntry(new Heading(text), HEADING_HEIGHT);
    }

    void section(JasmSettingsScreen.Page page, ItemStack icon, Component name) {
        addEntry(new Item(page, icon, name), ITEM_HEIGHT);
    }

    abstract static class Row extends net.minecraft.client.gui.components.ContainerObjectSelectionList.Entry<Row> {
        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private final class Heading extends Row {
        private final Component text;

        Heading(Component text) {
            this.text = text;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            graphics.fill(getX(), getY() + 1, getX() + getWidth(), getY() + getHeight() - 1, JasmGui.SELECTED);
            graphics.text(font, text, getX() + 4, getY() + (getHeight() - 8) / 2, JasmGui.SUBTEXT, false);
        }
    }

    private final class Item extends Row {
        private final JasmSettingsScreen.Page page;
        private final ItemStack icon;
        private final Component name;

        Item(JasmSettingsScreen.Page page, ItemStack icon, Component name) {
            this.page = page;
            this.icon = icon;
            this.name = name;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            screen.openPage(page, false);
            return true;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            boolean open = screen.isOpen(page);
            if (open) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), JasmGui.SELECTED);
                graphics.fill(getX(), getY(), getX() + 2, getY() + getHeight(), JasmGui.ACCENT);
            } else if (hovered) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), JasmGui.HOVER);
            }
            graphics.item(icon, getX() + 5, getY() + 1);
            String text = font.plainSubstrByWidth(name.getString(), getWidth() - 26);
            graphics.text(font, text, getX() + 25, getY() + (getHeight() - 8) / 2, open ? JasmGui.TEXT : JasmGui.SUBTEXT, false);
        }
    }
}
