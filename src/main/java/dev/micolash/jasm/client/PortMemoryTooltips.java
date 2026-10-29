package dev.micolash.jasm.client;

import com.mojang.datafixers.util.Either;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.PortMemory;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.jspecify.annotations.Nullable;

/** A mined Access Port says how many machines it remembers; with Shift, their icons follow on one line. */
@EventBusSubscriber(modid = Jasm.MODID, value = Dist.CLIENT)
public final class PortMemoryTooltips {
    private PortMemoryTooltips() {}

    /** The machines' icons, side by side. */
    record Icons(List<ItemStack> items) implements TooltipComponent {}

    private static final int SLOT = 18;

    private static @Nullable PortMemory memory(ItemStack stack) {
        PortMemory memory = stack.get(JasmComponents.PORT_MEMORY.get());
        return memory == null || memory.machines().isEmpty() ? null : memory;
    }

    private static Component savedLine(PortMemory memory) {
        int n = memory.machines().size();
        return (n == 1 ? Component.translatable("tooltip.jasm.port.machine_saved")
                : Component.translatable("tooltip.jasm.port.machines_saved", n)).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        PortMemory memory = memory(event.getItemStack());
        if (memory == null) return;
        event.getToolTip().add(savedLine(memory));
        if (!memory.icons().isEmpty() && !Minecraft.getInstance().hasShiftDown()) {
            Component key = Component.literal("[Shift]").withStyle(ChatFormatting.GRAY);
            event.getToolTip().add(Component.translatable("tooltip.jasm.port.shift", key).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** The icons go right under the line that counts them. */
    @SubscribeEvent
    static void onGather(RenderTooltipEvent.GatherComponents event) {
        PortMemory memory = memory(event.getItemStack());
        if (memory == null || memory.icons().isEmpty() || !Minecraft.getInstance().hasShiftDown()) return;
        String line = savedLine(memory).getString();
        List<Either<FormattedText, TooltipComponent>> elements = event.getTooltipElements();
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).left().map(text -> text.getString().equals(line)).orElse(false)) {
                elements.add(i + 1, Either.right(new Icons(memory.icons())));
                return;
            }
        }
    }

    @SubscribeEvent
    static void onRegister(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(Icons.class, icons -> new ClientTooltipComponent() {
            @Override
            public int getHeight(Font font) {
                return SLOT;
            }

            @Override
            public int getWidth(Font font) {
                return icons.items().size() * SLOT;
            }

            @Override
            public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
                for (int i = 0; i < icons.items().size(); i++) {
                    graphics.item(icons.items().get(i), x + i * SLOT, y);
                }
            }
        });
    }
}
