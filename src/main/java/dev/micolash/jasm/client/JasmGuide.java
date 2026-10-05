package dev.micolash.jasm.client;

import dev.micolash.jasm.guide.GuideBookItem;
import guideme.Guide;
import guideme.compiler.tags.RecipeTypeMappingSupplier;

/** The in-game guide. Its pages are the markdown files under assets/jasm/guide. */
public final class JasmGuide {
    private JasmGuide() {}

    static void build() {
        Guide.builder(GuideBookItem.GUIDE_ID)
                .folder("guide")
                .extension(RecipeTypeMappingSupplier.EXTENSION_POINT, new GuideRecipes())
                .build();
    }
}
