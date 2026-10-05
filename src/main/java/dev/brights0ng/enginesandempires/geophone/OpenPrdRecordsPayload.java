package dev.brights0ng.enginesandempires.geophone;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The portable record display's map screen asking the server to open its records list: the map is drawn by the client
 * alone, but the list has a reader slot, so it is a menu the server must open. The server checks that the slot really
 * holds a display, and that it is one the player is holding.
 *
 * @param slot where the display is in the player's inventory: the selected hotbar slot, or the off hand
 */
public record OpenPrdRecordsPayload(int slot) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenPrdRecordsPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "open_prd_records"));

    public static final StreamCodec<ByteBuf, OpenPrdRecordsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenPrdRecordsPayload::slot,
            OpenPrdRecordsPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
