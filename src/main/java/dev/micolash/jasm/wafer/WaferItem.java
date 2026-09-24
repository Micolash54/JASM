package dev.micolash.jasm.wafer;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferStore;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

public class WaferItem extends Item {
    private final WaferTier tier;

    public WaferItem(Item.Properties properties, WaferTier tier) {
        super(properties.stacksTo(1));
        this.tier = tier;
    }

    public WaferTier tier() {
        return tier;
    }

    /** Wafers must never nest inside bundles, shulker boxes, or other container items. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    /** Passive duplicate / recovered-original check for wafers carried by players. */
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
        if (!(owner instanceof Player player)) {
            return;
        }
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        if (identity == null) {
            return;
        }
        int interval = JasmConfig.WAFER_PASSIVE_CHECK_INTERVAL.getAsInt();
        if (Math.floorMod(level.getGameTime() + identity.id().hashCode(), interval) != 0) {
            return;
        }
        WaferValidator.validate(WaferStore.get(level.getServer()), stack, WaferValidator.Mode.PASSIVE, player);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        builder.accept(Component.translatable("tooltip.jasm.wafer.capacity", String.format("%,d", tier.capacity()))
                .withStyle(ChatFormatting.GRAY));
        WaferIdentity identity = stack.get(JasmComponents.WAFER_IDENTITY.get());
        if (identity == null) {
            builder.accept(Component.translatable("tooltip.jasm.wafer.blank").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            builder.accept(Component.translatable("tooltip.jasm.wafer.serial", String.format("%,d", identity.serial()))
                    .withStyle(ChatFormatting.DARK_GRAY));
            if (flag.isAdvanced()) {
                builder.accept(Component.literal(identity.id().toString()).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }
}
