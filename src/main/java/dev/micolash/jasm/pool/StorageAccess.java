package dev.micolash.jasm.pool;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

public enum StorageAccess implements StringRepresentable {
    READ_WRITE("read_write", true, true),
    READ_ONLY("read_only", true, false),
    WRITE_ONLY("write_only", false, true);

    public static final Codec<StorageAccess> CODEC = StringRepresentable.fromEnum(StorageAccess::values);
    public static final StreamCodec<ByteBuf, StorageAccess> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(StorageAccess::byId, StorageAccess::ordinal);
    private final String name;
    private final boolean read;
    private final boolean write;

    StorageAccess(String name, boolean read, boolean write) { this.name = name; this.read = read; this.write = write; }
    @Override
    public String getSerializedName() { return name; }
    public boolean canRead() { return read; }
    public boolean canWrite() { return write; }
    public static StorageAccess byId(int id) { return id >= 0 && id < values().length ? values()[id] : READ_WRITE; }
    public StorageAccess next() { return byId((ordinal() + 1) % values().length); }
}
