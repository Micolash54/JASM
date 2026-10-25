package dev.micolash.jasm.autocraft;

import dev.micolash.jasm.core.GridKey;
import dev.micolash.jasm.core.ModNameLine;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * A Filled Recipe Card: its tooltip says what it makes and what goes in, and whether the recipe is shaped, shapeless,
 * or for machines (and which).
 */
public class RecipeCardItem extends Item {
    public RecipeCardItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        RecipeCard card = stack.get(JasmComponents.RECIPE_CARD.get());
        if (card != null) {
            crafting(card, builder);
            return;
        }
        ProcessingCard processing = stack.get(JasmComponents.PROCESSING_CARD.get());
        if (processing != null) {
            processing(processing, builder);
        }
    }

    private static void crafting(RecipeCard card, Consumer<Component> builder) {
        ItemStack result = card.result();
        builder.accept(Component.translatable("tooltip.jasm.card.makes", result.getCount(), result.getHoverName()).withStyle(ChatFormatting.GRAY));
        builder.accept(modLine(result));
        builder.accept(Component.translatable(card.shapeless() ? "tooltip.jasm.card.shapeless" : "tooltip.jasm.card.shaped")
                .withStyle(card.shapeless() ? ChatFormatting.AQUA : ChatFormatting.GOLD));
        Map<Item, Integer> counts = new LinkedHashMap<>();
        Map<Item, Component> names = new LinkedHashMap<>();
        for (ItemStack input : card.inputs()) {
            if (!input.isEmpty()) {
                counts.merge(input.getItem(), 1, Integer::sum);
                names.putIfAbsent(input.getItem(), input.getHoverName());
            }
        }
        counts.forEach((item, n) -> builder.accept(
                Component.translatable("tooltip.jasm.card.input", n, names.get(item)).withStyle(ChatFormatting.DARK_GRAY)));
    }

    /** The mod the result comes from, so two cards making a "Tin Ingot" can be told apart. */
    private static Component modLine(ItemStack result) {
        String namespace = ModNameLine.namespaceOf(BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
        String name = ModList.get().getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace);
        return Component.literal(name).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
    }

    /** One ingredient line of a card's tooltip: what goes in, and through which face. */
    private record FacedInput(GridKey key, InputFace face) {}

    private static void processing(ProcessingCard card, Consumer<Component> builder) {
        ProcessingCard.Amount main = card.main();
        if (!main.isEmpty()) {
            builder.accept(Component.translatable(main.isFluid() || main.isMaterial() ? "tooltip.jasm.card.makes_fluid" : "tooltip.jasm.card.makes", main.label(),
                    main.stack().getHoverName()).withStyle(ChatFormatting.GRAY));
            if (!main.isFluid() && !main.isMaterial()) {
                builder.accept(modLine(main.stack()));
            }
        }
        for (ProcessingCard.Amount extra : card.extras()) {
            builder.accept(Component.translatable(extra.isFluid() || extra.isMaterial() ? "tooltip.jasm.card.also_fluid" : "tooltip.jasm.card.also", extra.label(),
                    extra.stack().getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        builder.accept(Component.translatable("tooltip.jasm.card.processing").withStyle(ChatFormatting.LIGHT_PURPLE));
        // the same ingredient through two faces is two lines; a face other than Default is named after it
        Map<FacedInput, Integer> counts = new LinkedHashMap<>();
        List<ProcessingCard.Amount> inputs = card.usedInputs();
        List<InputFace> faces = card.usedFaces();
        for (int i = 0; i < inputs.size(); i++) {
            counts.merge(new FacedInput(inputs.get(i).key(), faces.get(i)), inputs.get(i).count(), Integer::sum);
        }
        counts.forEach((input, n) -> {
            boolean fluid = input.key() instanceof GridKey.Fluid;
            boolean units = fluid || input.key() instanceof GridKey.Material;
            Component line = Component.translatable(units ? "tooltip.jasm.card.input_fluid" : "tooltip.jasm.card.input",
                    fluid ? FluidAmounts.label(n) : String.valueOf(n), ProcessingCard.Amount.of(input.key(), 1).stack().getHoverName());
            if (input.face() != InputFace.DEFAULT) {
                line = Component.translatable("tooltip.jasm.card.input_face", line, Component.translatable(input.face().translationKey()));
            }
            builder.accept(line.copy().withStyle(ChatFormatting.DARK_GRAY));
        });
        for (ProcessingCard.Machine machine : card.machines()) {
            builder.accept(Component.translatable("tooltip.jasm.card.machine", machine.name(), machine.pos().getX(), machine.pos().getY(),
                    machine.pos().getZ()).withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
