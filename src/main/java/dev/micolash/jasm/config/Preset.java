package dev.micolash.jasm.config;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/** A set of numbers picked in one go. Standard is every number at its default. */
public enum Preset implements TranslatableEnum {
    RELAXED,
    STANDARD,
    HARDCORE;

    @Override
    public Component getTranslatedName() {
        return Component.translatable("jasm.configuration.preset." + name().toLowerCase(Locale.ROOT));
    }
}
