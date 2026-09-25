package dev.micolash.jasm.deck;

/**
 * Deck tiers: how many wafers they carry, how much charge their battery holds (FE), and how much it uses each tick
 * while its screen is open. A full battery lasts about 17 / 21 / 21 / 35 / 52 minutes of open screen.
 */
public enum DeckTier {
    STARTER("starter_deck", 1, 20_000, 1),
    BASIC("basic_deck", 3, 50_000, 2),
    ADVANCED("advanced_deck", 6, 100_000, 4),
    ELITE("elite_deck", 12, 250_000, 6),
    ULTIMATE("ultimate_deck", 24, 500_000, 8);

    private final String registryName;
    private final int slots;
    private final int battery;
    private final int drainPerTick;

    DeckTier(String registryName, int slots, int battery, int drainPerTick) {
        this.registryName = registryName;
        this.slots = slots;
        this.battery = battery;
        this.drainPerTick = drainPerTick;
    }

    /** FE used each tick while the Deck's screen is open. */
    public int drainPerTick() {
        return drainPerTick;
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
