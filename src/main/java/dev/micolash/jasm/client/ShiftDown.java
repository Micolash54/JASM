package dev.micolash.jasm.client;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Checks either Shift key, including while an inventory screen is open. */
public record ShiftDown() implements ConditionalItemModelProperty {
    public static final MapCodec<ShiftDown> MAP_CODEC = MapCodec.unit(new ShiftDown());

    @Override
    public boolean get(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner, int seed,
            ItemDisplayContext displayContext) {
        return Minecraft.getInstance().hasShiftDown();
    }

    @Override
    public MapCodec<ShiftDown> type() {
        return MAP_CODEC;
    }
}
