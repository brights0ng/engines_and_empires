// Written by Gemini.
package dev.brights0ng.empire.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.brights0ng.empire.record.MotionRecorderServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /empire debug record ...}: starts, stops, marks, and inspects player movement/input/action recordings used
 * to study human pacing and movement trends for civilian AI tuning.
 */
public final class RecordDebugCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("record")
                .then(Commands.literal("start")
                        .executes(ctx -> start(ctx.getSource(), ""))
                        .then(Commands.argument("label", StringArgumentType.word())
                                .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "label")))))
                .then(Commands.literal("stop")
                        .executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("mark")
                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> mark(ctx.getSource(), StringArgumentType.getString(ctx, "note")))));
    }

    private static int start(CommandSourceStack source, String label) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (!MotionRecorderServer.start(player, label)) {
            source.sendFailure(Component.literal("Cannot start recording: client does not support Empire motion recorder"));
            return 0;
        }
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (!MotionRecorderServer.stop(player)) {
            source.sendFailure(Component.literal("Cannot stop recording: client channel unavailable"));
            return 0;
        }
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (!MotionRecorderServer.queryStatus(player)) {
            source.sendSuccess(() -> Component.literal("Motion recorder (server): "
                    + (MotionRecorderServer.isRecording(player.getUUID()) ? "active" : "idle")), false);
        }
        return 1;
    }

    private static int mark(CommandSourceStack source, String note) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (!MotionRecorderServer.isRecording(player.getUUID())) {
            source.sendFailure(Component.literal("Not currently recording. Use /empire debug record start [label] first."));
            return 0;
        }
        if (!MotionRecorderServer.mark(player, note)) {
            source.sendFailure(Component.literal("Failed to send marker to client"));
            return 0;
        }
        return 1;
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        source.sendFailure(Component.literal("Run /empire debug record as a player"));
        return null;
    }

    private RecordDebugCommands() {
    }
}
