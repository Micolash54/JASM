package dev.micolash.jasm.autocraft;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

/** A Processor or a Storage Module: goes into a Crafting Server, and says what it adds there. */
public class ServerPartItem extends Item {
    private final @Nullable ProcessorTier processor;
    private final @Nullable MemoryTier memory;

    private ServerPartItem(Item.Properties properties, @Nullable ProcessorTier processor, @Nullable MemoryTier memory) {
        super(properties);
        this.processor = processor;
        this.memory = memory;
    }

    public static ServerPartItem processor(Item.Properties properties, ProcessorTier tier) {
        return new ServerPartItem(properties, tier, null);
    }

    public static ServerPartItem memory(Item.Properties properties, MemoryTier tier) {
        return new ServerPartItem(properties, null, tier);
    }

    public @Nullable ProcessorTier processor() {
        return processor;
    }

    public @Nullable MemoryTier memory() {
        return memory;
    }

    public static @Nullable ProcessorTier processorOf(ItemStack stack) {
        return stack.getItem() instanceof ServerPartItem part ? part.processor : null;
    }

    public static @Nullable MemoryTier memoryOf(ItemStack stack) {
        return stack.getItem() instanceof ServerPartItem part ? part.memory : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        if (processor != null) {
            builder.accept(Component.translatable("tooltip.jasm.processor.crafts", processor.crafts()).withStyle(ChatFormatting.GRAY));
            builder.accept(Component.translatable("tooltip.jasm.processor.drain", processor.drainPerTick()).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (memory != null) {
            builder.accept(Component.translatable("tooltip.jasm.memory.capacity", String.format("%,d", memory.capacity()))
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
