package dev.micolash.jasm.client;

import dev.micolash.jasm.core.AmountMath;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** What an amount box says about the sum typed in it: the label beside it, its colour, and its hover text. */
final class AmountTips {
    private AmountTips() {}

    /** The result of a sum in green, the reason a sum can't be used in red, and otherwise the box's own label. */
    static Component label(AmountMath.Result result, String plainKey) {
        return switch (result.problem()) {
            case NONE -> result.sum() ? Component.translatable("screen.jasm.amount.result", result.value()) : Component.translatable(plainKey);
            case EMPTY, TOO_SMALL -> Component.translatable("screen.jasm.amount.short.small");
            case TOO_BIG -> Component.translatable("screen.jasm.amount.short.big");
            case BAD, DIVIDE_BY_ZERO -> Component.translatable("screen.jasm.amount.short.bad");
        };
    }

    static int color(AmountMath.Result result) {
        if (!result.ok()) return JasmGui.BAD;
        return result.sum() ? JasmGui.GOOD : JasmGui.SUBTEXT;
    }

    static Tooltip tooltip(AmountMath.Result result) {
        Component hint = Component.translatable("screen.jasm.amount.hint").withStyle(ChatFormatting.GRAY);
        Component problem = switch (result.problem()) {
            case NONE -> null;
            case EMPTY, TOO_SMALL -> Component.translatable("screen.jasm.amount.tip.small");
            case TOO_BIG -> Component.translatable("screen.jasm.amount.tip.big", AmountMath.MOST);
            case BAD -> Component.translatable("screen.jasm.amount.tip.bad");
            case DIVIDE_BY_ZERO -> Component.translatable("screen.jasm.amount.tip.zero");
        };
        if (problem == null) return Tooltip.create(hint);
        return Tooltip.create(Component.empty().append(problem.copy().withStyle(ChatFormatting.RED)).append("\n").append(hint));
    }
}
