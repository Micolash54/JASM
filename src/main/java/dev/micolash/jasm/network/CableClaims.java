package dev.micolash.jasm.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.storage.ArchiveRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/** Keeps a cable's network owner when the network cache or its chunks are unloaded. */
public final class CableClaims extends SavedData {
    private record Claim(ArchiveRecord.Placement placement, Optional<UUID> owner, boolean blocked) {
        static final Codec<Claim> CODEC = RecordCodecBuilder.create(i -> i.group(
                        ArchiveRecord.Placement.CODEC.fieldOf("placement").forGetter(Claim::placement),
                        UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Claim::owner),
                        Codec.BOOL.optionalFieldOf("blocked", false).forGetter(Claim::blocked))
                .apply(i, Claim::new));
    }

    public static final Codec<CableClaims> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Claim.CODEC.listOf().optionalFieldOf("claims", List.of()).forGetter(data -> List.copyOf(data.claims.values())),
                    MachineOwner.CODEC.listOf().optionalFieldOf("owners", List.of()).forGetter(data -> List.copyOf(data.owners.values())))
            .apply(i, CableClaims::new));
    public static final SavedDataType<CableClaims> TYPE = new SavedDataType<>(Jasm.id("cable_claims"), CableClaims::new, CODEC);

    private final Map<ArchiveRecord.Placement, Claim> claims = new HashMap<>();
    private final Map<UUID, MachineOwner> owners = new HashMap<>();

    public CableClaims() {}

    private CableClaims(List<Claim> claims, List<MachineOwner> owners) {
        claims.forEach(claim -> this.claims.put(claim.placement(), claim));
        owners.forEach(owner -> this.owners.put(owner.id(), owner));
    }

    public static CableClaims get(ServerLevel level) {
        return level.getServer().getDataStorage().computeIfAbsent(TYPE);
    }

    public void rememberOwner(UUID owner, String name) {
        if (name.isEmpty()) {
            return;
        }
        MachineOwner value = new MachineOwner(owner, name);
        if (!value.equals(owners.put(owner, value))) {
            setDirty();
        }
    }

    public String ownerName(UUID owner) {
        MachineOwner known = owners.get(owner);
        return known == null ? "" : known.name();
    }

    private ArchiveRecord.Placement key(ServerLevel level, BlockPos pos) {
        return new ArchiveRecord.Placement(level.dimension(), pos.immutable());
    }

    public @Nullable UUID owner(ServerLevel level, BlockPos pos) {
        Claim claim = claims.get(key(level, pos));
        return claim == null ? null : claim.owner().orElse(null);
    }

    public boolean blocked(ServerLevel level, BlockPos pos) {
        Claim claim = claims.get(key(level, pos));
        return claim != null && claim.blocked();
    }

    public List<BlockPos> blockedPositions(ServerLevel level) {
        return claims.values().stream().filter(claim -> claim.blocked() && claim.placement().dimension().equals(level.dimension()))
                .map(claim -> claim.placement().pos()).toList();
    }

    public void set(ServerLevel level, BlockPos pos, @Nullable UUID owner, boolean blocked) {
        ArchiveRecord.Placement key = key(level, pos);
        Claim next = new Claim(key, Optional.ofNullable(owner), blocked);
        if (!next.equals(claims.put(key, next))) {
            setDirty();
            markPortsDirty(level, pos);
        }
    }

    public void remove(ServerLevel level, BlockPos pos) {
        if (claims.remove(key(level, pos)) != null) {
            setDirty();
            markPortsDirty(level, pos);
        }
    }

    private static void markPortsDirty(ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DataCableBlockEntity cable) {
            cable.ownersChanged();
        }
    }
}
