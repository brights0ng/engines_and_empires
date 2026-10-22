// Edited by Gemini: added SetTab payload for smart logger ORES/OIL tab switching.
package dev.brights0ng.seismicsurveyance;

import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What a player working a smart logger's map asks the server for. The map's controls are worked out on the client, which
 * sees where the pointer is; only what changes the logger for everyone goes to the server, which checks it: that the
 * player is near that logger, that its display is on, and that what is asked for is sane.
 */
public final class LoggerMapPayloads {

    /**
     * A new view of the map: dragged, zoomed, or recentred. The view is shared by everyone looking at the logger.
     *
     * @param pos the logger's master block
     */
    public record View(BlockPos pos, double x, double z, int zoom) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<View> TYPE = new CustomPacketPayload.Type<>(
                SeismicSurveyance.id("logger_map_view"));

        public static final StreamCodec<ByteBuf, View> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, View::pos,
                ByteBufCodecs.DOUBLE, View::x,
                ByteBufCodecs.DOUBLE, View::z,
                ByteBufCodecs.VAR_INT, View::zoom,
                View::new);

        public LoggerDisplay.View view() {
            return new LoggerDisplay.View(x, z, zoom);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Send one of the logger's readings, by entry number, to the portable record display in its dock: a selected reading
     * right-clicked again.
     */
    public record Send(BlockPos pos, int number) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Send> TYPE = new CustomPacketPayload.Type<>(
                SeismicSurveyance.id("logger_map_send"));

        public static final StreamCodec<ByteBuf, Send> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Send::pos,
                ByteBufCodecs.VAR_INT, Send::number,
                Send::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Switch the logger's active tab between ORES and OIL.
     */
    public record SetTab(BlockPos pos, byte tab) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SetTab> TYPE = new CustomPacketPayload.Type<>(
                SeismicSurveyance.id("logger_map_tab"));

        public static final StreamCodec<ByteBuf, SetTab> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetTab::pos,
                ByteBufCodecs.BYTE, SetTab::tab,
                SetTab::new);

        public LoggerDisplay.Tab tabEnum() {
            int ordinal = tab & 0xFF;
            LoggerDisplay.Tab[] values = LoggerDisplay.Tab.values();
            return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : LoggerDisplay.Tab.ORES;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private LoggerMapPayloads() {
    }
}
