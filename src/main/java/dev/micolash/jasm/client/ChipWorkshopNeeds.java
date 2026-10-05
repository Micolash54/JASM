package dev.micolash.jasm.client;

import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.workshop.WorkshopNeed;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The critter a Workshop recipe asks for, in words. */
public final class ChipWorkshopNeeds {
    private ChipWorkshopNeeds() {}

    /** "Byteling", or "Memory Byteling" when the recipe asks for a kind. */
    public static Component needed(int need) {
        BitlingStage stage = WorkshopNeed.stage(need);
        return WorkshopNeed.kind(need)
                .<Component>map(kind -> new ItemStack(JasmItems.bitling(kind, stage)).getHoverName())
                .orElseGet(() -> Component.translatable("screen.jasm.workshop.stage." + stage.name().toLowerCase(Locale.ROOT)));
    }
}
