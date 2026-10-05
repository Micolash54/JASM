package dev.micolash.jasm.archive;

import dev.micolash.jasm.Jasm;
import io.netty.buffer.ByteBuf;
import java.util.List;
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
        RESET_DECK;

        static final StreamCodec<ByteBuf, Action> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], Action::ordinal);
    }

    /** A button press. {@code serial} picks the listed wafer (unlink, recover); 0 when unused. */
    public record Request(int containerId, Action action, long serial) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Jasm.id("archive_request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::containerId,
                Action.STREAM_CODEC, Request::action,
                ByteBufCodecs.VAR_LONG, Request::serial,
                Request::new);

        public static Request of(int containerId, Action action) {
            return new Request(containerId, action, 0);
        }

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    static final StreamCodec<ByteBuf, ArchiveService.Entry> ENTRY_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ArchiveService.Entry::serial,
            ByteBufCodecs.STRING_UTF8, ArchiveService.Entry::name,
            ByteBufCodecs.VAR_LONG, ArchiveService.Entry::used,
            ByteBufCodecs.VAR_LONG, ArchiveService.Entry::capacity,
            ByteBufCodecs.BOOL, ArchiveService.Entry::readable,
            ByteBufCodecs.BOOL, ArchiveService.Entry::fluid,
            ArchiveService.Entry::new);

    /** Everything the Archive screen shows. Sent when it opens, after every action, and whenever it changes. */
    public record State(int containerId, int energy, int registrations, List<ArchiveService.Entry> entries,
            String linkedPlayer) implements CustomPacketPayload {
        public static final State EMPTY = new State(-1, 0, 0, List.of(), "");
        public static final Type<State> TYPE = new Type<>(Jasm.id("archive_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, State::containerId,
                ByteBufCodecs.VAR_INT, State::energy,
                ByteBufCodecs.VAR_INT, State::registrations,
                ENTRY_CODEC.apply(ByteBufCodecs.list(64)), State::entries,
                ByteBufCodecs.STRING_UTF8, State::linkedPlayer,
                State::new);

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }
}
