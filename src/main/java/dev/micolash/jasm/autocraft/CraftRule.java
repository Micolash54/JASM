package dev.micolash.jasm.autocraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.config.JasmConfig;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * One rule on a Crafting Deck. {@code timed} false: when the Deck holds fewer than {@code threshold} of {@code item},
 * craft {@code amount} more. {@code timed} true: every {@code seconds} seconds, craft {@code amount}. A rule has at most
 * one job going at a time. {@code toPlayer}: the results go into the player's inventory instead of onto the Deck, and a
 * "fewer than" rule counts what the player carries.
 */
public record CraftRule(UUID id, ItemResource item, boolean timed, long threshold, int seconds, long amount, boolean enabled,
        boolean toPlayer) {
    public static final Codec<CraftRule> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(CraftRule::id),
            ItemResource.CODEC.fieldOf("item").forGetter(CraftRule::item),
            Codec.BOOL.optionalFieldOf("timed", false).forGetter(CraftRule::timed),
            Codec.LONG.optionalFieldOf("threshold", 1L).forGetter(CraftRule::threshold),
            Codec.INT.optionalFieldOf("seconds", 60).forGetter(CraftRule::seconds),
            Codec.LONG.optionalFieldOf("amount", 1L).forGetter(CraftRule::amount),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(CraftRule::enabled),
            Codec.BOOL.optionalFieldOf("to_player", false).forGetter(CraftRule::toPlayer))
            .apply(i, CraftRule::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftRule> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CraftRule::id,
            ItemResource.STREAM_CODEC, CraftRule::item,
            ByteBufCodecs.BOOL, CraftRule::timed,
            ByteBufCodecs.VAR_LONG, CraftRule::threshold,
            ByteBufCodecs.VAR_INT, CraftRule::seconds,
            ByteBufCodecs.VAR_LONG, CraftRule::amount,
            ByteBufCodecs.BOOL, CraftRule::enabled,
            ByteBufCodecs.BOOL, CraftRule::toPlayer,
            CraftRule::new);

    public static final Codec<List<CraftRule>> LIST_CODEC = CODEC.listOf();
    public static final StreamCodec<RegistryFriendlyByteBuf, List<CraftRule>> LIST_STREAM_CODEC = STREAM_CODEC.apply(ByteBufCodecs.list(64));

    /** The same rule with every number kept in range; the screen is never trusted. */
    public CraftRule cleaned() {
        int shortest = JasmConfig.RULE_MIN_SECONDS.getAsInt();
        int max = JasmConfig.MAX_REQUEST.getAsInt();
        return new CraftRule(id, item, timed, Math.clamp(threshold, 1, max), Math.clamp(seconds, shortest, 86_400), Math.clamp(amount, 1, max),
                enabled,
                toPlayer);
    }
}
