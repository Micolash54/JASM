package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.archive.ArchiveMenu;
import dev.micolash.jasm.battery.CreativeBatteryMenu;
import dev.micolash.jasm.deck.DeckMenu;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Jasm.MODID);

    public static final Supplier<MenuType<DeckMenu>> DECK = MENUS.register("deck", () -> IMenuTypeExtension.create(DeckMenu::client));
    public static final Supplier<MenuType<ArchiveMenu>> ARCHIVE = MENUS.register("archive", () -> IMenuTypeExtension.create(ArchiveMenu::client));
    public static final Supplier<MenuType<CreativeBatteryMenu>> CREATIVE_BATTERY = MENUS.register("creative_battery",
            () -> new MenuType<>(CreativeBatteryMenu::new, FeatureFlags.VANILLA_SET));

    private JasmMenus() {}
}
