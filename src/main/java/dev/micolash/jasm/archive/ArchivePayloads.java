package dev.micolash.jasm.archive;

import dev.micolash.jasm.Jasm;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Messages between the Archive screen and the server. */
public final class ArchivePayloads {
    private ArchivePayloads() {}

    public enum Action {
        LINK,
        UNLINK,
        RECOVER,
        TRUST,
        UNTRUST;

        static final StreamCodec<ByteBuf, Action> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], Action::ordinal);
    }

    /**
     * A button press. {@code serial} picks the listed wafer (unlink, recover), {@code player} the trusted player
     * (untrust), {@code name} the player to trust. Unused fields are 0, the nil id and "".
     */
    public record Request(int containerId, Action action, long serial, UUID player, String name) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Jasm.id("archive_request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::containerId,
                Action.STREAM_CODEC, Request::action,
                ByteBufCodecs.VAR_LONG, Request::serial,
                UUIDUtil.STREAM_CODEC, Request::player,
                ByteBufCodecs.stringUtf8(16), Request::name,
                Request::new);

        public static Request of(int containerId, Action action) {
            return new Request(containerId, action, 0, ArchivePlacement.NO_OWNER, "");
        }

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** The outcome of a button press, shown inside the screen. {@code ok} is false for refusals. */
    public record Feedback(int containerId, String messageKey, boolean ok) implements CustomPacketPayload {
        public static final Type<Feedback> TYPE = new Type<>(Jasm.id("archive_feedback"));
        public static final StreamCodec<ByteBuf, Feedback> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Feedback::containerId,
                ByteBufCodecs.STRING_UTF8, Feedback::messageKey,
                ByteBufCodecs.BOOL, Feedback::ok,
                Feedback::new);

        @Override
        public Type<Feedback> type() {
            return TYPE;
        }
    }

    public record Trusted(UUID id, String name) {
        static final StreamCodec<ByteBuf, Trusted> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Trusted::id,
                ByteBufCodecs.STRING_UTF8, Trusted::name,
                Trusted::new);
    }

    static final StreamCodec<ByteBuf, ArchiveService.Entry> ENTRY_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ArchiveService.Entry::serial,
            ByteBufCodecs.STRING_UTF8, ArchiveService.Entry::name,
            ByteBufCodecs.VAR_LONG, ArchiveService.Entry::used,
            ByteBufCodecs.VAR_INT, ArchiveService.Entry::capacity,
            ByteBufCodecs.BOOL, ArchiveService.Entry::readable,
            ArchiveService.Entry::new);

    /** Everything the Archive screen shows. Sent when it opens, after every action, and whenever it changes. */
    public record State(int containerId, int energy, int registrations, boolean owner, String ownerName,
            List<ArchiveService.Entry> entries, List<Trusted> trusted) implements CustomPacketPayload {
        public static final State EMPTY = new State(-1, 0, 0, false, "", List.of(), List.of());
        public static final Type<State> TYPE = new Type<>(Jasm.id("archive_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, State::containerId,
                ByteBufCodecs.VAR_INT, State::energy,
                ByteBufCodecs.VAR_INT, State::registrations,
                ByteBufCodecs.BOOL, State::owner,
                ByteBufCodecs.STRING_UTF8, State::ownerName,
                ENTRY_CODEC.apply(ByteBufCodecs.list(64)), State::entries,
                Trusted.STREAM_CODEC.apply(ByteBufCodecs.list(256)), State::trusted,
                State::new);

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }
}
