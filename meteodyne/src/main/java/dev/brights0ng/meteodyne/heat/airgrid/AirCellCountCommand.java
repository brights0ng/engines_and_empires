// Written by Claude.
package dev.brights0ng.meteodyne.heat.airgrid;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /meteodyne heat cellcount [radius] [down] [up]}: snapshots a box around the player (server thread), counts
 * the cells three grid schemes would need ({@link CellCountStudy}, worker thread), and reports back in chat and to
 * {@code build/benchmarks/air-cellcount/<stamp>/summary.json}. Geometry only; no physics.
 */
public final class AirCellCountCommand {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("cellcount").requires(source -> source.hasPermission(2))
                .executes(context -> run(context.getSource(), 48, 32, 32))
                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 128))
                        .executes(context -> run(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), 32, 32))
                        .then(Commands.argument("down", IntegerArgumentType.integer(0, 128))
                                .executes(context -> run(context.getSource(), IntegerArgumentType.getInteger(context, "radius"),
                                        IntegerArgumentType.getInteger(context, "down"), 32))
                                .then(Commands.argument("up", IntegerArgumentType.integer(0, 128))
                                        .executes(context -> run(context.getSource(), IntegerArgumentType.getInteger(context, "radius"),
                                                IntegerArgumentType.getInteger(context, "down"), IntegerArgumentType.getInteger(context, "up"))))));
    }

    private static int run(CommandSourceStack source, int radius, int down, int up) throws CommandSyntaxException {
        BlockPos at = source.getPlayerOrException().blockPosition();
        ServerLevel level = source.getLevel();
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("A cell count study is already running."));
            return 0;
        }
        AirSnapshot.Result snapshot;
        try {
            snapshot = AirSnapshot.take(level, at.offset(-radius, -down, -radius), at.offset(radius, up, radius));
        } catch (AirSnapshot.Failure failure) {
            RUNNING.set(false);
            source.sendFailure(Component.literal("Cell count: " + failure.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            RUNNING.set(false);
            throw e;
        }
        CellCountStudy.Grid g = snapshot.grid();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Cell count: %dx%dx%d box (%,d blocks)%s read in %.0f ms; analysing...", g.sx(), g.sy(), g.sz(), g.size(),
                snapshot.clampedToBuildHeight() ? ", cut to the build height," : "", snapshot.ms())), false);

        int[] points = new int[snapshot.sources().length + 1];
        points[0] = g.index(at.getX() - g.x0(), at.getY() - g.y0(), at.getZ() - g.z0());
        System.arraycopy(snapshot.sources(), 0, points, 1, snapshot.sources().length);
        AirCellClassifier.Table table = AirCellClassifier.table();
        String dimension = level.dimension().location().toString();
        MinecraftServer server = source.getServer();
        CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            CellCountStudy.Result result = CellCountStudy.run(g, points);
            double analysisMs = (System.nanoTime() - start) / 1e6;
            Path file = write(report(result, snapshot, table, at, radius, down, up, dimension, analysisMs));
            return new Done(result, file, analysisMs);
        }, Util.backgroundExecutor()).whenComplete((done, error) -> server.execute(() -> {
            RUNNING.set(false);
            if (error != null) {
                Meteodyne.LOGGER.warn("Air cells: the cell count study failed", error);
                source.sendFailure(Component.literal("Cell count failed: " + error.getMessage()));
                return;
            }
            send(source, done, table);
        }));
        return 1;
    }

    private record Done(CellCountStudy.Result result, Path file, double analysisMs) {
    }

    private static void send(CommandSourceStack source, Done done, AirCellClassifier.Table table) {
        CellCountStudy.Result r = done.result();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Cell count done in %.0f ms:", done.analysisMs())), false);
        for (String line : r.chatLines()) {
            gray(source, line);
        }
        StringBuilder types = new StringBuilder("Blocks:");
        for (AirCellType t : AirCellType.values()) {
            types.append(' ').append(t.name()).append(' ').append(String.format(Locale.ROOT, "%,d", r.typeCounts()[t.ordinal()]));
        }
        gray(source, types.toString());
        gray(source, String.format(Locale.ROOT, "Simulated blocks outdoor (sky 15) %,d, indoor %,d; active: %d points, %,d forced blocks",
                r.simulatedOutdoor(), r.simulatedIndoor(), r.activePoints(), r.activeBlocks()));
        StringBuilder top = new StringBuilder();
        for (int k = 0; k < Math.min(5, table.topVolumeRule().size()); k++) {
            AirCellClassifier.FallThrough f = table.topVolumeRule().get(k);
            top.append(k == 0 ? "; top: " : ", ").append(f.block()).append(" (").append(f.states()).append(')');
        }
        gray(source, String.format(Locale.ROOT, "Volume rule: %,d of %,d states%s", table.volumeRuleStates(), table.states(), top));
        gray(source, done.file() == null ? "Couldn't write the summary (see the log)." : "Full results: " + done.file());
    }

    private static void gray(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.GRAY), false);
    }

    private static Map<String, Object> report(CellCountStudy.Result result, AirSnapshot.Result snapshot, AirCellClassifier.Table table,
                                              BlockPos at, int radius, int down, int up, String dimension, double analysisMs) {
        CellCountStudy.Grid g = snapshot.grid();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("writtenBy", "Claude");
        out.put("schema", "air-cellcount-v1");
        out.put("timeUtc", Instant.now().toString());
        out.put("dimension", dimension);
        out.put("player", List.of(at.getX(), at.getY(), at.getZ()));
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("min", List.of(g.x0(), g.y0(), g.z0()));
        box.put("max", List.of(g.x0() + g.sx() - 1, g.y0() + g.sy() - 1, g.z0() + g.sz() - 1));
        box.put("size", List.of(g.sx(), g.sy(), g.sz()));
        box.put("blocks", g.size());
        box.put("requested", Map.of("radius", radius, "down", down, "up", up));
        box.put("clampedToBuildHeight", snapshot.clampedToBuildHeight());
        out.put("box", box);
        out.put("snapshotMs", snapshot.ms());
        out.put("analysisMs", analysisMs);
        out.put("heatSources", snapshot.sources().length);
        Map<String, Object> classifier = new LinkedHashMap<>();
        classifier.put("states", table.states());
        classifier.put("buildMs", table.buildMs());
        classifier.put("volumeRuleStates", table.volumeRuleStates());
        List<Map<String, Object>> top = new ArrayList<>();
        for (AirCellClassifier.FallThrough f : table.topVolumeRule()) {
            top.add(Map.of("block", f.block(), "states", f.states()));
        }
        classifier.put("topVolumeRuleBlocks", top);
        out.put("classifier", classifier);
        out.put("definitions", Map.of(
                "cells", "simulated blocks: AIR, BOTTOM_HALF, TOP_HALF, THIN, PERMEABLE, DOOR; SOLID and FLUID are walls",
                "interfaces", "face-adjacent cell pairs",
                "interfaceMismatch", "interfaces whose two cells differ in dimensions",
                "active", "blocks within 8 (Chebyshev) of the player or a heat source are size-1 cells"));
        out.putAll(result.toMap());
        return out;
    }

    private static Path write(Map<String, Object> report) {
        try {
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
            Path base = Path.of(System.getProperty("meteodyne.cellCountDir", "../build/benchmarks/air-cellcount")).toAbsolutePath().normalize();
            Path directory = base.resolve(stamp);
            Files.createDirectories(directory);
            Path file = directory.resolve("summary.json");
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            return file;
        } catch (Exception e) {
            Meteodyne.LOGGER.warn("Air cells: couldn't write the cell count summary", e);
            return null;
        }
    }

    private AirCellCountCommand() {
    }
}
