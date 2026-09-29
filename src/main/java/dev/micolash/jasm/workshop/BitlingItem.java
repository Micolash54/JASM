package dev.micolash.jasm.workshop;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.core.ChipBalance;
import dev.micolash.jasm.core.Training;
import dev.micolash.jasm.registry.JasmComponents;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * A critter that works the Chip Workshop. It carries its own battery, and a typed Bitling or Nibbling learns from every
 * chip it makes until it can evolve.
 */
public class BitlingItem extends Item {
    private final BitlingKind kind;
    private final BitlingStage stage;

    public BitlingItem(Properties properties, BitlingKind kind, BitlingStage stage) {
        super(properties.stacksTo(1));
        this.kind = kind;
        this.stage = stage;
    }

    public BitlingKind kind() {
        return kind;
    }

    public BitlingStage stage() {
        return stage;
    }

    /** Registry name, like {@code logic_nibbling} or {@code basic_bitling}. */
    public static String registryName(BitlingKind kind, BitlingStage stage) {
        return kind.name().toLowerCase(Locale.ROOT) + "_" + stage.name().toLowerCase(Locale.ROOT);
    }

    /** FE the battery holds. */
    public static int battery(BitlingKind kind, BitlingStage stage) {
        if (kind == BitlingKind.BASIC) {
            return config(JasmConfig.BATTERY_BASIC);
        }
        return config(switch (stage) {
            case BITLING -> JasmConfig.BATTERY_BITLING;
            case NIBBLING -> JasmConfig.BATTERY_NIBBLING;
            case BYTELING -> JasmConfig.BATTERY_BYTELING;
        });
    }

    public int battery() {
        return battery(kind, stage);
    }

    /** Chips needed to fill the training bar; 0 if it never trains. */
    public int trainingRequired() {
        return Training.required(kind, stage, balance());
    }

    /** The chip numbers from the server config, or their defaults before it is loaded. */
    public static ChipBalance balance() {
        return JasmConfig.SPEC.isLoaded() ? ChipBalance.fromConfig() : DEFAULT_BALANCE;
    }

    // Tooltips can be drawn before any world, and so the server config, is loaded.
    private static final ChipBalance DEFAULT_BALANCE = new ChipBalance(JasmConfig.ODDS_OWN_TYPE_BITLING.getDefault(),
            JasmConfig.ADVANCED_CHANCE_BASIC.getDefault(), JasmConfig.ADVANCED_CHANCE_BITLING.getDefault(),
            JasmConfig.ADVANCED_CHANCE_NIBBLING.getDefault(), JasmConfig.TRAINING_BITLING.getDefault(), JasmConfig.TRAINING_NIBBLING.getDefault());

    private static int config(ModConfigSpec.IntValue value) {
        return JasmConfig.SPEC.isLoaded() ? value.getAsInt() : value.getDefault();
    }

    public static int energy(ItemStack stack) {
        return stack.getOrDefault(JasmComponents.ENERGY.get(), 0);
    }

    public static int trained(ItemStack stack) {
        return stack.getOrDefault(JasmComponents.TRAINING.get(), 0);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
            TooltipFlag flag) {
        builder.accept(Component.translatable("tooltip.jasm.bitling.stage",
                Component.translatable("tooltip.jasm.bitling.stage." + stage.name().toLowerCase(Locale.ROOT))).withStyle(ChatFormatting.GRAY));
        int required = trainingRequired();
        if (required > 0) {
            int trained = Math.min(trained(stack), required);
            builder.accept(Component.translatable(Training.full(trained, required) ? "tooltip.jasm.bitling.training_full" : "tooltip.jasm.bitling.training",
                    trained * 100 / required).withStyle(ChatFormatting.GRAY));
        } else if (stage == BitlingStage.BYTELING) {
            builder.accept(Component.translatable("tooltip.jasm.bitling.fully_learned").withStyle(ChatFormatting.GRAY));
        }
        builder.accept(Component.translatable("tooltip.jasm.bitling.battery", String.format("%,d", energy(stack)),
                String.format("%,d", battery())).withStyle(ChatFormatting.GRAY));
    }
}
