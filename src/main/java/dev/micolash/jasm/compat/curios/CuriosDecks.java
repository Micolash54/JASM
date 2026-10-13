package dev.micolash.jasm.compat.curios;

import dev.micolash.jasm.deck.DeckItem;
import dev.micolash.jasm.deck.WornDeckKeeper;
import dev.micolash.jasm.deck.WornDecks;
import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jspecify.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * The Deck slot in Curios. The slot itself, which items fit and who gets it are data files
 * ({@code data/jasm/curios}, {@code data/curios/tags/item}); this class lets the rest of JASM see the worn Deck. It is
 * only loaded when Curios is installed.
 */
public final class CuriosDecks implements WornDecks.Slots {
    /** The slot's name in Curios. */
    static final String SLOT = "jasm_deck";

    private CuriosDecks() {}

    public static void init(IEventBus modBus) {
        WornDecks.install(new CuriosDecks());
        modBus.addListener(CuriosDecks::registerCapabilities);
    }

    private static @Nullable IDynamicStackHandler stacks(Player player) {
        return CuriosApi.getCuriosInventory(player).flatMap(inventory -> inventory.getStacksHandler(SLOT))
                .map(ICurioStacksHandler::getStacks).orElse(null);
    }

    @Override
    public int size(Player player) {
        IDynamicStackHandler stacks = stacks(player);
        return stacks == null ? 0 : stacks.getSlots();
    }

    @Override
    public ItemStack get(Player player, int index) {
        IDynamicStackHandler stacks = stacks(player);
        return stacks == null ? ItemStack.EMPTY : stacks.getStackInSlot(index);
    }

    @Override
    public void set(Player player, int index, ItemStack stack) {
        IDynamicStackHandler stacks = stacks(player);
        if (stacks != null) {
            stacks.setStackInSlot(index, stack);
        }
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        for (DeckItem deck : JasmItems.allDecks()) {
            event.registerItem(CuriosCapability.ITEM, (stack, context) -> new WornDeck(stack), deck);
        }
    }

    /** A Deck in a Curios slot. Using it still opens it; it is only put on by placing it in the slot. */
    private record WornDeck(ItemStack stack) implements ICurio {
        @Override
        public ItemStack getStack() {
            return stack;
        }

        @Override
        public void curioTick(SlotContext context) {
            if (context.entity() instanceof Player player && player.level() instanceof ServerLevel level && SLOT.equals(context.identifier())) {
                WornDeckKeeper.wornTick(player, stack);
                DeckItem.forgetStaleLink(stack, level);
            }
        }

        /** Curios' old copy of a Deck that was already given back can't be taken off; it is taken out instead. */
        @Override
        public boolean canUnequip(SlotContext context) {
            return !(context.entity() instanceof Player player && WornDeckKeeper.isOldCopy(player, stack));
        }
    }
}
