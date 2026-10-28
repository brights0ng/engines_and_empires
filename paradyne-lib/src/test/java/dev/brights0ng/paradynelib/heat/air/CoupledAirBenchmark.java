// Written by ChatGPT; extended by ChatGPT with thermal/flow controls, phase counters, and long-run calibration;
// scaling mode, shared scene geometry helper, tiny-pockets scene and hot-still-long variant added by Claude.
// House layout adapted from AirGridTest (original fixture authorship unmarked).
package dev.brights0ng.paradynelib.heat.air;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.StandardCopyOption;

/** Offline two-way source/temperature/momentum evidence. No timing thresholds or gameplay retuning.
 * Run performance in separate JVM forks; calibration retains the accepted legacy house as a control.
 */
public final class CoupledAirBenchmark {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 1 ? args[1] : "performance";
        var thermalMode = args.length > 2 ? ThermalTransport.Mode.valueOf(args[2]) : ThermalTransport.DEFAULT_MODE;
        boolean cacheSamplingOrigins = args.length <= 3 || Boolean.parseBoolean(args[3]);
        var kernel = args.length > 4 ? FlowGrid.Kernel.valueOf(args[4]) : FlowGrid.DEFAULT_KERNEL;
        Path output = Path.of(args.length > 0 ? args[0] : "build/benchmarks/coupled-air/" + mode + ".json");
        var report = new LinkedHashMap<String, Object>();
        System.out.println("Benchmark PID: "+ProcessHandle.current().pid());
        report.put("writtenBy", "ChatGPT"); report.put("schema", "coupled-air-v2"); report.put("mode", mode);
        report.put("thermalTraversal", thermalMode);
        report.put("cachedSamplingOrigins", cacheSamplingOrigins);
        report.put("flowKernel", kernel);
        report.put("timeUtc", Instant.now().toString()); report.put("java", System.getProperty("java.runtime.version"));
        report.put("cpu", System.getenv("PROCESSOR_IDENTIFIER")); report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("heapBytes", Runtime.getRuntime().maxMemory());
        report.put("vmArguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("flowSettings", FlowSettings.DEFAULT.toString()); report.put("thermalSettings", ThermalTransport.Settings.defaults().toString());
        report.put("sequence", "half thermal -> velocity transport/buoyancy/pressure -> half thermal; atomic commit");
        var checkpointRows=new ArrayList<Object>();
        Consumer<Map<String,Object>> checkpoint=row->{
            checkpointRows.add(row);report.put("results",checkpointRows);report.put("complete",false);
            try { save(output,report); } catch(IOException e) { throw new UncheckedIOException(e); }
        };
        if (mode.equals("performance")) { report.put("results", performance(thermalMode, cacheSamplingOrigins, kernel)); }
        else if (mode.equals("scaling")) {
            String sceneFilter=args.length>9?args[9]:"all";report.put("writtenBy","ChatGPT; scaling mode by Claude");
            report.put("scalingScene",sceneFilter);report.put("settingsProfile","DEFAULT");
            report.put("results", scaling(thermalMode, cacheSamplingOrigins, kernel, sceneFilter));
        }
        else if (mode.equals("calibration")) { report.put("results", calibration(thermalMode, cacheSamplingOrigins, kernel)); }
        else if (mode.equals("equilibrium")) {
            String protocol=args.length>5?args[5]:"long";report.put("equilibriumProtocol",protocol);
            double step=args.length>8?Double.parseDouble(args[8]):1;report.put("equilibriumPhysicalStep",step);
            report.put("results", CoupledAirCalibration.run(thermalMode, cacheSamplingOrigins, kernel,CoupledAirCalibration.Protocol.named(protocol),step,checkpoint));
        }
        else if (mode.equals("timesteps")) { report.put("results", CoupledAirQualification.timesteps(thermalMode,cacheSamplingOrigins,kernel,checkpoint)); }
        else if (mode.equals("responses")) {
            double step=args.length>6?Double.parseDouble(args[6]):.5;
            report.put("results", CoupledAirQualification.responses(thermalMode,cacheSamplingOrigins,kernel,step,checkpoint));
        }
        else if (mode.equals("boundaries")) {
            double step=args.length>6?Double.parseDouble(args[6]):.5;
            report.put("results", CoupledAirQualification.boundaries(thermalMode,cacheSamplingOrigins,kernel,step,checkpoint));
        }
        else if (mode.equals("ground")) {
            double step=args.length>6?Double.parseDouble(args[6]):.5;
            report.put("results", CoupledAirQualification.ground(thermalMode,cacheSamplingOrigins,kernel,step,checkpoint));
        }
        else if(mode.equals("qualification-case")) {
            double step=args.length>6?Double.parseDouble(args[6]):.5;
            String specification=args.length>7?args[7]:"0,8,8,3";
            report.put("results",CoupledAirQualification.one(thermalMode,cacheSamplingOrigins,kernel,step,specification));
        }
        else { throw new IllegalArgumentException("Unknown benchmark mode: "+mode); }
        report.put("complete",true);save(output,report);
        System.out.println("Report: " + output.toAbsolutePath());
    }

    private static void save(Path output,Map<String,Object> report) throws IOException {
        Path target=output.toAbsolutePath();Files.createDirectories(target.getParent());
        Path temporary=target.resolveSibling(target.getFileName()+".tmp");
        Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<Object> performance(ThermalTransport.Mode mode, boolean cacheSamplingOrigins, FlowGrid.Kernel kernel) {
        var rows = new ArrayList<Object>();
        for (var scene : List.of(new ThermalBenchmark.Scene("multiroom32", 32, 32, 32, 4),
                new ThermalBenchmark.Scene("stress48", 48, 48, 48, 4),
                new ThermalBenchmark.Scene("partial-block-house", 24, 16, 24, 3))) {
            var g = sceneGeometry(scene, false); var f = g.flow;
            double[] bg = new double[f.nodes()], ext = new double[f.faces()]; Arrays.fill(bg, 20); Arrays.fill(ext, 20);
            for (double[] setting : new double[][]{{.25, 1}, {.5, 1}, {1, 1}, {1, .5}, {1, 2}}) {
                double dt = setting[0], scale = setting[1];
                long setup = System.nanoTime(), setupBytes = ThermalTiming.allocated();
                var grid = new CoupledAirGrid(g, FlowBoundary.wind(f, 6, 0, 2), FlowSettings.DEFAULT, ThermalTransport.Settings.defaults(), mode, cacheSamplingOrigins, kernel);
                for (int i = 0; i < g.states(); i++) { grid.setTemperature(i, 20); }
                var initialized = grid.initialize(6, 0, 2);
                long setupNanos = System.nanoTime() - setup, bytes = ThermalTiming.difference(setupBytes, ThermalTiming.allocated());
                if (!initialized.ready()) { throw new IllegalStateException(initialized.toString()); }
                int source = f.nodeAt(scene.nx() / 2.0 + .5, 3.5, scene.nz() / 2.0 + .5);
                if (source < 0) { throw new IllegalStateException("Fixture source must be represented air"); }
                grid.setSources(new ThermalTransport.Source(source, AirParams.DEFAULT.gridPower(1, .5), 65));
                var wall = new ThermalTiming.Samples(); var cpu = new ThermalTiming.Samples(); var allocation = new ThermalTiming.Samples();
                var thermal = new ThermalTiming.Samples(); var pressure = new ThermalTiming.Samples(); var velocity = new ThermalTiming.Samples();
                var thermalSweeps = new ThermalTiming.Samples(); var thermalQuality = new ThermalTiming.Samples();
                Map<String, Integer> rejected = new LinkedHashMap<>(); int accepted = 0, warmupRejected = 0, iterations = 0, sweeps = 0;
                int checks = 0;
                double error = 0, divergence = 0, progress = 0;
                for (int i = 0; i < 15; i++) {
                    long begin = System.nanoTime(), cpuBefore = ThermalTiming.cpu(), allocationBefore = ThermalTiming.allocated();
                    var result = grid.step(dt, scale, bg, ext);
                    long elapsed = System.nanoTime() - begin, cpuNanos = ThermalTiming.difference(cpuBefore, ThermalTiming.cpu());
                    long allocated = ThermalTiming.difference(allocationBefore, ThermalTiming.allocated());
                    if (result.ready()) { progress += dt; }
                    if (i < 5) { if (!result.ready()) { warmupRejected++; } continue; }
                    wall.add(elapsed); cpu.add(cpuNanos); allocation.add(allocated);
                    if (!result.ready()) { rejected.merge(result.status().name(), 1, Integer::sum); continue; }
                    accepted++; error = Math.max(error, Math.abs(result.energyError()));
                    divergence = Math.max(divergence, result.flow().pressure().maxDivergence());
                    iterations = Math.max(iterations, result.flow().pressure().iterations());
                    sweeps = Math.max(sweeps, Math.max(result.first().sweeps(), result.second().sweeps()));
                    checks = Math.max(checks, Math.max(result.first().residualChecks(), result.second().residualChecks()));
                    thermalSweeps.add(result.first().sweepNanos() + result.second().sweepNanos());
                    thermalQuality.add(result.first().qualityNanos() + result.second().qualityNanos());
                    thermal.add(result.first().nanos() + result.second().nanos()); pressure.add(result.flow().pressureNanos()); velocity.add(result.flow().transportNanos());
                }
                var row = new LinkedHashMap<String, Object>(); row.put("scene", scene.name()); row.put("physicalStep", dt); row.put("responseScale", scale);
                row.put("warmupSteps", 5); row.put("measuredSteps", 10); row.put("accepted", accepted); row.put("rejected", rejected); row.put("warmupRejected", warmupRejected);
                row.put("completedPhysicalSecondsIncludingWarmup", progress); row.put("sourcePower", 75); row.put("sourceLimitC", 65);
                row.put("airNodes", f.nodes()); row.put("states", g.states()); row.put("faces", f.faces());
                row.put("geometryFingerprint", Arrays.hashCode(f.area) * 31L + Arrays.hashCode(f.nodeCell));
                row.put("ownerConstructionAndInitializationMs", setupNanos / 1e6); row.put("ownerConstructionAndInitializationAllocatedBytes", bytes);
                row.put("wallMs", wall.summary(1e6)); row.put("cpuMs", cpu.summary(1e6)); row.put("allocatedBytes", allocation.summary(1));
                row.put("acceptedThermalMs", thermal.summary(1e6)); row.put("acceptedPressureMs", pressure.summary(1e6)); row.put("acceptedVelocityMs", velocity.summary(1e6));
                row.put("acceptedThermalSweepsMs", thermalSweeps.summary(1e6)); row.put("acceptedThermalQualityMs", thermalQuality.summary(1e6));
                row.put("maxThermalHalfChecks", checks);
                row.put("maxPressureIterations", iterations); row.put("maxThermalHalfSweeps", sweeps); row.put("maxEnergyError", error); row.put("maxDivergence", divergence);
                rows.add(row); System.out.println(scene.name() + " dt=" + dt + " scale=" + scale + " accepted=" + accepted
                        + " rejected=" + rejected + " ms=" + wall.summary(1e6).mean());
            }
        }
        return rows;
    }

    /** ThermalBenchmark fixture as an open-exterior flow domain; partial solids are a 16x2x16 bottom slab.
     * When notched, every full solid face-touching interior (AIR) cells is full except one 1/16 subvoxel at local (0,0,0).
     */
    private static ThermalGeometry sceneGeometry(ThermalBenchmark.Scene scene, boolean notched) {
        var fixture = ThermalBenchmark.fixture(scene);
        var builder = new FlowGeometry.Builder(scene.nx(), scene.ny(), scene.nz()).openExterior(true);
        var partial = new FlowShape.Builder().solidBox(0, 0, 0, 16, 2, 16).build();
        var notch = new FlowShape.Builder().solidBox(1, 0, 0, 16, 16, 16).solidBox(0, 1, 0, 1, 16, 16).solidBox(0, 0, 1, 1, 1, 16).build();
        for (int z = 0; z < scene.nz(); z++) for (int y = 0; y < scene.ny(); y++) for (int x = 0; x < scene.nx(); x++) {
            int i = fixture.index(x, y, z);
            if (fixture.kinds()[i] != AirGrid.SOLID) { continue; }
            var shape = fixture.open()[i] != 0 ? partial : notched && touchesInterior(fixture, x, y, z) ? notch : FlowShape.FULL;
            builder.cell(x, y, z, shape);
        }
        var f = builder.build(); double[] k = new double[scene.cells()], c = new double[k.length]; Arrays.fill(k, 1); Arrays.fill(c, 1);
        return new ThermalGeometry(f, k, c, ThermalGeometry.Coefficients.legacyMaterials());
    }

    private static boolean touchesInterior(ThermalBenchmark.Fixture fixture, int x, int y, int z) {
        var s = fixture.scene();
        int[][] offsets = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int[] o : offsets) {
            int px = x + o[0], py = y + o[1], pz = z + o[2];
            if (px < 0 || py < 0 || pz < 0 || px >= s.nx() || py >= s.ny() || pz >= s.nz()) { continue; }
            if (fixture.kinds()[fixture.index(px, py, pz)] == AirGrid.AIR) { return true; }
        }
        return false;
    }

    private static double maxSpeed(CoupledAirGrid grid, FlowGeometry f) {
        double max = 0;
        for (int e = 0; e < f.faces(); e++) {
            if (f.faceArea(e) > 0) { max = Math.max(max, Math.abs(grid.faceFlux(e) / f.faceArea(e))); }
        }
        return max;
    }

    /** seriesEvery &gt; 0 records max speed and source temperature every that many measured steps. */
    private record Variant(String name, double windX, double windZ, double sourcePower, double sourceLimitC, int measured, int seriesEvery) { }

    /** Phase 0 baseline: cost and acceptance against domain size with DEFAULT settings.
     * Rejected steps keep the previous state, so stepping continues after them.
     * tiny-pockets is multiroom16 with notched room-facing solids. open16 runs only hot-still-long, and only when named;
     * multiroom16 adds hot-still-long only when named.
     */
    private static List<Object> scaling(ThermalTransport.Mode mode, boolean cacheSamplingOrigins, FlowGrid.Kernel kernel, String sceneFilter) {
        var rows = new ArrayList<Object>();
        int warmup = 10; double dt = .5, scale = 1;
        var variants = List.of(new Variant("wind-only", 6, 2, 0, Double.NaN, 40, 0),
                new Variant("heated", 6, 2, AirParams.DEFAULT.gridPower(1, .5), 65, 40, 0),
                new Variant("hot-still", 0, 0, 300, 400, 40, 0));
        var hotStillLong = new Variant("hot-still-long", 0, 0, 300, 400, 1200, 20);
        var multiroom16 = new ThermalBenchmark.Scene("multiroom16", 16, 16, 16, 4);
        for (var scene : List.of(multiroom16,
                new ThermalBenchmark.Scene("multiroom32x16", 32, 16, 32, 4),
                new ThermalBenchmark.Scene("multiroom32", 32, 32, 32, 4),
                new ThermalBenchmark.Scene("partial-block-house", 24, 16, 24, 3),
                new ThermalBenchmark.Scene("tiny-pockets", 16, 16, 16, 4),
                new ThermalBenchmark.Scene("open16", 16, 16, 16, 0))) {
            boolean named = sceneFilter.equals(scene.name()), longOnly = scene.name().equals("open16");
            if (!named && (longOnly || !sceneFilter.equals("all"))) { continue; }
            boolean pockets = scene.name().equals("tiny-pockets");
            var g = pockets ? sceneGeometry(multiroom16, true) : sceneGeometry(scene, false); var f = g.flow;
            double[] bg = new double[f.nodes()], ext = new double[f.faces()]; Arrays.fill(bg, 20); Arrays.fill(ext, 20);
            int source = f.nodeAt(scene.nx() / 2.0 + .5, 3.5, scene.nz() / 2.0 + .5);
            if (source < 0) { throw new IllegalStateException("Fixture source must be represented air"); }
            double minNodeVolume = Double.POSITIVE_INFINITY; int smallNodes = 0;
            for (int i = 0; i < f.nodes(); i++) {
                minNodeVolume = Math.min(minNodeVolume, f.airVolume(i)); if (f.airVolume(i) < 1.0 / 64) { smallNodes++; }
            }
            var sceneVariants = new ArrayList<Variant>(longOnly ? List.of() : variants);
            if (named && (longOnly || scene == multiroom16)) { sceneVariants.add(hotStillLong); }
            for (var variant : sceneVariants) {
                int measured = variant.measured();
                long setup = System.nanoTime();
                var grid = new CoupledAirGrid(g, FlowBoundary.wind(f, variant.windX(), 0, variant.windZ()), FlowSettings.DEFAULT,
                        ThermalTransport.Settings.defaults(), mode, cacheSamplingOrigins, kernel);
                for (int i = 0; i < g.states(); i++) { grid.setTemperature(i, 20); }
                var initialized = grid.initialize(variant.windX(), 0, variant.windZ());
                long setupNanos = System.nanoTime() - setup;
                if (variant.sourcePower() > 0) { grid.setSources(new ThermalTransport.Source(source, variant.sourcePower(), variant.sourceLimitC())); }
                var wall = new ThermalTiming.Samples(); var thermal = new ThermalTiming.Samples();
                var pressure = new ThermalTiming.Samples(); var transport = new ThermalTiming.Samples();
                var iterations = new ThermalTiming.Samples(); var sweeps = new ThermalTiming.Samples();
                Map<String, Integer> rejected = new LinkedHashMap<>(); int accepted = 0, warmupRejected = 0, firstRejected = -1;
                double maxSpeed = 0, divergence = 0, error = 0; var speedSeries = new ArrayList<Object>();
                for (int s = 0; s < warmup + measured; s++) {
                    long begin = System.nanoTime();
                    var step = grid.step(dt, scale, bg, ext);
                    long elapsed = System.nanoTime() - begin;
                    if (!step.ready() && firstRejected < 0) { firstRejected = s; }
                    if (s < warmup) { if (!step.ready()) { warmupRejected++; } continue; }
                    var flow = step.flow();
                    wall.add(elapsed);
                    int measuredStep = s - warmup + 1;
                    if (variant.seriesEvery() > 0 && measuredStep % variant.seriesEvery() == 0) {
                        var sample = new LinkedHashMap<String, Object>(); sample.put("measuredStep", measuredStep);
                        sample.put("measuredPhysicalSeconds", measuredStep * dt); sample.put("ready", step.ready());
                        sample.put("maxSpeed", maxSpeed(grid, f)); sample.put("sourceTemperatureC", grid.temperature(source));
                        speedSeries.add(sample);
                    }
                    if (step.first() != null) {
                        thermal.add(step.first().nanos() + (step.second() != null ? step.second().nanos() : 0));
                        sweeps.add(step.first().sweeps() + (step.second() != null ? step.second().sweeps() : 0));
                    }
                    if (flow != null) {
                        pressure.add(flow.pressureNanos()); transport.add(flow.transportNanos());
                        if (flow.pressure() != null) { iterations.add(flow.pressure().iterations()); }
                    }
                    if (!step.ready()) {
                        String key = step.status() + (flow != null ? "/" + flow.status() : "")
                                + (flow != null && flow.pressure() != null ? "/" + flow.pressure().status() : "");
                        rejected.merge(key, 1, Integer::sum); continue;
                    }
                    accepted++;
                    maxSpeed = Math.max(maxSpeed, maxSpeed(grid, f));
                    divergence = Math.max(divergence, flow.pressure().maxDivergence());
                    error = Math.max(error, Math.abs(step.energyError()));
                }
                double[] temperatures = new double[g.states()], flux = new double[f.faces()];
                for (int i = 0; i < temperatures.length; i++) { temperatures[i] = grid.temperature(i); }
                for (int e = 0; e < flux.length; e++) { flux[e] = grid.faceFlux(e); }
                Double rebuildMs = null; int rebuildIterations = -1; String rebuildStatus;
                try {
                    long rebuild = System.nanoTime();
                    var next = new CoupledAirGrid(g, FlowBoundary.wind(f, variant.windX(), 0, variant.windZ()), FlowSettings.DEFAULT,
                            ThermalTransport.Settings.defaults(), mode, cacheSamplingOrigins, kernel);
                    var mapped = GeometryTransfer.map(g, g, temperatures, flux, 20).initialize(next);
                    rebuildMs = (System.nanoTime() - rebuild) / 1e6;
                    rebuildStatus = mapped.status() + (mapped.pressure() != null ? "/" + mapped.pressure().status() : "");
                    if (mapped.pressure() != null) { rebuildIterations = mapped.pressure().iterations(); }
                } catch (IllegalArgumentException e) { rebuildStatus = "REJECTED: " + e.getMessage(); }
                var row = new LinkedHashMap<String, Object>(); row.put("scene", scene.name()); row.put("cells", scene.cells());
                row.put("airNodes", f.nodes()); row.put("states", g.states()); row.put("faces", f.faces()); row.put("variant", variant.name());
                if (pockets) { row.put("baseScene", multiroom16.name()); }
                row.put("minNodeVolume", minNodeVolume); row.put("smallNodes", smallNodes);
                row.put("sourcePower", variant.sourcePower()); if (variant.sourcePower() > 0) { row.put("sourceLimitC", variant.sourceLimitC()); }
                row.put("physicalStep", dt); row.put("responseScale", scale); row.put("warmupSteps", warmup); row.put("measuredSteps", measured);
                row.put("initialization", initialized.status() + (initialized.pressure() != null ? "/" + initialized.pressure().status() : ""));
                row.put("accepted", accepted); row.put("rejectedByStatus", rejected); row.put("warmupRejected", warmupRejected);
                row.put("firstRejectedStep", firstRejected);
                row.put("wallMs", wall.summary(1e6)); row.put("thermalMs", thermal.summary(1e6)); row.put("pressureMs", pressure.summary(1e6));
                row.put("transportMs", transport.summary(1e6)); row.put("pressureIterations", iterations.summary(1));
                row.put("thermalSweeps", sweeps.summary(1));
                row.put("maxSpeed", maxSpeed); row.put("maxDivergence", divergence); row.put("maxEnergyError", error);
                row.put("ownerConstructionAndInitializationMs", setupNanos / 1e6);
                row.put("rebuildMs", rebuildMs); row.put("rebuildPressureIterations", rebuildIterations); row.put("rebuildStatus", rebuildStatus);
                row.put("geometryFingerprint", Arrays.hashCode(f.area) * 31L + Arrays.hashCode(f.nodeCell));
                if (variant.seriesEvery() > 0) { row.put("speedSeriesEvery", variant.seriesEvery()); row.put("speedSeries", speedSeries); }
                rows.add(row); System.out.println(scene.name() + " " + variant.name() + " accepted=" + accepted + " rejected=" + rejected
                        + " ms=" + wall.summary(1e6).mean() + " rebuildMs=" + rebuildMs);
            }
        }
        return rows;
    }

    record House(AirGrid legacy, FlowGeometry flow, ThermalGeometry thermal, int x0, int x1, int y0, int y1, int mid) {
        double legacyMean() { return legacy.meanAirOffset(x0 + 1, y0 + 1, x0 + 1, x1 - 1, y1 - 1, x1 - 1); }
        double mean(CoupledAirGrid grid) {
            double sum = 0; int count = 0;
            for (int z = x0 + 1; z < x1; z++) for (int y = y0 + 1; y < y1; y++) for (int x = x0 + 1; x < x1; x++) {
                sum += grid.temperature(flow.nodeAt(x + .5, y + .5, z + .5)) - 20; count++;
            }
            return sum / count;
        }
        double materialMean(CoupledAirGrid grid) {
            double sum = 0; int count = 0;
            for (int z = x0; z <= x1; z++) for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                int state = thermal.materialState(flow.index(x, y, z));
                if (state >= 0) { sum += grid.temperature(state) - 20; count++; }
            }
            return sum / count;
        }
    }

    /** Same 10x10x5 block layout/materials/virtual air source as the accepted AirGridTest house.
     * The coupled model has a pressure-open exterior rather than the legacy zero-offset outer cell layer.
     */
    static House house(int opening) {
        return house(opening,6,6);
    }

    /** Padding is air cells between the house/roof and the outermost cell layer; the house itself is unchanged. */
    static House house(int opening,int padding,int topPadding) {
        return house(opening,padding,topPadding,3);
    }

    static House house(int opening,int padding,int topPadding,int groundDepth) {
        if(opening<0 || opening>2 || padding<1 || topPadding<1 || groundDepth<1) { throw new IllegalArgumentException("Invalid house fixture"); }
        int nx = Math.addExact(12,Math.multiplyExact(2,padding)), ny = Math.addExact(Math.addExact(7,groundDepth),topPadding);
        int x0 = padding+1, x1 = x0+9, y0 = groundDepth+1, y1 = y0+4, mid = x0+4;
        var legacy = new AirGrid(nx, ny, nx, AirParams.DEFAULT); var builder = new FlowGeometry.Builder(nx, ny, nx).openExterior(true);
        double[] k = new double[nx * ny * nx], c = new double[k.length]; Arrays.fill(k, 1); Arrays.fill(c, 1);
        for (int z = 1; z < nx - 1; z++) for (int y = 1; y < ny - 1; y++) for (int x = 1; x < nx - 1; x++) {
            boolean inside = x >= x0 && x <= x1 && z >= x0 && z <= x1 && y <= y1;
            boolean solid = y <= groundDepth || inside && (x == x0 || x == x1 || z == x0 || z == x1 || y == y0 || y == y1);
            boolean door = x == mid && z == x0 && (y == y0 + 1 || y == y0 + 2);
            boolean gap = opening == 1 && x == x1 && y == y0 + 2 && z == mid
                    || opening == 2 && x == x1 && y > y0 && y < y1 && z > x0 && z < x1;
            int cell = (z * ny + y) * nx + x;
            if ((solid || door) && !gap) {
                k[cell] = y <= groundDepth ? .6 : door ? .25 : 1; c[cell] = y <= groundDepth ? .8 : door ? .5 : 1;
                builder.cell(x, y, z, FlowShape.FULL); legacy.setSolid(x, y, z, k[cell], c[cell], 0);
            } else { legacy.setAir(x, y, z, !inside); }
        }
        legacy.addSource(mid, y0 + 1, mid, AirParams.DEFAULT.gridPower(1, .5), Double.NaN);
        var f = builder.build(); return new House(legacy, f, new ThermalGeometry(f, k, c, ThermalGeometry.Coefficients.legacyMaterials()), x0, x1, y0, y1, mid);
    }

    private static List<Object> calibration(ThermalTransport.Mode mode, boolean cacheSamplingOrigins, FlowGrid.Kernel kernel) {
        var rows = new ArrayList<Object>();
        for (int opening = 0; opening < 3; opening++) {
            var h = house(opening); var equilibrium = house(opening); equilibrium.legacy.settle(3000);
            double target = equilibrium.legacyMean();
            var grid = new CoupledAirGrid(h.thermal, FlowBoundary.wind(h.flow, 0, 0, 0), FlowSettings.DEFAULT, ThermalTransport.Settings.defaults(), mode, cacheSamplingOrigins, kernel);
            for (int i = 0; i < h.thermal.states(); i++) { grid.setTemperature(i, 20); }
            if (!grid.initialize(0, 0, 0).ready()) { throw new IllegalStateException("House initialization failed"); }
            grid.setSources(new ThermalTransport.Source(h.flow.nodeAt(h.mid + .5, h.y0 + 1.5, h.mid + .5), 75, Double.NaN));
            double[] bg = new double[h.flow.nodes()], ext = new double[h.flow.faces()]; Arrays.fill(bg, 20); Arrays.fill(ext, 20);
            var samples = new ArrayList<Object>(); var timing = new ThermalTiming.Samples();
            String failure = ""; int completed = 0, legacy95 = -1, coupled95LegacyTarget = -1;
            double energyStart = grid.energy(), sourceEnergy = 0, exteriorEnergy = 0, maxError = 0;
            for (int second = 1; second <= 680; second++) {
                if (second == 601) { h.legacy.clearSources(); grid.setSources(); }
                var result = grid.step(1, 1, bg, ext);
                if (!result.ready()) { failure = result.toString(); break; }
                h.legacy.step(1); completed = second; timing.add(result.nanos());
                sourceEnergy += result.sourceEnergy(); exteriorEnergy += result.exteriorEnergy(); maxError = Math.max(maxError, Math.abs(result.energyError()));
                if (second <= 600 && legacy95 < 0 && h.legacyMean() >= .95 * target) { legacy95 = second; }
                if (second <= 600 && coupled95LegacyTarget < 0 && h.mean(grid) >= .95 * target) { coupled95LegacyTarget = second; }
                if (second == 15 || second == 60 || second == 120 || second == 240 || second == 300 || second == 360
                        || second == 600 || second == 640 || second == 680) {
                    var sample = new LinkedHashMap<String, Object>(); sample.put("physicalSecond", second);
                    sample.put("legacyMeanOffsetC", h.legacyMean()); sample.put("coupledMeanOffsetC", h.mean(grid));
                    sample.put("coupledMeanMaterialOffsetC", h.materialMean(grid));
                    sample.put("legacyNearSourceOffsetC", h.legacy.offset(h.mid + 1, h.y0 + 1, h.mid));
                    sample.put("coupledNearSourceOffsetC", grid.temperature(h.flow.nodeAt(h.mid + 1.5, h.y0 + 1.5, h.mid + .5)) - 20);
                    samples.add(sample); System.out.println("house opening=" + opening + " second=" + second
                            + " legacy=" + h.legacyMean() + " coupled=" + h.mean(grid));
                }
            }
            var row = new LinkedHashMap<String, Object>(); row.put("opening", List.of("sealed", "window", "missing-wall").get(opening));
            row.put("sourcePower", 75); row.put("sourceLimit", "unlimited, matching AirGridTest calibration fixture");
            row.put("physicalStep", 1); row.put("responseScale", 1); row.put("completedPhysicalSeconds", completed); row.put("firstFailure", failure);
            row.put("legacyEquilibriumOffsetC", target); row.put("legacySecondsTo95Percent", legacy95);
            row.put("coupledSecondsTo95PercentOfLegacyEquilibrium", coupled95LegacyTarget);
            row.put("coupledEquilibriumQualified", false); row.put("samples", samples); row.put("stepWallMs", timing.summary(1e6));
            row.put("maxStepEnergyError", maxError); row.put("totalEnergyBudgetError", grid.energy() - energyStart - sourceEnergy - exteriorEnergy);
            rows.add(row);
        }
        return rows;
    }
}
