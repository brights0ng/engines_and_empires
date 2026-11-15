// Written by Claude (GreedyFluids M7b b4); edited by Claude (M7d d1: regions are tiles; d2: save counts; the view).
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
                        + "%d saves written, %d saved sections lost to unloads, %d regions restored%s",
                r.regions().size(), r.runtime().alive() ? "up" : "DOWN", r.runtime().busyNanos() / 1e9,
                s.hookNanos() / 1e6, s.readNanos() / 1e6, s.reads(), r.managerNanos() / 1e6, s.cached(), s.pending(),
                r.savesWritten(), r.sectionsLost(), r.restores(),
                rec == null ? "" : "; recording " + rec.seconds() + " s into " + rec.dir().toAbsolutePath()));
        for (AirflowRegions.Region g : r.regions()) {
            reply(source, regionLine(g, now));
        }
        return 1;
    }

    static String regionLine(AirflowRegions.Region g, long now) {
        String bounds = String.format(Locale.ROOT, "#%d [%d..%d, %d..%d, %d..%d]%s", g.id(), g.x0(),
                g.x0() + AirflowRegions.WX - 1, g.y0(), g.y0() + AirflowRegions.WY - 1, g.z0(),
                g.z0() + AirflowRegions.WZ - 1, g.occupied() ? "" : " (empty)");
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
                        + "%d desyncs; %d edits, %d toggles, %.0f W%s",
                bounds, slot.status(), slot.boxes(), slot.stepMs(), slot.lag(), slot.queued(), age, slot.steps(),
                slot.drops(), slot.failures(), c.desyncs(), c.edits(), c.toggles(), c.watts(),
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
