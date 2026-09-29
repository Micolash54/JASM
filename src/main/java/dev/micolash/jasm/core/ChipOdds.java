package dev.micolash.jasm.core;

import java.util.random.RandomGenerator;

/** Which chip a critter makes. Every chip is rolled on its own: first its type, then whether it comes out Advanced. */
public final class ChipOdds {
    /** One chip made. */
    public record Roll(ChipType type, boolean advanced) {}

    /** Chance of each type, and of Advanced. */
    public record Odds(double logic, double memory, double link, double advanced) {
        double of(ChipType type) {
            return switch (type) {
                case LOGIC -> logic;
                case MEMORY -> memory;
                case LINK -> link;
            };
        }
    }

    private ChipOdds() {}

    /** {@code advancedSelected} is the Workshop's toggle; only a Byteling listens to it. */
    public static Odds of(BitlingKind kind, BitlingStage stage, boolean advancedSelected, ChipBalance balance) {
        ChipType own = kind.chipType();
        if (own == null) {
            return new Odds(1 / 3.0, 1 / 3.0, 1 / 3.0, balance.advancedBasic());
        }
        return switch (stage) {
            case BITLING -> odds(own, balance.ownTypeBitling(), (1 - balance.ownTypeBitling()) / 2, balance.advancedBitling());
            case NIBBLING -> odds(own, 1, 0, balance.advancedNibbling());
            case BYTELING -> odds(own, 1, 0, advancedSelected ? 1 : 0);
        };
    }

    private static Odds odds(ChipType own, double ownChance, double otherChance, double advanced) {
        return new Odds(own == ChipType.LOGIC ? ownChance : otherChance, own == ChipType.MEMORY ? ownChance : otherChance,
                own == ChipType.LINK ? ownChance : otherChance, advanced);
    }

    public static Roll roll(Odds odds, RandomGenerator random) {
        double pick = random.nextDouble();
        ChipType type = ChipType.LINK;
        for (ChipType candidate : ChipType.values()) {
            pick -= odds.of(candidate);
            if (pick < 0) {
                type = candidate;
                break;
            }
        }
        // nextDouble is below 1, so a 0% chance never hits and a 100% chance always does.
        return new Roll(type, random.nextDouble() < odds.advanced());
    }
}
