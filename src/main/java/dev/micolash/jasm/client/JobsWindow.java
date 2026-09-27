package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftPayloads;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.deck.DeckMenu;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * A Crafting Deck's job list, laid over the Deck's main panel: every job it has running, how far along, and anything
 * holding it up. Clicking a job opens its Crafting Server's screen.
 */
final class JobsWindow {
    private static final int HEADER = 22;
    private static final int ROW = 22;
    private static final int BAR_WIDTH = 40;
    private static final JasmButton.Icon CLOSE = new JasmButton.Icon(Jasm.id("icon/close"), 5, 5);

    private final DeckMenu menu;
    private final Font font;
    private final Button close;
    private boolean open;
    private int scroll;
    private int x;
    private int y;
    private int width;
    private int height;

    JobsWindow(DeckMenu menu, Font font) {
        this.menu = menu;
        this.font = font;
        close = JasmButton.icon(() -> CLOSE, Component.translatable("screen.jasm.deck.settings.close"), b -> open = false, 0, 0, 11, 11);
    }

    boolean isOpen() {
        return open;
    }

    void open(int left, int top, int w, int h) {
        open = true;
        scroll = 0;
        x = left;
        y = top;
        width = w;
        height = h;
    }

    void close() {
        open = false;
    }

    boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private int rows() {
        return (height - HEADER - 6) / ROW;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < x + 6 || mouseX >= x + width - 6 || mouseY < y + HEADER + 2) {
            return -1;
        }
        int row = (int) ((mouseY - y - HEADER - 2) / ROW);
        int index = scroll + row;
        return row < rows() && index < menu.view().jobs().size() ? index : -1;
    }

    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!open) {
            return;
        }
        List<CraftPayloads.JobView> jobs = menu.view().jobs();
        scroll = Math.clamp(scroll, 0, Math.max(0, jobs.size() - rows()));
        JasmGui.panel(graphics, x, y, width, height);
        graphics.text(font, Component.translatable("screen.jasm.jobs.title", jobs.size()), x + 8, y + 7, JasmGui.TEXT, false);
        graphics.fill(x + 4, y + HEADER - 4, x + width - 4, y + HEADER - 3, JasmGui.SELECTED);
        close.setPosition(x + width - 16, y + 5);
        close.extractRenderState(graphics, mouseX, mouseY, a);
        if (jobs.isEmpty()) {
            graphics.text(font, Component.translatable("screen.jasm.jobs.none"), x + 8, y + HEADER + 4, JasmGui.MUTED, false);
            return;
        }
        int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < rows() && scroll + row < jobs.size(); row++) {
            CraftPayloads.JobView job = jobs.get(scroll + row);
            int ry = y + HEADER + 2 + row * ROW;
            if (scroll + row == hovered) {
                graphics.fill(x + 6, ry, x + width - 6, ry + ROW - 2, JasmGui.HOVER);
            }
            ItemStack shown = job.target().toStack(1);
            graphics.item(shown, x + 8, ry + 2);
            String what = GridEntries.abbreviate(job.amount()) + " × " + shown.getHoverName().getString();
            graphics.text(font, trim(what, width - 36 - BAR_WIDTH - 6), x + 28, ry + 2, JasmGui.TEXT, false);
            Component state = state(job);
            graphics.text(font, trim(state.getString(), width - 36), x + 28, ry + 11, job.pause() == 0 ? JasmGui.MUTED : JasmGui.BAD, false);
            if (job.phase() == 0) {
                JasmGui.bar(graphics, x + width - 10 - BAR_WIDTH, ry + 4, BAR_WIDTH, 5, job.progress() / 1000.0);
            }
        }
        if (hovered >= 0) {
            graphics.setTooltipForNextFrame(font, Component.translatable("screen.jasm.jobs.open"), mouseX, mouseY);
        }
    }

    /** What the job is doing, or what holds it up. */
    static Component state(CraftPayloads.JobView job) {
        return switch (job.pause()) {
            case 1 -> Component.translatable("screen.jasm.server.pause.no_power");
            case 2 -> Component.translatable("screen.jasm.server.pause.no_card");
            case 3 -> Component.translatable("screen.jasm.server.pause.waiting_player");
            case 4 -> Component.translatable("screen.jasm.server.pause.waiting_space");
            case 5 -> Component.translatable("screen.jasm.server.pause.no_network");
            case 6 -> Component.translatable("screen.jasm.server.pause.machine_busy");
            case 7 -> Component.translatable("screen.jasm.server.pause.no_machine");
            default -> job.phase() == 0 && job.waiting().isPresent() ? job.waiting().get() : switch (job.phase()) {
                case 0 -> Component.translatable("screen.jasm.server.crafting", Math.round(job.progress() / 10F));
                case 1 -> Component.translatable("screen.jasm.server.cancelling");
                default -> Component.translatable("screen.jasm.server.returning");
            };
        };
    }

    private String trim(String text, int room) {
        return font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("...")) + "...";
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (close.mouseClicked(event, doubleClick)) {
            return true;
        }
        int index = rowAt(event.x(), event.y());
        if (index >= 0) {
            ClientPacketDistributor.sendToServer(new CraftPayloads.OpenServer(menu.containerId, menu.view().jobs().get(index).server()));
        }
        return true;
    }

    boolean mouseScrolled(double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            open = false;
            return true;
        }
        return false;
    }
}
