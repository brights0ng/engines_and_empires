package dev.brights0ng.enginesandempires.geophone;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The portable record display, held up and worked by hand, asking the server to change one of its readings: delete it, or
 * rename it. There is no menu open for this (the display's own screen is its interface), so the server checks for itself
 * that the slot named is one the player is holding, and that a display is in it; see {@link PrdItem#act}.
 *
 * @param slot   where the display is: the selected hotbar slot, or the off hand
 * @param action {@link PrdItem#DELETE} or {@link PrdItem#RENAME}
 * @param number the entry's number (never its row, so the right one is changed even if another went meanwhile)
 * @param name   the new name, for a rename; empty otherwise
 */
public record PrdActionPayload(int slot, int action, int number, String name) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PrdActionPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "prd_action"));

    public static final StreamCodec<ByteBuf, PrdActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrdActionPayload::slot,
            ByteBufCodecs.VAR_INT, PrdActionPayload::action,
            ByteBufCodecs.VAR_INT, PrdActionPayload::number,
            ByteBufCodecs.stringUtf8(Logbook.MAX_NAME_LENGTH * 4), PrdActionPayload::name,
            PrdActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
