package dev.micolash.jasm.client;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.core.MaterialKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;
import org.jspecify.annotations.Nullable;

/** Icons and names for other mods' materials. JEI's own icon when JEI knows it, a tinted canister when not. */
public final class MaterialIcons {
    private static final Identifier CANISTER = Jasm.id("material_canister");

    public interface Source {
        boolean draw(GuiGraphicsExtractor graphics, MaterialKey key, int x, int y);
    }

    // set while JEI's runtime is up, cleared when it goes
    private static @Nullable Source source;

    private MaterialIcons() {}

    public static void setSource(@Nullable Source source) {
        MaterialIcons.source = source;
    }

    public static void draw(GuiGraphicsExtractor graphics, MaterialKey key, int x, int y) {
        Source icons = source;
        if (icons != null && icons.draw(graphics, key, x, y)) return;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CANISTER, x, y, 16, 16, colour(key));
    }

    // same colour for the same material every time, light enough to read on the dark panel
    static int colour(MaterialKey key) {
        float hue = (key.id().toString().hashCode() & 0xFFFF) / 65536F;
        return Mth.hsvToArgb(hue, 0.45F, 0.9F, 255);
    }

    public static Component name(MaterialKey key) {
        String translation = key.translationKey();
        return I18n.exists(translation) ? Component.translatable(translation) : Component.literal(GridEntries.readableName(key.id().getPath()));
    }

    static String modName(MaterialKey key) {
        String namespace = key.id().getNamespace();
        return ModList.get().getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace);
    }
}
