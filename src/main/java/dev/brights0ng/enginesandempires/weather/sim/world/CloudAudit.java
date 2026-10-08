package dev.brights0ng.enginesandempires.weather.sim.world;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudChecks;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudDiagnostics;
import dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSim;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A running log of what the cloud spawner does and why ({@code /eae weather audit on}, 2026-10-06), for checking the
 * weather from the backend while someone flies around: {@code logs/weather-audit.log} in the server's folder.
 *
 * <ul>
 *   <li>Every pass: each cloud that formed or grew, where (relative to the nearest player), why, its base and its
 *       rain.</li>
 *   <li>Every {@value #SUMMARY_PASSES} passes (about 30 s): per player, where they are, the air and the systems near
 *       them, the clouds within 4 km by type, and every rule broken ({@link CloudChecks}) with examples.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class CloudAudit {

    @net.neoforged.bus.api.SubscribeEvent
    static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        stop();
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.cloudAudit()) {
            try {
                start(event.getServer().overworld());
            } catch (IOException e) {
                EnginesAndEmpiresMod.LOGGER.warn("Weather: couldn't start the cloud audit", e);
            }
        }
    }

    static final int SUMMARY_PASSES = 15;
    static final double NEAR = 4096;

    private static BufferedWriter out;
    private static int passes;

    public static boolean on() {
        return out != null;
    }

    /** Starts writing (appending) to the audit log; returns its path. */
    public static Path start(ServerLevel level) throws IOException {
        stop();
        Path dir = level.getServer().getServerDirectory().resolve("logs");
        Files.createDirectories(dir);
        Path file = dir.resolve("weather-audit.log");
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        passes = 0;
        line("==== audit started, game time " + level.getGameTime());
        return file;
    }

    public static void stop() {
        if (out != null) {
            try {
                line("==== audit stopped");
                out.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            out = null;
        }
    }

    /** After every cloud pass. */
    static void afterPass(ServerLevel level, WeatherSim sim, CloudSim.Env env, CloudSim.Report report) {
        if (out == null) {
            return;
        }
        try {
            long now = level.getGameTime();
            List<ServerPlayer> players = level.players();
            for (SimCloud c : sim.cloudSim().changedThisPass()) {
                double[] rel = nearest(players, c.xAt(now), c.zAt(now));
                line(String.format(Locale.ROOT, "t%d NEW %-24s %+6.0f %+6.0f from %s | cause: %s | base y %.0f, %.0f "
                                + "thick, rain %.2f%s", now, c.type.id, rel[0], rel[1], rel[2] < 0 ? "?" :
                                players.get((int) rel[2]).getGameProfile().getName(), c.cause, c.baseY, c.thickness,
                        c.precipitation, Float.isInfinite(c.rainBottom) ? "" : " (virga)"));
            }
            if (++passes % SUMMARY_PASSES == 0) {
                summary(level, sim, env, report, now);
            }
            out.flush();
        } catch (IOException | RuntimeException ex) {
            EnginesAndEmpiresMod.LOGGER.warn("Weather: the cloud audit failed; stopping it", ex);
            stop();
        }
    }

    private static void summary(ServerLevel level, WeatherSim sim, CloudSim.Env env, CloudSim.Report r, long now)
            throws IOException {
        double hour = (6 + Math.floorMod(env.dayTime(), 24000L) / 1000.0) % 24;
        line(String.format(Locale.ROOT, "t%d ---- summary at %02d:%02d: %d clouds, %d raining on the ground, %.1f mm "
                        + "drained last pass, last pass %.2f ms, %d outflows", now, (int) hour,
                (int) ((hour % 1) * 60), r.clouds(), r.raining(), r.drainedMm(), r.nanos() / 1e6,
                CloudWorld.gustCount()));
        for (ServerPlayer p : level.players()) {
            double px = p.getX();
            double pz = p.getZ();
            CloudDiagnostics.Air air = env.air(px, pz);
            CloudDiagnostics.Need need = CloudDiagnostics.diagnose(px, pz, air, env.systems(), env.dayTime(),
                    env.season());
            line(String.format(Locale.ROOT, "  player %s at %.0f %.0f (y %.0f): air %.1f C (aloft %.1f), %.1f mm, "
                            + "rh %.2f, surface %d, elevation %.0f | wanted:%s | lift: %s front %.2f, low centre %.2f, "
                            + "upslope %.2f, cooled %.2f, sinking %.2f, instability %.2f (%s)",
                    p.getGameProfile().getName(), px, pz, p.getY(), air.t(), air.a(), air.q(), need.rh(),
                    air.surface(), air.elevation(), need.describe(), need.front(), need.frontal(), need.convergence(),
                    need.orographic(), need.wrung(), need.subsidence(), need.instability(), need.heapSource()));
            for (WeatherSystem s : sim.systems()) {
                double d = Math.hypot(s.x - px, s.z - pz);
                if (d < 9000) {
                    line(String.format(Locale.ROOT, "    %s %+.0f %+.0f (%.0f away): %.0f hPa, radius %.0f, %s%s",
                            s.kind == WeatherSystem.Kind.LOW ? "LOW " : "HIGH", s.x - px, s.z - pz, d,
                            1013 + s.signedStrength(), s.radius(), s.stage().label(), s.blocking ? ", blocking" : ""));
                }
            }
            Map<String, int[]> byType = new TreeMap<>();
            Map<String, List<String>> problems = new TreeMap<>();
            for (SimCloud c : sim.cloudSim().clouds()) {
                double cx = c.xAt(now);
                double cz = c.zAt(now);
                if (Math.hypot(cx - px, cz - pz) > NEAR) {
                    continue;
                }
                int[] n = byType.computeIfAbsent(c.type.id, k -> new int[3]);
                n[0]++;
                if (c.precipitation > 0.02) {
                    n[Float.isInfinite(c.rainBottom) ? 1 : 2]++;
                }
                CloudDiagnostics.Air ca = env.air(cx, cz);
                CloudDiagnostics.Need cn = CloudDiagnostics.diagnose(cx, cz, ca, env.systems(), env.dayTime(),
                        env.season());
                for (CloudChecks.Problem pr : CloudChecks.check(c, cn, ca, now)) {
                    problems.computeIfAbsent(pr.code(), k -> new ArrayList<>()).add(String.format(Locale.ROOT,
                            "%s at %+.0f %+.0f (cause: %s): %s", c.type.id, cx - px, cz - pz, c.cause, pr.reason()));
                }
            }
            StringBuilder sb = new StringBuilder("    clouds within 4 km:");
            for (Map.Entry<String, int[]> e : byType.entrySet()) {
                int[] n = e.getValue();
                sb.append(String.format(Locale.ROOT, " %s %d", e.getKey(), n[0]));
                if (n[1] + n[2] > 0) {
                    sb.append(String.format(Locale.ROOT, " (%d rain, %d virga)", n[1], n[2]));
                }
                sb.append(',');
            }
            line(sb.toString());
            if (problems.isEmpty()) {
                line("    rules: all fine");
            }
            for (Map.Entry<String, List<String>> e : problems.entrySet()) {
                line(String.format(Locale.ROOT, "    PROBLEM %s x%d", e.getKey(), e.getValue().size()));
                for (String ex : e.getValue().subList(0, Math.min(4, e.getValue().size()))) {
                    line("      " + ex);
                }
            }
        }
    }

    /** {dx, dz, player index} to the nearest player (index -1 if none). */
    private static double[] nearest(List<ServerPlayer> players, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        double[] out = {0, 0, -1};
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            double d = Math.hypot(x - p.getX(), z - p.getZ());
            if (d < best) {
                best = d;
                out = new double[]{x - p.getX(), z - p.getZ(), i};
            }
        }
        return out;
    }

    private static void line(String s) throws IOException {
        out.write(s);
        out.newLine();
    }

    private CloudAudit() {
    }
}
