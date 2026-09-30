package dev.micolash.jasm.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** The current owner, kept on the mined item until the block joins another network. */
public record MachineOwner(UUID id, String name) {
    public static final Codec<MachineOwner> CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(MachineOwner::id),
                    Codec.STRING.fieldOf("name").forGetter(MachineOwner::name))
            .apply(i, MachineOwner::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, MachineOwner> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, MachineOwner::id,
            ByteBufCodecs.STRING_UTF8, MachineOwner::name,
            MachineOwner::new);
}
