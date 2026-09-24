package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckMenu;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Jasm.MODID);

    public static final Supplier<MenuType<DeckMenu>> DECK = MENUS.register("deck", () -> IMenuTypeExtension.create(DeckMenu::client));

    private JasmMenus() {}
}
