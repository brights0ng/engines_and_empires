package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import jdk.jfr.Recording;

/**
 * Replays a sky saved in game ({@code /eae clouds dump} writes {@code run/cloud-dump.txt}) through the mesher's own path
 * (a field per section, lit and shaded, the coarse cull, then the build) and reports the CPU time it takes, per
 * formation and per step, to {@code build/cloud-bench.txt}, with a profile in {@code build/cloud-bench.jfr}. Runs only
 * with {@code EAE_BENCH=1} set, so ordinary test runs stay quick. Every planned section is built (the mesher's
 * remembered empty sections aren't modelled): the time is what a generation costs with nothing skipped.
 */
class CloudBenchmarkTest {

    private static final Path DUMP = Path.of("run", "cloud-dump.txt");
    private static final int RUNS = 3;

    @Test
    void benchmarkDumpedSky() throws Exception {
        assumeTrue("1".equals(System.getenv("EAE_BENCH")), "set EAE_BENCH=1 to run the cloud benchmark");
        assumeTrue(Files.exists(DUMP), "no " + DUMP + ": take one in game with /eae clouds dump");
        String cache = System.getenv("EAE_BENCH_COLUMNS");
        if (cache != null) {
            CloudVoxelizer.COLUMN_CACHE = Integer.parseInt(cache);
        }
        CloudDump.Snapshot sky = CloudDump.read(DUMP);
        List<CloudShape> all = new ArrayList<>();
        for (CloudDump.Entry e : sky.entries()) {
            all.addAll(e.formation().members());
        }
        CloudShadows.update(all, sky.time());
        CloudShadows shadows = CloudShadows.current();
        CloudLight light = CloudLight.bakeAt(0.15);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        Recording rec = new Recording();
        rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5));
        rec.enable("jdk.ObjectAllocationSample").with("throttle", "300/s");
        StringBuilder out = new StringBuilder();
        out.append(String.format("Cloud benchmark: %d formations, voxel %d, section %d, draw %.0f, column cache %d%n",
                sky.entries().size(), sky.voxel(), CloudTuning.sectionSize, sky.drawDistance(),
                CloudVoxelizer.COLUMN_CACHE));
        for (int run = 0; run < RUNS; run++) {
            if (run == RUNS - 1) {
                rec.start(); // profile the last (warmed-up) run only
            }
            Map<String, double[]> byType = new TreeMap<>();
            List<String> slowest = new ArrayList<>();
            List<double[]> slowestMs = new ArrayList<>();
            double total = 0, totalField = 0, totalCull = 0;
            int built = 0, empty = 0;
            for (CloudDump.Entry e : sky.entries()) {
                CloudFormation f = e.formation();
                String only = System.getenv("EAE_BENCH_ONLY");
                if (only != null && !only.isBlank() && !f.anchor().typeId().equals(only)) {
                    continue;
                }
                CloudField bounds = CloudField.of(f);
                if (bounds == null) {
                    continue;
                }
                List<CloudMeshes.Planned> plan = RebuildSchedule.plan(bounds.bounds(), e.camX(), e.camY(), e.camZ(),
                        CloudTuning.sectionSize, sky.voxel(), sky.drawDistance());
                long c0 = threads.getCurrentThreadCpuTime();
                List<CloudMeshes.Planned> kept = new ArrayList<>();
                for (CloudMeshes.Planned p : plan) {
                    if (CloudVoxelizer.sectionMayHaveCloud(bounds, p.voxel(), sky.time(), p.sx(), p.sy(), p.sz(),
                            CloudTuning.sectionSize)) {
                        kept.add(p);
                    }
                }
                double cull = (threads.getCurrentThreadCpuTime() - c0) / 1e6;
                double[] t = byType.computeIfAbsent(f.anchor().typeId(), k -> new double[7]);
                for (CloudMeshes.Planned p : kept) {
                    long a = threads.getCurrentThreadCpuTime();
                    // As CloudMeshes.build: a fresh field per section, lit and shaded.
                    CloudField field = CloudField.of(f);
                    field.withLight(light.x(), light.y(), light.z(), light.strength());
                    field.withShadows(shadows, f, sky.time());
                    long b = threads.getCurrentThreadCpuTime();
                    int[] quads = new int[1];
                    CloudVoxelizer.debugCount = run == 0;
                    CloudVoxelizer.debugDistinct.clear();
                    long fillsBefore = CloudVoxelizer.debugColumnFills;
                    CloudVoxelizer.Result r = CloudVoxelizer.buildSection(field, p.voxel(), sky.time(), p.sx(), p.sy(),
                            p.sz(), CloudTuning.sectionSize, (x, y, z, argb) -> quads[0]++);
                    if (run == 0) {
                        t[5] += CloudVoxelizer.debugColumnFills - fillsBefore;
                        t[6] += CloudVoxelizer.debugDistinct.size();
                    }
                    long c = threads.getCurrentThreadCpuTime();
                    double fieldMs = (b - a) / 1e6, buildMs = (c - b) / 1e6;
                    t[0] += fieldMs + buildMs;
                    t[1] += fieldMs;
                    t[2]++;
                    t[4] += r.quads();
                    total += fieldMs + buildMs;
                    totalField += fieldMs;
                    built++;
                    if (r.quads() == 0) {
                        empty++;
                        t[3]++;
                    }
                    slowest.add(String.format("%s section %d,%d,%d voxel %d: field %.1f ms, build %.1f ms, %d quads",
                            f.anchor().typeId(), p.sx(), p.sy(), p.sz(), p.voxel(), fieldMs, buildMs, r.quads()));
                    slowestMs.add(new double[]{fieldMs + buildMs, slowest.size() - 1});
                }
                t[0] += cull;
                total += cull;
                totalCull += cull;
            }
            out.append(String.format("%nRun %d: %.0f ms CPU for %d sections (%d empty): fields %.0f ms, culls %.0f ms,"
                    + " builds %.0f ms%n", run, total, built, empty, totalField, totalCull,
                    total - totalField - totalCull));
            for (Map.Entry<String, double[]> t : byType.entrySet()) {
                double[] v = t.getValue();
                out.append(String.format("  %-26s %8.0f ms, %4.0f sections (%3.0f empty), fields %6.0f ms, %8.0f quads%s%n",
                        t.getKey(), v[0], v[2], v[3], v[1], v[4], run == 0 ? String.format(
                                ", shading columns %.0f filled for %.0f distinct (%.1fx)", v[5], v[6],
                                v[6] == 0 ? 0 : v[5] / v[6]) : ""));
            }
            if (run == RUNS - 1) {
                slowestMs.sort((x, y) -> Double.compare(y[0], x[0]));
                out.append("  Slowest sections:\n");
                for (int i = 0; i < Math.min(12, slowestMs.size()); i++) {
                    out.append("    ").append(slowest.get((int) slowestMs.get(i)[1])).append('\n');
                }
            }
        }
        rec.stop();
        Files.createDirectories(Path.of("build"));
        rec.dump(Path.of("build", "cloud-bench.jfr"));
        rec.close();
        Files.writeString(Path.of("build", "cloud-bench.txt"), out.toString());
        System.out.print(out);
    }
}
