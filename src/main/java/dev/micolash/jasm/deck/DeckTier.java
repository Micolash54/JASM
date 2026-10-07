package dev.micolash.jasm.deck;

import dev.micolash.jasm.config.JasmConfig;

/**
 * Deck tiers: how much charge their battery holds (FE). How many wafers they carry is a world rule. Each item moved
 * in or out costs the same on every tier ({@code energyPerItem} in the config).
 */
public enum DeckTier {
    STARTER("starter_deck", 10_000),
    BASIC("basic_deck", 25_000),
    ADVANCED("advanced_deck", 50_000),
    ELITE("elite_deck", 125_000),
    ULTIMATE("ultimate_deck", 250_000);

    private final String registryName;
    private final int battery;

    DeckTier(String registryName, int battery) {
        this.registryName = registryName;
        this.battery = battery;
    }

    public String registryName() {
        return registryName;
    }

    /** Wafer slots, as this world's rules set them. */
    public int slots() {
        return JasmConfig.deckSlots(this);
    }

    public int battery() {
        return battery;
    }

    /** Slots in the Deck to Deck send grid. */
    public int sendSlots() {
        return switch (this) {
            case STARTER -> 1;
            case BASIC -> 3;
            case ADVANCED -> 6;
            case ELITE, ULTIMATE -> 9;
        };
    }

    /** Only Advanced and up come as a Crafting Deck too. */
    public boolean hasCraftingDeck() {
        return ordinal() >= ADVANCED.ordinal();
    }

    public String craftingRegistryName() {
        return registryName.replace("_deck", "_crafting_deck");
    }
}
