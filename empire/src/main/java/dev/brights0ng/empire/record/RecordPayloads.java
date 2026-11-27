// Written by Gemini.
package dev.brights0ng.empire.record;

import dev.brights0ng.empire.Empire;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client payloads coordinating player motion/action recording sessions triggered via
 * {@code /empire debug record ...}.
 */
public final class RecordPayloads {

    public enum Action {
        START,
        STOP,
        STATUS,
        MARK;

        public static Action byOrdinal(int ordinal) {
            Action[] values = values();
            return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : STATUS;
        }
    }

    private static final StreamCodec<ByteBuf, Action> ACTION_CODEC = ByteBufCodecs.VAR_INT.map(
            Action::byOrdinal, Action::ordinal);

    /** Server instructs a player's client to start, stop, report status of, or mark a recording session. */
    public record Control(Action action, String arg) implements CustomPacketPayload {

        public static final Type<Control> TYPE = new Type<>(Empire.id("motion_record_control"));

        public static final StreamCodec<ByteBuf, Control> STREAM_CODEC = StreamCodec.composite(
                ACTION_CODEC, Control::action,
                ByteBufCodecs.STRING_UTF8, Control::arg,
                Control::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server forwards a confirmed world event (block break/place/till, pickup, container diff) to the active recorder. */
    public record ServerEvent(long gameTime, String kind, String payloadJson) implements CustomPacketPayload {

        public static final Type<ServerEvent> TYPE = new Type<>(Empire.id("motion_record_event"));

        public static final StreamCodec<ByteBuf, ServerEvent> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ServerEvent::gameTime,
                ByteBufCodecs.STRING_UTF8, ServerEvent::kind,
                ByteBufCodecs.STRING_UTF8, ServerEvent::payloadJson,
                ServerEvent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RecordPayloads() {
    }
}
