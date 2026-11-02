package dev.micolash.jasm.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Where a network block stands: its dimension and position. Equal positions in two dimensions are two different
 * blocks. Saved with the same fields as an Archive's placement.
 */
public record NetworkNode(ResourceKey<Level> dimension, BlockPos pos) {
    public static final Codec<NetworkNode> CODEC = RecordCodecBuilder.create(i -> i.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(NetworkNode::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(NetworkNode::pos))
            .apply(i, NetworkNode::new));

    public NetworkNode {
        pos = pos.immutable();
    }

    public static NetworkNode of(Level level, BlockPos pos) {
        return new NetworkNode(level.dimension(), pos);
    }
}
