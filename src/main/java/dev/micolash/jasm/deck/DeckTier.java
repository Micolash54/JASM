package dev.micolash.jasm.deck;

/** Deck tiers: how many wafers they carry and how much charge their battery holds (FE). */
public enum DeckTier {
    STARTER("starter_deck", 1, 20_000),
    BASIC("basic_deck", 3, 50_000),
    ADVANCED("advanced_deck", 6, 100_000),
    ULTIMATE("ultimate_deck", 12, 250_000);

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
}
