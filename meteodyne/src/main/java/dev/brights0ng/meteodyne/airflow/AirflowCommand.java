// Written by Claude (GreedyFluids M7b b4); edited by Claude (M7d d1: regions are tiles; d2: save counts; the view;
// M7e: the speed limit's and the cap's counts; M8b: heat across a tile's borders; b4: secondaries; M8b5b: merged
// regions).
package dev.brights0ng.meteodyne.airflow;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.heat.scan.ScanRecorder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /meteodyne airflow} (M7b b4), operators only:
 * <ul>
 *   <li>{@code status}: the air thread, the server-thread totals, and each region's tile, state, boxes, ms per
 *       step, lag, queue, frame age, steps, drops, failures and desyncs;</li>
 *   <li>{@code sample [pos]}: temperature and air velocity at a point (default: where the source stands), from the
 *       latest frame of the region whose tile holds it;</li>
 *   <li>{@code record start|stop|mark <text>}: the smoke recorder ({@link AirflowRecorder}), into
 *       {@code greedyfluids-scans/airflow/<stamp>/}.</li>
 *   <li>{@code show}: toggles the debug view for the player running it ({@link AirflowView}).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class AirflowCommand {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meteodyne").requires(src -> src.hasPermission(2))
                .then(Commands.literal("airflow")
                        .then(Commands.literal("status").executes(c -> status(c.getSource())))
                        .then(Commands.literal("show").executes(c -> show(c.getSource())))
                        .then(Commands.literal("sample")
                                .executes(c -> sample(c.getSource(), c.getSource().getPosition()))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(c -> sample(c.getSource(), Vec3Argument.getVec3(c, "pos")))))
                        .then(Commands.literal("record")
                                .then(Commands.literal("start").executes(c -> recordStart(c.getSource())))
                                .then(Commands.literal("stop").executes(c -> recordStop(c.getSource())))
                                .then(Commands.literal("mark")
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(c -> mark(c.getSource(),
                                                        StringArgumentType.getString(c, "text"))))))));
    }

    static int status(CommandSourceStack source) {
        AirflowRegions r = AirflowRegions.get();
        if (r == null) {
            reply(source, "Airflow is off: [heat] engine is " + Airflow.engine());
            return 1;
        }
        AirflowSections.Stats s = r.sections().stats();
        long now = r.level().getGameTime();
        AirflowRecorder rec = r.recorder();
        reply(source, String.format(Locale.ROOT,
                "Airflow: %d regions; air thread %s, %.1f s busy in all; server thread in all: hook %.1f ms, "
                        + "reads %.1f ms (%d sections), manager %.1f ms; %d sections cached, %d pending; "
                        + "%d saves written, %d saved sections lost to unloads, %d regions restored; "
                        + "%d matches held for speed, %d regions closed by the cap%s",
                r.regions().size(), r.runtime().alive() ? "up" : "DOWN", r.runtime().busyNanos() / 1e9,
                s.hookNanos() / 1e6, s.readNanos() / 1e6, s.reads(), r.managerNanos() / 1e6, s.cached(), s.pending(),
                r.savesWritten(), r.sectionsLost(), r.restores(), r.heldForSpeed(), r.closedByCap(),
                rec == null ? "" : "; recording " + rec.seconds() + " s into " + rec.dir().toAbsolutePath()));
        reply(source, neighbourLine(r));
        reply(source, mergeLine(r));
        for (AirflowRegions.Region g : r.regions()) {
            reply(source, regionLine(g, now));
        }
        return 1;
    }

    /** M8b b4: the secondaries now and so far, and what would run at each reported threshold. */
    static String neighbourLine(AirflowRegions r) {
        AirflowNeighbourTiles.Mode mode = r.neighbourMode();
        if (mode == AirflowNeighbourTiles.Mode.OFF) {
            return "Neighbour tiles (B3): OFF";
        }
        StringBuilder would = new StringBuilder(), so = new StringBuilder(), at = new StringBuilder();
        for (int i = 0; i < AirflowNeighbourTiles.REPORTED.length; i++) {
            String sep = i == 0 ? "" : "/";
            at.append(sep).append(AirflowNeighbourTiles.REPORTED[i]);
            would.append(sep).append(r.wouldNow(i));
            so.append(sep).append(String.format(Locale.ROOT, "%.0f", r.wouldTicks(i) / 20.0));
        }
        return String.format(Locale.ROOT,
                "Neighbour tiles (B3): %s at %d cells within %d blocks of a player in enclosed air; %d secondaries now, "
                        + "%.0f tile-s so far, %d opened, %d openings refused by the cap; would run at %s cells: %s "
                        + "now, %s tile-s so far; %d sections wanted for traces",
                mode, r.neighbourMinCells(), r.neighbourReach(), r.secondariesNow(), r.secondaryTicks() / 20.0,
                r.secondariesOpened(), r.secondariesRefused(), at, would, so, r.stripSections());
    }

    /** M8b5b: merged regions now, merges and splits so far, those under way and those given up. */
    static String mergeLine(AirflowRegions r) {
        int merged = 0, tiles = 0;
        for (AirflowRegions.Region g : r.regions()) {
            if (g.tiles() > 1) {
                merged++;
                tiles += g.tiles();
            }
        }
        return String.format(Locale.ROOT,
                "Merged regions (M1): %s, settle %.1f s; %d now (%d tiles); %d merges and %d splits so far, %d under "
                        + "way, %d given up",
                r.mergeMode(), r.mergeSettleSeconds(), merged, tiles, r.merges(), r.splits(), r.transitionsNow(),
                r.transitionsAbandoned());
    }

    static String regionLine(AirflowRegions.Region g, long now) {
        String bounds = String.format(Locale.ROOT, "#%d [%d..%d, %d..%d, %d..%d]%s%s", g.id(), g.x0(),
                g.x0() + g.nx() - 1, g.y0(), g.y0() + g.ny() - 1, g.z0(), g.z0() + g.nz() - 1,
                g.tiles() > 1 ? " (" + g.tiles() + " tiles merged)" : "",
                g.occupied() ? "" : g.secondary() ? " (secondary)" : " (empty)");
        AirflowRuntime.Slot slot = g.slot();
        if (slot == null) {
            return bounds + " reading sections";
        }
        AirflowRuntime.Frame f = slot.frame();
        String age = f == null ? "no frame" : String.format(Locale.ROOT, "frame %.1f s old",
                (now - f.tick()) * AirflowRuntime.TICK_SECONDS);
        AirflowRuntime.Counters c = slot.counters();
        return String.format(Locale.ROOT,
                "%s %s: %d boxes, %.1f ms/step, lag %.1f s, queue %d, %s; %d steps, %d drops, %d failures, "
                        + "%d desyncs; %d edits, %d toggles, %.0f W; %.0f kJ out across borders%s",
                bounds, slot.status(), slot.boxes(), slot.stepMs(), slot.lag(), slot.queued(), age, slot.steps(),
                slot.drops(), slot.failures(), c.desyncs(), c.edits(), c.toggles(), c.watts(), c.exchangedJ() / 1e3,
                slot.failure() == null ? "" : "; last failure: " + slot.failure());
    }

    static int sample(CommandSourceStack source, Vec3 at) {
        AirflowRegions r = AirflowRegions.get();
        if (r == null) {
            reply(source, "Airflow is off: [heat] engine is " + Airflow.engine());
            return 0;
        }
        AirflowRegions.Region g = r.regionAt(at.x, at.y, at.z);
        AirflowRuntime.Frame f = r.frameAt(at.x, at.y, at.z);
        if (g == null || f == null) {
            fail(source, String.format(Locale.ROOT, "No airflow frame covers %.1f %.1f %.1f", at.x, at.y, at.z));
            return 0;
        }
        double lx = at.x - g.x0(), ly = at.y - g.y0(), lz = at.z - g.z0();
        double[] v = new double[3];
        boolean air = f.frame().velocity(lx, ly, lz, v);
        reply(source, String.format(Locale.ROOT, "%.2f °C, air %s (%.2f, %.2f, %.2f) m/s at %.1f %.1f %.1f "
                        + "(region #%d, frame %.1f s old)",
                f.frame().temperature(lx, ly, lz), air ? "moving" : "(solid here)", v[0], v[1], v[2], at.x, at.y, at.z,
                g.id(), (r.level().getGameTime() - f.tick()) * AirflowRuntime.TICK_SECONDS));
        return 1;
    }

    static int show(CommandSourceStack source) {
        if (AirflowRegions.get() == null) {
            fail(source, "Airflow is off: [heat] engine is " + Airflow.engine());
            return 0;
        }
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "Only a player can watch the airflow view");
            return 0;
        }
        boolean on = AirflowView.toggle(player);
        reply(source, on ? "Airflow view on: tiles within " + AirflowView.RANGE + " blocks, once a second"
                : "Airflow view off");
        return 1;
    }

    static int recordStart(CommandSourceStack source) {
        AirflowRegions r = AirflowRegions.get();
        if (r == null) {
            fail(source, "Airflow is off: [heat] engine is " + Airflow.engine());
            return 0;
        }
        Path dir = ScanRecorder.directory(source.getServer()).resolve("airflow")
                .resolve(LocalDateTime.now().format(STAMP));
        try {
            AirflowRecorder rec = r.record(dir);
            reply(source, "Recording airflow into " + rec.dir().toAbsolutePath());
            return 1;
        } catch (RuntimeException e) {
            fail(source, "Airflow record: " + e.getMessage());
            return 0;
        }
    }

    static int recordStop(CommandSourceStack source) {
        AirflowRegions r = AirflowRegions.get();
        AirflowRecorder rec = r == null ? null : r.stopRecording();
        if (rec == null) {
            fail(source, "No airflow recording is running");
            return 0;
        }
        List<String> summary = rec.summary();
        for (String line : summary) {
            if (!line.isBlank()) {
                reply(source, line);
            }
        }
        reply(source, "Summary: " + rec.dir().resolve("summary.md").toAbsolutePath());
        return 1;
    }

    static int mark(CommandSourceStack source, String text) {
        AirflowRegions r = AirflowRegions.get();
        AirflowRecorder rec = r == null ? null : r.recorder();
        if (rec == null) {
            fail(source, "No airflow recording is running");
            return 0;
        }
        rec.mark(text);
        reply(source, "Marked at " + rec.seconds() + " s");
        return 1;
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static void fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
    }

    private AirflowCommand() {
    }
}
