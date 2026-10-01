package dev.micolash.jasm.deck;

/**
 * Deck tiers: how many wafers they carry and how much charge their battery holds (FE). Each item moved in or out
 * costs the same on every tier ({@code energyPerItem} in the config).
 */
public enum DeckTier {
    STARTER("starter_deck", 1, 10_000),
    BASIC("basic_deck", 3, 25_000),
    ADVANCED("advanced_deck", 6, 50_000),
    ELITE("elite_deck", 12, 125_000),
    ULTIMATE("ultimate_deck", 24, 250_000);

    private final String registryName;
    private final int slots;
    private final int battery;

    DeckTier(String registryName, int slots, int battery) {
        this.registryName = registryName;
        this.slots = slots;
        this.battery = battery;
    }

    public String registryName() {
        return registryName;
    }

    public int slots() {
        return slots;
    }

    public int battery() {
        return battery;
    }

    /** Only Advanced and up come as a Crafting Deck too. */
    public boolean hasCraftingDeck() {
        return ordinal() >= ADVANCED.ordinal();
    }

    public String craftingRegistryName() {
        return registryName.replace("_deck", "_crafting_deck");
    }
}
