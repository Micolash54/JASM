package dev.micolash.jasm.archive;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** Archive tiers: registrations, FE buffer and the FE used every tick while loaded are provisional code constants. */
public enum ArchiveTier implements StringRepresentable {
    BASIC("basic_archive", 3, 50_000, 5),
    ADVANCED("advanced_archive", 6, 100_000, 10),
    ULTIMATE("ultimate_archive", 12, 200_000, 20);

    public static final Codec<ArchiveTier> CODEC = StringRepresentable.fromEnum(ArchiveTier::values);

    private final String registryName;
    private final int registrations;
    private final int energyBuffer;
    private final int drainPerTick;

    ArchiveTier(String registryName, int registrations, int energyBuffer, int drainPerTick) {
        this.registryName = registryName;
        this.registrations = registrations;
        this.energyBuffer = energyBuffer;
        this.drainPerTick = drainPerTick;
    }

    public String registryName() {
        return registryName;
    }

    public int registrations() {
        return registrations;
    }

    public int energyBuffer() {
        return energyBuffer;
    }

    /** FE used every tick while the Archive is loaded, linked wafers or not. */
    public int drainPerTick() {
        return drainPerTick;
    }

    @Override
    public String getSerializedName() {
        return registryName;
    }
}
