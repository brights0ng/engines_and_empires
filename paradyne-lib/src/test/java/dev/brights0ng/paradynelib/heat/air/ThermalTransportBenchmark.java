// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Fixed projected wind and identical restored temperature pulses: transport cost only, no coupled momentum/sources.
 * Run three separate JVMs for repeated evidence. Rejected solves are recorded, never counted as successful work.
 */
public final class ThermalTransportBenchmark {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "build/benchmarks/thermal-transport/fork.json" : args[0]);
        var report = new LinkedHashMap<String, Object>();
        report.put("writtenBy", "ChatGPT"); report.put("schema", "shared-flux-thermal-v1");
        report.put("timeUtc", Instant.now().toString()); report.put("java", System.getProperty("java.runtime.version"));
        report.put("cpu", System.getenv("PROCESSOR_IDENTIFIER")); report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("heapBytes", Runtime.getRuntime().maxMemory());
        report.put("vmArguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("warmup", 5); report.put("samples", 10);
        report.put("scope", "Fixed pressure-projected wind; thermal transport/material exchange only. No sources or two-way buoyancy.");
        var rows = new ArrayList<Object>();
        for (var scene : List.of(new ThermalBenchmark.Scene("multiroom32", 32, 32, 32, 4),
                new ThermalBenchmark.Scene("stress48", 48, 48, 48, 4),
                new ThermalBenchmark.Scene("partial-block-house", 24, 16, 24, 3))) {
            var fixture = ThermalBenchmark.fixture(scene);
            var builder = new FlowGeometry.Builder(scene.nx(), scene.ny(), scene.nz()).openExterior(true);
            var partial = new FlowShape.Builder().solidBox(0, 0, 0, 16, 2, 16).build();
            for (int z = 0; z < scene.nz(); z++) for (int y = 0; y < scene.ny(); y++) for (int x = 0; x < scene.nx(); x++) {
                int i = fixture.index(x, y, z);
                if (fixture.kinds()[i] == AirGrid.SOLID) {
                    builder.cell(x, y, z, fixture.open()[i] != 0 ? partial : FlowShape.FULL);
                }
            }
            var f = builder.build();
            var flow = new FlowGrid(f, FlowBoundary.wind(f, 6, 0, 2), FlowSettings.DEFAULT);
            flow.seedUniform(6, 0, 2);
            var projected = flow.projectInitial();
            if (!projected.ready()) { throw new IllegalStateException(projected.toString()); }
            double[] k = new double[scene.cells()], c = new double[k.length];
            Arrays.fill(k, 1); Arrays.fill(c, 1);
            long setup = System.nanoTime(), setupAllocation = ThermalTiming.allocated();
            var g = new ThermalGeometry(f, k, c, ThermalGeometry.Coefficients.legacyMaterials());
            var t = new ThermalTransport(g, ThermalTransport.Settings.defaults());
            long setupNanos = System.nanoTime() - setup;
            long setupBytes = ThermalTiming.difference(setupAllocation, ThermalTiming.allocated());
            double[] exterior = new double[f.faces()]; Arrays.fill(exterior, 20);
            for (double seconds : new double[]{.25, .5, 1, 2, 10}) {
                var wall = new ThermalTiming.Samples(); var cpu = new ThermalTiming.Samples(); var allocation = new ThermalTiming.Samples();
                int maxSweeps = 0, accepted = 0, rejected = 0, warmupRejected = 0;
                double maxError = 0, maxResidual = 0;
                for (int iteration = 0; iteration < 15; iteration++) {
                    // Restore identical initial state outside timing; do not benchmark convergence toward an empty field.
                    for (int i = 0; i < g.states(); i++) { t.setTemperature(i, 20); }
                    for (int i = 0; i < f.nodes(); i++) {
                        if (Math.abs(f.cx[i] - scene.nx() / 2.0) < 2 && f.cy[i] > 2 && f.cy[i] < 7
                                && Math.abs(f.cz[i] - scene.nz() / 2.0) < 2) { t.setTemperature(i, 60); }
                    }
                    long begin = System.nanoTime(), cpuBefore = ThermalTiming.cpu(), bytes = ThermalTiming.allocated();
                    var result = t.step(seconds, flow, exterior);
                    long elapsed = System.nanoTime() - begin;
                    long cpuNanos = ThermalTiming.difference(cpuBefore, ThermalTiming.cpu());
                    long allocated = ThermalTiming.difference(bytes, ThermalTiming.allocated());
                    if (iteration < 5 && !result.ready()) { warmupRejected++; }
                    if (iteration >= 5) {
                        if (result.ready()) { accepted++; } else { rejected++; }
                        wall.add(elapsed); cpu.add(cpuNanos); allocation.add(allocated);
                        maxSweeps = Math.max(maxSweeps, result.sweeps()); maxError = Math.max(maxError, Math.abs(result.energyError()));
                        maxResidual = Math.max(maxResidual, result.maxTemperatureResidual());
                    }
                }
                var row = new LinkedHashMap<String, Object>(); row.put("scene", scene.name()); row.put("thermalSeconds", seconds);
                row.put("airNodes", f.nodes()); row.put("states", g.states()); row.put("faces", f.faces());
                row.put("thermalSetupMs", setupNanos / 1e6); row.put("thermalSetupAllocatedBytes", setupBytes);
                row.put("geometryFingerprint", Arrays.hashCode(f.area) * 31L + Arrays.hashCode(f.nodeCell));
                row.put("wallMs", wall.summary(1e6)); row.put("cpuMs", cpu.summary(1e6)); row.put("allocatedBytes", allocation.summary(1));
                row.put("maxSweeps", maxSweeps); row.put("maxEnergyError", maxError); row.put("maxTemperatureResidual", maxResidual);
                row.put("accepted", accepted); row.put("rejected", rejected); row.put("warmupRejected", warmupRejected);
                rows.add(row);
                System.out.println(scene.name() + " dt=" + seconds + " ms=" + wall.summary(1e6).mean()
                        + " sweeps=" + maxSweeps + " accepted=" + accepted + " rejected=" + rejected);
            }
        }
        report.put("results", rows); Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("Report: " + output.toAbsolutePath());
    }
}
