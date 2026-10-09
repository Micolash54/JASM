package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.acceptor.PowerAcceptorMenu;
import dev.micolash.jasm.archive.ArchiveMenu;
import dev.micolash.jasm.autocraft.AccessPortMenu;
import dev.micolash.jasm.autocraft.CraftingServerMenu;
import dev.micolash.jasm.autocraft.EncodingTerminalMenu;
import dev.micolash.jasm.autocraft.RecipeRackMenu;
import dev.micolash.jasm.battery.CreativeBatteryMenu;
import dev.micolash.jasm.bay.BayKind;
import dev.micolash.jasm.bay.BayMenu;
import dev.micolash.jasm.brain.NetworkBrainMenu;
import dev.micolash.jasm.crystal.CrystalFoundryMenu;
import dev.micolash.jasm.deck.DeckMenu;
import dev.micolash.jasm.generator.CombustionGeneratorMenu;
import dev.micolash.jasm.pool.StoragePortMenu;
import dev.micolash.jasm.station.BitlingStationMenu;
import dev.micolash.jasm.transfer.TransferPortMenu;
import dev.micolash.jasm.workshop.ChipWorkshopMenu;
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
    public static final Supplier<MenuType<PowerAcceptorMenu>> POWER_ACCEPTOR = MENUS.register("power_acceptor",
            () -> new MenuType<>(PowerAcceptorMenu::new, FeatureFlags.VANILLA_SET));
    public static final Supplier<MenuType<CombustionGeneratorMenu>> COMBUSTION_GENERATOR = MENUS.register("combustion_generator",
            () -> new MenuType<>(CombustionGeneratorMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<CrystalFoundryMenu>> CRYSTAL_FOUNDRY = MENUS.register("crystal_foundry",
            () -> new MenuType<>(CrystalFoundryMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<ChipWorkshopMenu>> CHIP_WORKSHOP = MENUS.register("chip_workshop",
            () -> new MenuType<>(ChipWorkshopMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<BitlingStationMenu>> BITLING_STATION = MENUS.register("bitling_station",
            () -> new MenuType<>(BitlingStationMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<NetworkBrainMenu>> NETWORK_BRAIN = MENUS.register("network_brain",
            () -> new MenuType<>(NetworkBrainMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<EncodingTerminalMenu>> ENCODING_TERMINAL = MENUS.register("encoding_terminal",
            () -> new MenuType<>(EncodingTerminalMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<RecipeRackMenu>> RECIPE_RACK = MENUS.register("recipe_rack",
            () -> new MenuType<>(RecipeRackMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<CraftingServerMenu>> CRAFTING_SERVER = MENUS.register("crafting_server",
            () -> new MenuType<>(CraftingServerMenu::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<AccessPortMenu>> ACCESS_PORT = MENUS.register("access_port",
            () -> IMenuTypeExtension.create(AccessPortMenu::client));

    public static final Supplier<MenuType<TransferPortMenu>> TRANSFER_PORT = MENUS.register("transfer_port",
            () -> IMenuTypeExtension.create(TransferPortMenu::client));

    public static final Supplier<MenuType<StoragePortMenu>> STORAGE_PORT = MENUS.register("storage_port",
            () -> IMenuTypeExtension.create(StoragePortMenu::client));

    public static final Supplier<MenuType<BayMenu>> DEPLOYMENT_BAY = MENUS.register("deployment_bay",
            () -> IMenuTypeExtension.create((id, inventory, buf) -> BayMenu.client(BayKind.DEPLOYMENT, id, inventory, buf)));

    public static final Supplier<MenuType<BayMenu>> DEMOLITION_BAY = MENUS.register("demolition_bay",
            () -> IMenuTypeExtension.create((id, inventory, buf) -> BayMenu.client(BayKind.DEMOLITION, id, inventory, buf)));

    private JasmMenus() {}
}
