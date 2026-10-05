package dev.brights0ng.enginesandempires.geophone;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A board-of-readings screen (logbook, smart logger, portable record display) asking the server for something: see
 * {@link ReadingBoardMenu} for the actions. A message of its own rather than a menu button, since a board can hold more
 * entries than a button id can safely name, and a rename carries text. The server trusts none of it: it checks the player
 * has such a screen open, and the rules decide.
 *
 * @param number the entry's number, for load, delete and rename; ignored otherwise
 * @param name   the new name, for a rename; empty otherwise
 */
public record ReadingBoardActionPayload(int action, int number, String name) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ReadingBoardActionPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "reading_board_action"));

    /** A name is at most {@link Logbook#MAX_NAME_LENGTH} characters, some of which can take several bytes to write. */
    public static final StreamCodec<ByteBuf, ReadingBoardActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ReadingBoardActionPayload::action,
            ByteBufCodecs.VAR_INT, ReadingBoardActionPayload::number,
            ByteBufCodecs.stringUtf8(Logbook.MAX_NAME_LENGTH * 4), ReadingBoardActionPayload::name,
            ReadingBoardActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
