package dev.brights0ng.enginesandempires.frontier.deep;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.frontier.incursion.Incursion;
import dev.brights0ng.enginesandempires.frontier.incursion.Incursions;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Where thumping calls up an incursion (see {@link Disturbance}): a (lesser) thumper incursion against the thumpers around
 * the shot, with a rumble and a line for the players nearby. The latest calls are kept for the debug command and tests.
 */
public final class ThumperIncursions {

    /** A call: where, when, and the incursion it started (null if one was already under way nearby). */
    public record Call(BlockPos pos, long time, Integer incursionId) {
    }

    private static final List<Call> RECENT = new ArrayList<>();

    public static Incursion call(ServerLevel level, BlockPos pos) {
        Incursion incursion = Incursions.of(level).startThumper(pos);
        RECENT.add(new Call(pos.immutable(), level.getGameTime(), incursion == null ? null : incursion.id()));
        if (RECENT.size() > 32) {
            RECENT.remove(0);
        }
        if (incursion == null) {
            return null;
        }
        level.playSound(null, pos, SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 2.0F, 0.6F);
        Component message = Component.literal("The ground shudders. Something is coming for the thumper...")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().closerThan(pos, 96)) {
                player.displayClientMessage(message, true);
            }
        }
        return incursion;
    }

    /** The latest calls, oldest first (for the debug command and tests). */
    public static List<Call> recent() {
        return List.copyOf(RECENT);
    }

    private ThumperIncursions() {
    }
}
