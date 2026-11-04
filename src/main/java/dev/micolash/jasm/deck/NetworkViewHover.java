package dev.micolash.jasm.deck;

import dev.micolash.jasm.core.GridEntries;
import dev.micolash.jasm.deck.NetworkViewPayloads.Category;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * The lines of a Network tab row's hover, top to bottom, each with the tone the screen draws it in. The name comes first
 * (a port's or Power Interface's own, with the block it serves), where the block stands always comes last.
 */
public final class NetworkViewHover {
    private NetworkViewHover() {}

    /** How a line is drawn: plain text, the softer grey, or the colours for good, bad, quiet and warning. */
    public enum Tone {
        PLAIN,
        SOFT,
        GOOD,
        BAD,
        MUTED,
        WARN
    }

    public record Line(Component text, Tone tone) {}

    /**
     * The name; a Battery's charge, In and Out, or a block's status, power and note (a Relay Chatterbox's frequency);
     * then where it stands.
     */
    public static List<Line> row(NetworkViewPayloads.Node node) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(node.title().orElse(node.name()), Tone.PLAIN));
        if (node.category() == Category.BATTERIES) {
            lines.add(new Line(Component.translatable("screen.jasm.network.stored_of", GridEntries.abbreviate(node.stored()),
                    GridEntries.abbreviate(node.capacity())), Tone.SOFT));
            lines.addAll(flow(node.charge().in(), node.charge().out()));
        } else {
            if (node.status() == NetworkViewPayloads.Node.WORKING) {
                lines.add(new Line(Component.translatable("screen.jasm.network.status.working"), Tone.GOOD));
            } else if (node.status() == NetworkViewPayloads.Node.IDLE || node.category() == Category.POWER) {
                lines.add(new Line(Component.translatable("screen.jasm.network.status.idle"), Tone.MUTED));
            } else {
                lines.add(new Line(Component.translatable("screen.jasm.network.status.stopped"), Tone.BAD));
            }
            Component power = power(node);
            if (power != null) lines.add(new Line(power, Tone.SOFT));
            node.note().ifPresent(note -> lines.add(new Line(note, Tone.SOFT)));
        }
        lines.add(new Line(Component.translatable("screen.jasm.network.pos", node.pos().getX(), node.pos().getY(), node.pos().getZ()),
                Tone.SOFT));
        return lines;
    }

    /** "In: X FE/t" and "Out: X FE/t", coloured as on the Battery's own screen: In green and Out yellow, grey at 0. */
    public static List<Line> flow(long in, long out) {
        return List.of(
                new Line(Component.translatable("screen.jasm.battery.in", String.format("%,d", in)), in > 0 ? Tone.GOOD : Tone.SOFT),
                new Line(Component.translatable("screen.jasm.battery.out", String.format("%,d", out)), out > 0 ? Tone.WARN : Tone.SOFT));
    }

    private static Component power(NetworkViewPayloads.Node node) {
        if (node.power() > 0) {
            return Component.translatable("screen.jasm.network.feeding", String.format("%,d", node.power()));
        }
        if (node.power() < 0 && node.category() == Category.POWER) {
            return Component.translatable("screen.jasm.network.drawing", String.format("%,d", -(long) node.power()));
        }
        if (node.power() < 0) {
            return Component.translatable("screen.jasm.network.using", String.format("%,d", -(long) node.power()));
        }
        return null;
    }
}
