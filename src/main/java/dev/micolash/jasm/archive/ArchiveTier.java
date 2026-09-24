package dev.micolash.jasm.archive;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** Archive tiers: registrations and FE buffer are provisional code constants. */
public enum ArchiveTier implements StringRepresentable {
    BASIC("basic_archive", 3, 50_000),
    ADVANCED("advanced_archive", 6, 100_000),
    ULTIMATE("ultimate_archive", 12, 200_000);

    public static final Codec<ArchiveTier> CODEC = StringRepresentable.fromEnum(ArchiveTier::values);

    private final String registryName;
    private final int registrations;
    private final int energyBuffer;

    ArchiveTier(String registryName, int registrations, int energyBuffer) {
        this.registryName = registryName;
        this.registrations = registrations;
        this.energyBuffer = energyBuffer;
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

    @Override
    public String getSerializedName() {
        return registryName;
    }
}
