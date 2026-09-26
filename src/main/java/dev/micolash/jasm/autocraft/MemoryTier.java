package dev.micolash.jasm.autocraft;

/** Storage Module tiers: how many items of a job one holds. */
public enum MemoryTier {
    K1("storage_module_1k", 1_000),
    K4("storage_module_4k", 4_000),
    K16("storage_module_16k", 16_000),
    K64("storage_module_64k", 64_000);

    private final String registryName;
    private final int capacity;

    MemoryTier(String registryName, int capacity) {
        this.registryName = registryName;
        this.capacity = capacity;
    }

    public String registryName() {
        return registryName;
    }

    public int capacity() {
        return capacity;
    }
}
