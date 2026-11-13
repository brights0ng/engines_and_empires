// Written by Claude; edited by Claude (CELLS count, M6f).
package dev.brights0ng.meteodyne.heat.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.IntSupplier;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Both command entrances share the scan operations; the short alias also permits the singleplayer owner. */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class ScanCommand {
    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        var scan = event.getDispatcher().register(Commands.literal("meteodyne")
                .requires(src -> src.hasPermission(2)).then(subtree("scan"))).getChild("scan");
        event.getDispatcher().register(Commands.literal("gfscan")
                .requires(src -> src.hasPermission(2) || (src.getServer().isSingleplayer()
                        && src.getPlayer() != null
                        && src.getServer().isSingleplayerOwner(src.getPlayer().getGameProfile())))
                .redirect(scan));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> subtree(String name) {
        return Commands.literal(name)
                .then(Commands.literal("snapshot")
                        .executes(c -> snapshot(c.getSource(), "snap", 64, 32))
                        .then(Commands.argument("label", StringArgumentType.word())
                                .executes(c -> snapshot(c.getSource(), StringArgumentType.getString(c, "label"), 64, 32))
                                .then(Commands.argument("rxz", IntegerArgumentType.integer(8, 128))
                                        .executes(c -> snapshot(c.getSource(), StringArgumentType.getString(c, "label"),
                                                IntegerArgumentType.getInteger(c, "rxz"), 32))
                                        .then(Commands.argument("ry", IntegerArgumentType.integer(8, 64))
                                                .executes(c -> snapshot(c.getSource(), StringArgumentType.getString(c, "label"),
                                                        IntegerArgumentType.getInteger(c, "rxz"),
                                                        IntegerArgumentType.getInteger(c, "ry")))))))
                .then(Commands.literal("record")
                        .then(Commands.literal("start").executes(c -> guard(c.getSource(), () -> {
                            var player = c.getSource().getPlayer();
                            if (player == null) throw new IllegalArgumentException("Recording needs a player anchor");
                            Path file = ScanRecorder.start(ScanRecorder.directory(c.getSource().getServer()),
                                    player.serverLevel(), player.getUUID());
                            reply(c.getSource(), "Recording " + file.toAbsolutePath() + " (" + size(file) + " bytes)");
                            return 1;
                        })))
                        .then(Commands.literal("stop").executes(c -> guard(c.getSource(), () -> {
                            report(c.getSource(), ScanRecorder.stop()); return 1;
                        })))
                        .then(Commands.literal("status").executes(c -> guard(c.getSource(), () -> {
                            var status = ScanRecorder.status();
                            if (status == null) reply(c.getSource(), "No scan recording yet");
                            else report(c.getSource(), status);
                            return 1;
                        })))
                        .then(Commands.literal("mark").then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(c -> guard(c.getSource(), () -> {
                                    ScanRecorder.mark(StringArgumentType.getString(c, "text"));
                                    reply(c.getSource(), "Scan mark added"); return 1;
                                }))))
                        .then(Commands.literal("auto")
                                .then(Commands.literal("on").executes(c -> auto(c.getSource(), true)))
                                .then(Commands.literal("off").executes(c -> auto(c.getSource(), false)))));
    }

    private static int snapshot(CommandSourceStack source, String label, int rxz, int ry) {
        return guard(source, () -> {
            long start = System.nanoTime();
            Path file = ScanSnapshot.write(source.getLevel(), BlockPos.containing(source.getPosition()),
                    rxz, ry, label, ScanRecorder.directory(source.getServer()), source.getPlayer());
            reply(source, String.format(Locale.ROOT, "%s (%d bytes, %d cells, %.1f ms)",
                    file.toAbsolutePath(), size(file), (2 * rxz) * (2 * ry) * (2 * rxz),
                    (System.nanoTime() - start) / 1e6));
            return 1;
        });
    }

    private static int auto(CommandSourceStack source, boolean enabled) {
        return guard(source, () -> {
            Path dir = ScanRecorder.directory(source.getServer());
            try {
                Files.createDirectories(dir);
                Path marker = dir.resolve("autorecord");
                if (enabled) {
                    if (!Files.exists(marker)) Files.createFile(marker);
                } else Files.deleteIfExists(marker);
                reply(source, "Automatic scan recording " + (enabled ? "on: " : "off: ") + marker.toAbsolutePath());
                return 1;
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private static void report(CommandSourceStack source, ScanRecorder.Status status) {
        long[] n = status.recordCounts();
        reply(source, String.format(Locale.ROOT,
                "%s %s (%d bytes, %.1f s, %d records: %d states, %d dimensions, %d positions, %d blocks, "
                        + "%d actions, %d sections (%d with cells), %d load samples, %d marks; %d off-thread dropped, %d sections pending)",
                status.recording() ? "Recording" : "Stopped", status.file().toAbsolutePath(), status.fileSize(),
                status.durationTicks() / 20.0, status.records(), n[ScanFormat.STATE], n[ScanFormat.DIM], n[ScanFormat.POS],
                n[ScanFormat.BLOCK], n[ScanFormat.ACTION], n[ScanFormat.SECTION], n[ScanFormat.CELLS], n[ScanFormat.LOAD], n[ScanFormat.MARK],
                status.droppedOffThread(), status.sectionsPending()));
    }

    private static long size(Path file) {
        try { return Files.size(file); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int guard(CommandSourceStack source, IntSupplier operation) {
        try { return operation.getAsInt(); }
        catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            source.sendFailure(Component.literal("Scan: " + e.getMessage()));
            return 0;
        }
    }

    private ScanCommand() { }
}
