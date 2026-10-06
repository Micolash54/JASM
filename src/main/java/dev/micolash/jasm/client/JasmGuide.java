package dev.micolash.jasm.client;

import dev.micolash.jasm.guide.GuideBookItem;
import dev.micolash.jasm.Jasm;
import guideme.Guide;
import guideme.GuidesCommon;
import guideme.PageAnchor;
import guideme.compiler.tags.RecipeTypeMappingSupplier;
import net.minecraft.client.Minecraft;

/** The in-game guide. Its pages are the markdown files under assets/jasm/guide. */
public final class JasmGuide {
    private JasmGuide() {}

    static void build() {
        Guide.builder(GuideBookItem.GUIDE_ID)
                .folder("guide")
                // GuideME's own recipe boxes would come first, so the guide draws every recipe kind itself.
                .disableDefaultExtensions(RecipeTypeMappingSupplier.EXTENSION_POINT)
                .extension(RecipeTypeMappingSupplier.EXTENSION_POINT, new GuideRecipes())
                .build();
    }

    /** Opens a page, like {@code items/chip-workshop.md}. Closing the guide goes back to the screen it was opened from. */
    static void open(String page) {
        var player = Minecraft.getInstance().player;
        if (player != null) GuidesCommon.openGuide(player, GuideBookItem.GUIDE_ID, PageAnchor.page(Jasm.id(page)));
    }
}
