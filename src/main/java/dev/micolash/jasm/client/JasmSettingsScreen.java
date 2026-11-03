package dev.micolash.jasm.client;

import dev.micolash.jasm.config.JasmClientConfig;
import dev.micolash.jasm.config.Preset;
import dev.micolash.jasm.config.Presets;
import dev.micolash.jasm.config.Setting;
import dev.micolash.jasm.config.SettingsCatalog;
import dev.micolash.jasm.config.SettingsPayloads;
import dev.micolash.jasm.config.SettingsSave;
import dev.micolash.jasm.registry.JasmItems;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

/**
 * JASM's settings screen, opened from the mod list's Config button. The sections are on the left, the open section's
 * settings on the right, each with a plain-words description. The player's own settings always change; the world rules
 * change when the player owns the world or is an operator, and are shown locked to everyone else.
 *
 * <p>Nothing is written until Done (or Escape): changes wait here, and the world rules go to the server, which checks who
 * is asking and what the values are before saving them.
 */
public final class JasmSettingsScreen extends Screen {
    /** The screen that is open, so the server's answer finds it. Cleared when the screen closes. */
    private static @Nullable JasmSettingsScreen open;

    /** One section of one of the two config files. */
    record Page(boolean world, SettingsCatalog.Section section) {}

    /** What the player may do with the world rules right now. */
    private enum Rules { EDITABLE, NO_WORLD, NOT_OPERATOR }

    private static final int MOST_WIDTH = 500;
    private static final int MOST_HEIGHT = 330;

    private final Screen parent;
    private final List<Page> pages = new ArrayList<>();
    private final Rules rules;
    /** What the player has set so far, by setting. A world rule and an own setting never share a key. */
    private final Map<Setting, Object> pending = new LinkedHashMap<>();
    /** Settings whose box holds something they don't accept. Done waits until it is empty. */
    private final Set<Setting> invalid = new HashSet<>();
    private Page page;
    private boolean saving;
    private @Nullable Component problem;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int rightX;
    private int rightWidth;
    private SettingsSectionList sections;
    private SettingsRowList rows;
    private JasmButton resetSection;
    private JasmButton done;

    public JasmSettingsScreen(Screen parent) {
        super(Component.translatable("screen.jasm.settings.title"));
        this.parent = parent;
        Minecraft minecraft = Minecraft.getInstance();
        boolean loaded = SettingsCatalog.WORLD.spec().isLoaded();
        boolean mayEdit = loaded && minecraft.player != null
                && (minecraft.hasSingleplayerServer() || minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
        this.rules = !loaded ? Rules.NO_WORLD : mayEdit ? Rules.EDITABLE : Rules.NOT_OPERATOR;
        for (SettingsCatalog.Section section : SettingsCatalog.MINE.sections()) pages.add(new Page(false, section));
        for (SettingsCatalog.Section section : SettingsCatalog.WORLD.sections()) pages.add(new Page(true, section));
        this.page = pages.get(0);
    }

    /** The server's answer to a save. Only the open settings screen cares. */
    public static void saved(SettingsSave.Status status) {
        if (open != null) open.onSaved(status);
    }

    // ---- values -------------------------------------------------------------------------------------------------

    private static SettingsCatalog catalog(boolean world) {
        return world ? SettingsCatalog.WORLD : SettingsCatalog.MINE;
    }

    private boolean isWorld(Setting setting) {
        return SettingsCatalog.WORLD.find(setting.id()) == setting;
    }

    /** Whether the player can't change this setting right now. */
    boolean locked(Setting setting) {
        return saving || isWorld(setting) && rules != Rules.EDITABLE;
    }

    /** What to show: the player's pending choice, else what the file holds, else (no world loaded yet) the default. */
    Object value(Setting setting) {
        Object chosen = pending.get(setting);
        if (chosen != null) return chosen;
        return catalog(isWorld(setting)).spec().isLoaded() ? setting.current() : setting.defaultValue();
    }

    boolean changed(Setting setting) {
        return !Objects.equals(value(setting), setting.defaultValue());
    }

    void set(Setting setting, Object value) {
        pending.put(setting, value);
        problem = null;
        if (value instanceof Preset preset && isWorld(setting)) {
            // Picking a preset fills in the numbers it covers, so the other sections show them before anything is saved.
            // Going back to the one the world already had leaves the numbers as the file holds them.
            boolean sameAsBefore = preset == setting.current();
            for (Map.Entry<ModConfigSpec.ConfigValue<?>, Object> number : Presets.numbers(preset).entrySet()) {
                Setting covered = SettingsCatalog.WORLD.find(String.join(".", number.getKey().getPath()));
                if (covered == null || covered == setting) continue;
                if (sameAsBefore) {
                    pending.remove(covered);
                } else {
                    pending.put(covered, number.getValue());
                }
            }
        }
        updateButtons();
    }

    void reset(Setting setting) {
        invalid.remove(setting);
        set(setting, setting.defaultValue());
        rows.show(notes(), warnNote(), page.section().settings(), true);
    }

    /** The + and - keys of a list: one entry more (a copy of the last) or one fewer. */
    void resizeList(Setting setting, int by) {
        List<Object> list = new ArrayList<>((List<?>) value(setting));
        if (by > 0) {
            list.add(list.get(list.size() - 1));
        } else {
            list.remove(list.size() - 1);
        }
        invalid.remove(setting);
        set(setting, List.copyOf(list));
        rows.show(notes(), warnNote(), page.section().settings(), true);
    }

    void markInvalid(Setting setting, boolean bad) {
        if (bad) {
            invalid.add(setting);
        } else {
            invalid.remove(setting);
        }
        updateButtons();
    }

    /** Only digits, a point and a minus sign go into a number box. */
    static boolean numberText(String text) {
        return text.chars().allMatch(c -> c >= '0' && c <= '9' || c == '.' || c == '-');
    }

    Component restartTip() {
        return Component.translatable(minecraft.hasSingleplayerServer() ? "screen.jasm.settings.restart_tip.world" : "screen.jasm.settings.restart_tip.server");
    }

    // ---- pages --------------------------------------------------------------------------------------------------

    boolean isOpen(Page candidate) {
        return candidate.equals(page);
    }

    void openPage(Page next, boolean keepScroll) {
        page = next;
        invalid.removeIf(s -> !next.section().settings().contains(s));
        rows.show(notes(), warnNote(), next.section().settings(), keepScroll);
        updateButtons();
    }

    private List<Component> notes() {
        if (!page.world()) return List.of(Component.translatable("screen.jasm.settings.note.mine"));
        return switch (rules) {
            case NO_WORLD -> List.of(Component.translatable("screen.jasm.settings.note.no_world"));
            case NOT_OPERATOR -> List.of(Component.translatable("screen.jasm.settings.note.not_operator"));
            case EDITABLE -> List.of(Component.translatable(minecraft.hasSingleplayerServer() ? "screen.jasm.settings.note.world"
                    : "screen.jasm.settings.note.server"));
        };
    }

    /** The locked-out note stands out; the ordinary one is quiet. */
    private boolean warnNote() {
        return page.world() && rules != Rules.EDITABLE;
    }

    private ItemStack icon(String section) {
        ItemLike item = switch (section) {
            case "preset" -> JasmItems.GUIDE_BOOK.get();
            case "deck" -> JasmItems.deck(dev.micolash.jasm.deck.DeckTier.BASIC);
            case "slots" -> JasmItems.wafer(dev.micolash.jasm.wafer.WaferTier.K1);
            case "power" -> JasmItems.BATTERY.get();
            case "autocrafting" -> JasmItems.CRAFTING_SERVER.get();
            case "chips" -> JasmItems.LOGIC_CHIP.get();
            case "brain" -> JasmItems.NETWORK_BRAIN.get();
            case "wild_bitling" -> JasmItems.WILD_BITLING_SPAWN_EGG.get();
            case "bays" -> JasmItems.DEPLOYMENT_BAY.get();
            case "speed_upgrades" -> JasmItems.SPEED_UPGRADE.get();
            case "chunkloading" -> JasmItems.CHUNKLOAD_UPGRADE.get();
            case "features" -> JasmItems.WRENCH.get();
            case "screens" -> Items.PAINTING;
            case "sounds" -> Items.NOTE_BLOCK;
            default -> Items.PAPER;
        };
        return new ItemStack(item);
    }

    private static Component sectionName(String section) {
        String key = "jasm.configuration." + section;
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(section);
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        open = this;
        panelWidth = Math.min(width - 12, MOST_WIDTH);
        panelHeight = Math.min(height - 12, MOST_HEIGHT);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int sideWidth = Math.min(140, panelWidth / 3);
        int bodyTop = top + 22;
        int bodyBottom = top + panelHeight - 34;
        rightX = left + 8 + sideWidth + 6;
        rightWidth = left + panelWidth - 8 - rightX;

        sections = addRenderableWidget(new SettingsSectionList(minecraft, this, left + 8, bodyTop, sideWidth, bodyBottom - bodyTop));
        rows = addRenderableWidget(new SettingsRowList(minecraft, this, rightX, bodyTop + 18, rightWidth, bodyBottom - bodyTop - 18));

        boolean worldHeading = false;
        sections.heading(Component.translatable("screen.jasm.settings.mine"));
        for (Page candidate : pages) {
            if (candidate.world() && !worldHeading) {
                worldHeading = true;
                sections.heading(Component.translatable("screen.jasm.settings.world"));
            }
            sections.section(candidate, icon(candidate.section().name()), sectionName(candidate.section().name()));
        }

        int buttonTop = top + panelHeight - 27;
        done = addRenderableWidget(JasmButton.text(CommonComponents.GUI_DONE, b -> finish(true), left + panelWidth - 8 - 84, buttonTop, 84, 18));
        resetSection = addRenderableWidget(JasmButton.text(Component.translatable("screen.jasm.settings.reset_section"), b -> resetSection(),
                left + panelWidth - 8 - 84 - 4 - 104, buttonTop, 104, 18));
        resetSection.setTooltip(Tooltip.create(Component.translatable("screen.jasm.settings.reset_section_tip")));
        openPage(page, false);
    }

    private void resetSection() {
        for (Setting setting : page.section().settings()) {
            invalid.remove(setting);
            set(setting, setting.defaultValue());
        }
        rows.show(notes(), warnNote(), page.section().settings(), true);
    }

    private void updateButtons() {
        if (done == null) return;
        done.active = !saving && invalid.isEmpty();
        boolean anyChanged = false;
        for (Setting setting : page.section().settings()) anyChanged |= changed(setting);
        resetSection.active = !saving && anyChanged && (!page.world() || rules == Rules.EDITABLE);
        if (rows != null) rows.refreshStates();
    }

    // ---- saving -------------------------------------------------------------------------------------------------

    /** Writes the player's own settings and sends the world rules to the server. With {@code wait}, stays open until the server answers. */
    private void finish(boolean wait) {
        boolean mineChanged = false;
        Map<String, String> world = new LinkedHashMap<>();
        for (Map.Entry<Setting, Object> entry : pending.entrySet()) {
            Setting setting = entry.getKey();
            if (invalid.contains(setting)) continue;
            if (isWorld(setting)) {
                if (rules == Rules.EDITABLE) world.put(setting.id(), setting.format(entry.getValue()));
            } else if (JasmClientConfig.SPEC.isLoaded() && !Objects.equals(setting.current(), entry.getValue())) {
                setting.write(entry.getValue());
                mineChanged = true;
            }
        }
        if (mineChanged) JasmClientConfig.SPEC.save();
        if (world.isEmpty()) {
            minecraft.setScreen(parent);
            return;
        }
        ClientPacketDistributor.sendToServer(new SettingsPayloads.Save(world));
        if (!wait) {
            minecraft.setScreen(parent);
            return;
        }
        saving = true;
        problem = null;
        openPage(page, true);
    }

    private void onSaved(SettingsSave.Status status) {
        saving = false;
        switch (status) {
            case SAVED -> {
                minecraft.setScreen(parent);
                return;
            }
            case NOT_ALLOWED -> problem = Component.translatable("screen.jasm.settings.not_allowed");
            case REFUSED -> problem = Component.translatable("screen.jasm.settings.refused");
        }
        openPage(page, true);
    }

    @Override
    public void onClose() {
        if (saving) {
            minecraft.setScreen(parent);
        } else {
            finish(false);
        }
    }

    @Override
    public void removed() {
        if (open == this) open = null;
        super.removed();
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        JasmGui.panel(graphics, left, top, panelWidth, panelHeight);
        graphics.text(font, title, left + 10, top + 8, JasmGui.TEXT, false);
        Component name = sectionName(page.section().name());
        graphics.text(font, name, rightX + 4, top + 22 + 5, JasmGui.ACCENT, false);
        Component kind = Component.translatable(page.world() ? "screen.jasm.settings.world" : "screen.jasm.settings.mine");
        graphics.text(font, kind, rightX + rightWidth - 4 - font.width(kind), top + 22 + 5, JasmGui.MUTED, false);
        Component line = saving ? Component.translatable("screen.jasm.settings.saving") : problem != null ? problem
                : !invalid.isEmpty() ? Component.translatable("screen.jasm.settings.fix_red") : null;
        if (line != null) {
            int room = resetSection.getX() - 6 - (left + 10);
            graphics.text(font, font.plainSubstrByWidth(line.getString(), room), left + 10, top + panelHeight - 22,
                    saving ? JasmGui.SUBTEXT : JasmGui.BAD, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
