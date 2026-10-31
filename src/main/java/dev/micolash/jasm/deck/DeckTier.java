package dev.micolash.jasm.deck;

import dev.micolash.jasm.config.JasmConfig;

/**
 * Deck tiers. How much charge their battery holds (FE) and how many wafers they carry are world rules. Each item moved
 * in or out costs the same on every tier ({@code energyPerItem} in the config).
 */
public enum DeckTier {
    STARTER("starter_deck"),
    BASIC("basic_deck"),
    ADVANCED("advanced_deck"),
    ELITE("elite_deck"),
    ULTIMATE("ultimate_deck");

    private final String registryName;

    DeckTier(String registryName) {
        this.registryName = registryName;
    }

    public String registryName() {
        return registryName;
    }

    /** Wafer slots, as this world's rules set them. */
    public int slots() {
        return JasmConfig.deckSlots(this);
    }

    /** FE the battery holds, as this world's rules set it. */
    public int battery() {
        return JasmConfig.deckBattery(this);
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
