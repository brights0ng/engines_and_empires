package dev.brights0ng.enginesandempires.food;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A player shift+scrolled over a food stack in an open menu, asking for the next group of it to go on top.
 *
 * @param containerId the menu it was in (ignored unless it is still the player's open menu)
 * @param slot        the slot's index in that menu
 * @param staler      true for the next staler group (scrolling up), false for the next fresher one
 */
public record FoodTopPayload(int containerId, int slot, boolean staler) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FoodTopPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "food_top"));

    public static final StreamCodec<ByteBuf, FoodTopPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FoodTopPayload::containerId,
            ByteBufCodecs.VAR_INT, FoodTopPayload::slot,
            ByteBufCodecs.BOOL, FoodTopPayload::staler,
            FoodTopPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
