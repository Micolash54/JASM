package dev.micolash.jasm.deck;

import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.WaferHolderItem;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/** The handheld reader. Carries wafers and a battery; right-click opens it. */
public class DeckItem extends Item implements WaferHolderItem {
    private final DeckTier tier;

    public DeckItem(Item.Properties properties, DeckTier tier) {
        super(properties.stacksTo(1));
        this.tier = tier;
    }

    public DeckTier tier() {
        return tier;
    }

    /** Decks must never nest inside bundles, shulker boxes, or other container items. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    /** The battery and wafers change while the Deck is in use; only a different item should make the hand dip. */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }

    public static DeckWafers wafers(ItemStack deck) {
        return deck.getOrDefault(JasmComponents.DECK_WAFERS.get(), DeckWafers.EMPTY);
    }

    public static int energy(ItemStack deck) {
        return deck.getOrDefault(JasmComponents.ENERGY.get(), 0);
    }

    /** Opens the Deck. Its wafers are activated first, so copies elsewhere stop working. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
            ItemStack deck = player.getInventory().getItem(slot);
            DeckStorage.activate(WaferStore.get(serverPlayer.level().getServer()), deck, serverPlayer);
            serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, p) -> new DeckMenu(id, inventory, slot), deck.getHoverName()),
                    buf -> buf.writeVarInt(slot));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void forEachWafer(ItemStack holder, Consumer<ItemStack> action) {
        wafers(holder).forEach(action);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        builder.accept(Component.translatable("tooltip.jasm.deck.wafers", wafers(stack).count(), tier.slots())
                .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("tooltip.jasm.deck.energy", String.format("%,d", energy(stack)),
                String.format("%,d", tier.battery())).withStyle(ChatFormatting.GRAY));
    }
}
