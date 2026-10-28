// Updated by ChatGPT: retained static block/shape/fluid edit handoffs with explicit material ownership.
// Updated by ChatGPT: reported natural thermal initialization quality.
// Updated by ChatGPT: automatic thermal initialization, versioned chunk persistence and retained natural estimates.
// Instrumented by ChatGPT: opt-in thermal baseline capture without changing simulation scheduling.
// Updated by ChatGPT: expose immutable consumer queries with explicit freshness and availability.
// Updated by ChatGPT: classified invalidation, cached shelter reuse and snapshot revision telemetry.
// Updated by Claude: reported manual local-air restart, failure, topology and weather churn counters.
// Updated by ChatGPT: coalesced local geometry edits and budgeted snapshot preparation across ticks.
// Updated by ChatGPT: integrated thermal response time separately from elapsed game time.
// Updated by ChatGPT: opt-in local-air geometry preparation on shared capture and worker budgets.
// Updated by ChatGPT: explicit cold-start coupled diagnostics with frozen source/weather inputs.
// Updated by ChatGPT: chronological campfire source refresh with retained diagnostic heat and flow.
// Updated by ChatGPT: request-local block/chunk dependency invalidation, including off-thread hooks.
// Updated by ChatGPT: startup telemetry and prepaid continuation credit on the shared worker pool.
// Updated by ChatGPT: routed explicit manual/automatic view selections without creating work.
// Updated by ChatGPT: checked recovery tile availability without loading chunks.
// Updated by ChatGPT: shared bounded sky indexes with immediate block/chunk invalidation.
// Updated by Claude: classified server-thread edits before invalidating owners and filtered sky-irrelevant edits.
// Updated by Claude: indexed placed sources for local-air demand in any state, beside the legacy placed index.
// Updated by Claude: tagged local-air revision bumps by call site and reported re-read and revision-site counters.
// Updated by Claude: compared sky relevance in local air's snow buckets; reported geometry edits, edit phases, post-capture re-reads and replans.
package dev.brights0ng.meteodyne.heat.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.Temperature;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.meteodyne.ships.Ships;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.heat.air.AirGrid;
import dev.brights0ng.paradynelib.heat.air.AirParams;
import dev.brights0ng.paradynelib.heat.air.ThermalClock;
import dev.brights0ng.paradynelib.heat.air.HeatClusters;
import dev.brights0ng.paradynelib.wind.Shelter;
import dev.brights0ng.paradynelib.wind.WindColumn;
import dev.brights0ng.paradynelib.wind.WindConfig;
import dev.brights0ng.paradynelib.wind.WindParams;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The heat simulation in the Overworld (weather phase 8f-2): which blocks are sources, the regions around them, the
 * budgeted thread that steps them, and what is saved with the chunks. See {@code claude/weather-backbone-phase8f.md}.
 *
 * <ul>
 *   <li><b>Placed sources</b> (campfires, furnaces, burners...) are indexed per loaded chunk, from a scan when the
 *       chunk loads and from every block change after ({@code LevelChunkHeatMixin}).</li>
 *   <li><b>Natural sources</b> (lava, magma, fire) are looked for only near players and ships, and inside the boxes
 *       placed sources make (Bright's decisions 11 and 13).</li>
 *   <li><b>Plan</b> (once a second): the sources are grouped into regions ({@link HeatClusters}); a region whose box
 *       is unchanged carries on; a new one starts from the fields it replaces, or from what was saved; regions made of
 *       natural sources alone start at their steady state and are dropped when nobody is near; regions of placed
 *       sources whose sources went out cool down until nothing is left, then go.</li>
 *   <li><b>Steps</b> run on worker threads under a CPU budget ({@code [heat] budgetCores}); each advances its region
 *       by the game time since its last step: every second near a player or ship, every ten seconds otherwise.</li>
 *   <li><b>Saving</b>: every 30 s, when a chunk unloads and when the server stops, each saved region's offsets go into
 *       its chunks ({@link HeatChunkData}).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class HeatWorld {

    /** Ticks between plans. */
    static final int PLAN_INTERVAL = 20;
    /** Ticks between saves. */
    static final int PERSIST_INTERVAL = 600;
    /** Ticks a chunk's natural sources are kept before they are looked for again. */
    static final int NATURAL_TTL = 100;
    /** Simulated seconds a region of natural sources is run for to reach its steady state at the start. */
    static final double SETTLE_SECONDS = 1500;
    /** The longest a single job advances a region, s (a region left long behind catches up only this much). */
    static final double MAX_CATCH_UP = 120;
    /** Blocks around a region within which a player or ship counts as near it. */
    static final int NEAR = 16;
    /** A source-less region whose field is nowhere stronger than this, C, is dropped. */
    static final double FADED = 0.2;

    private static final Map<ServerLevel, HeatWorld> WORLDS = new WeakHashMap<>();
    private static ExecutorService pool;
    private static int poolThreads;
    /** Active diagnostic requests only. Bump before marshalling off-thread edits to the server. */
    private static final java.util.concurrent.ConcurrentHashMap<ServerLevel,LocalAirDependencies> LOCAL_AIR_EPOCHS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ServerLevel,List<LocalAirDependencies>> AUTOMATIC_AIR_EPOCHS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ServerLevel,LocalAirSkyCache> LOCAL_AIR_SKY = new java.util.concurrent.ConcurrentHashMap<>();

    /** The simulation of {@code level} (made on first use), or null if it isn't the Overworld. */
    public static HeatWorld of(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) {
            return null;
        }
        return WORLDS.computeIfAbsent(level, HeatWorld::new);
    }

    /** One source found in the world. */
    record Found(int x, int y, int z, BlockState state, HeatPowerData.Variant variant) {
    }

    private record Naturals(long tick, List<Found> found) {
    }

    private record Job(HeatRegion region, HeatRegion.Geometry geometry, float[] initial, boolean settle,
                       List<AirGrid.Source> sources, double windX, double windZ, byte[] cover, double roofWeight,
                       double seconds, long tick, HeatBenchmark.Token profiling,
                       long geometryRevision, long materialRevision, long sourceRevision,
                       double gameSeconds, double responseScale) {
    }

    private record Done(HeatRegion region, HeatRegion.Published published, long nanos, HeatBenchmark.JobMeasurement measurement) {
    }

    final ServerLevel level;
    private final LocalAirDemands airDemands;
    private LocalAirPlanner airPlanner;
    private LocalAirAutomatic automaticAir;
    private long nextAirPlanTick;
    final AirParams params = AirParams.DEFAULT;
    /** Placed sources per chunk: position ({@code BlockPos.asLong}) to state. */
    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> placed = new Long2ObjectOpenHashMap<>();
    /** Blocks that request local-air coverage in any state, lit or not, per chunk. Kept beside {@link #placed}. */
    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> placedAir = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Naturals> naturals = new Long2ObjectOpenHashMap<>();
    /** Loaded chunks holding saved region data. */
    private final LongOpenHashSet savedChunks = new LongOpenHashSet();
    private List<HeatRegion> regions = new ArrayList<>();
    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();
    private int nextId = 1;
    private double budgetMs;
    private final HeatBenchmark benchmark = new HeatBenchmark();
    private long tableRevision = HeatTables.revision();
    private WindParams shelterParams = WindConfig.params();
    private final ThermalClock thermalClock = new ThermalClock((long) (MAX_CATCH_UP * 20));
    private Double responseOverride;
    private record InputPlan(List<Found> sources, List<Vec3> anchors, ShipAir air) { }
    private final Map<HeatRegion, InputPlan> inputPlans = new IdentityHashMap<>();
    /** Shared snapshot target; a row/column is atomic, so this is a soft time bound plus a strict work cap. */
    static final long SNAPSHOT_NANOS = 2_000_000;
    static final int SNAPSHOT_UNITS = 256;
    private int snapshotCursor;
    private long lastPersist;
    private List<Vec3> extraAnchors = List.of();
    /** While set, the live tick leaves the regions alone (game tests drive them with {@link #runNow}). */
    private boolean paused;
    /** While set, only sources (and saved rooms) inside this box are planned (game tests, so neighbours don't mix). */
    private int[] testArea;
    /** CPU milliseconds the jobs finished in the last plan interval took (for the debug command). */
    private double usedMs;
    private double usedMsLast;
    private LocalAirPreparation localAirPreparation,localAirJobOwner;
    private double localAirReservedMs;
    private LocalAirWorldSource localAirSource;
    private final LocalAirSkyCache localAirSky=new LocalAirSkyCache();
    LocalAirSkyCache skyCache() { return localAirSky; }
    private LocalAirGeometryCapture.Bounds localAirWeatherBounds;
    private long localAirWeatherSampleTick=-1,localAirWeatherSampleNanos;
    private LocalAirRun.Weather localAirWeather;
    private final ConcurrentLinkedQueue<LocalAirPreparation.Result> localAirDone = new ConcurrentLinkedQueue<>();
    private boolean localAirWorkerTurn,localAirSnapshotTurn;
    private int localAirCaptureCursor,localAirDispatchCursor;
    private HeatBenchmark.Token localAirProfiling;
    private long localAirAdvanceNanos,localAirAdvanceUnits;

    private static void invalidateLocalAir(ServerLevel level,ChunkPos chunk,String cause) {
        var sky=LOCAL_AIR_SKY.get(level);if(sky!=null) sky.chunkChanged(chunk.x,chunk.z);
        var epoch=LOCAL_AIR_EPOCHS.get(level);
        if(epoch!=null) epoch.chunkChanged(chunk.x,chunk.z,level.getServer().isSameThread()?level.getGameTime():-1,cause);
        for(var dependency:AUTOMATIC_AIR_EPOCHS.getOrDefault(level,List.of()))
            dependency.chunkChanged(chunk.x,chunk.z,level.getServer().isSameThread()?level.getGameTime():-1,cause);
    }

    /** Internal, explicit diagnostic request only; replaces the previous domain. No solver activation. */
    void requestLocalAirGeometry(LocalAirGeometryCapture.Bounds bounds,int cellLimit,int coverageLimit) {
        requestLocalAir(bounds,cellLimit,coverageLimit,null);
    }
    /** Explicit cold-start diagnostic with fixed boundary weather; still isolated from production consumers. */
    void requestLocalAirSimulation(LocalAirGeometryCapture.Bounds bounds,int cellLimit,int coverageLimit,LocalAirRun.Settings settings) {
        requestLocalAir(bounds,cellLimit,coverageLimit,java.util.Objects.requireNonNull(settings));
    }
    void requestLiveLocalAirSimulation(LocalAirGeometryCapture.Bounds bounds,int cellLimit,int coverageLimit,int steps) {
        long begin=System.nanoTime();var weather=LocalAirWeatherSource.sample(level,bounds);long nanos=System.nanoTime()-begin;
        requestLocalAirSimulation(bounds,cellLimit,coverageLimit,new LocalAirRun.Settings(weather.ambient(),weather.windX(),weather.windZ(),steps));
        localAirWeatherBounds=bounds;localAirWeather=weather;localAirWeatherSampleTick=level.getGameTime();localAirWeatherSampleNanos=nanos;
    }
    private void observeLocalAir(long tick) {
        if(localAirPreparation==null) return;
        if(localAirWeatherBounds!=null && tick-localAirWeatherSampleTick>=LocalAirRun.PHYSICAL_TICKS) {
            long begin=System.nanoTime();localAirWeatherSampleTick=tick;
            try {
                localAirWeather=LocalAirWeatherSource.sample(level,localAirWeatherBounds);
                localAirPreparation.changeWeather(tick,localAirWeather,responseScale());
            } catch(RuntimeException error) { localAirPreparation.weatherUnavailable(error.toString()); }
            finally { localAirWeatherSampleNanos+=System.nanoTime()-begin; }
        }
        localAirPreparation.observe(tick,responseScale());
    }
    private void requestLocalAir(LocalAirGeometryCapture.Bounds bounds,int cellLimit,int coverageLimit,LocalAirRun.Settings settings) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Local air request requires server thread");
        if(bounds.cells()>cellLimit || coverageLimit<0) throw new IllegalArgumentException("Invalid local air admission limits");
        clearLocalAirGeometry();
        var epoch=new LocalAirDependencies(bounds);
        LOCAL_AIR_EPOCHS.put(level,epoch);
        var source=new LocalAirWorldSource(level,epoch::get,epoch,localAirSky);
        localAirSource=source;
        localAirPreparation=new LocalAirPreparation(source,bounds,cellLimit,coverageLimit,settings);
        localAirPreparation.invalidationCause(epoch::lastCause);localAirPreparation.revisionSites(epoch::revisionSites);
        localAirAdvanceNanos=0;localAirAdvanceUnits=0;
    }
    void clearLocalAirGeometry() {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Local air clear requires server thread");
        if(localAirPreparation!=null) localAirPreparation.close();
        localAirPreparation=null;localAirSource=null;LOCAL_AIR_EPOCHS.remove(level);
        localAirWeatherBounds=null;localAirWeather=null;localAirWeatherSampleTick=-1;localAirWeatherSampleNanos=0;
        localAirAdvanceNanos=0;localAirAdvanceUnits=0;
        // A retired physical job still occupies localAirJobOwner until collect() charges its time.
    }
    LocalAirGeometryCapture.Prepared localAirGeometry() {
        return localAirPreparation==null?null:localAirPreparation.published();
    }
    LocalAirPreparation.Status localAirStatus() {
        return localAirPreparation==null?LocalAirPreparation.Status.CLOSED:localAirPreparation.status();
    }
    LocalAirRun.Frame localAirField() { return localAirPreparation==null?null:localAirPreparation.field(); }
    LocalAirPresentation localAirPresentation(boolean automatic,double x,double y,double z,int maxAge) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Local-air view requires the server thread");
        long tick=level.getGameTime();LocalAirReading.validate(tick,x,y,z,maxAge);
        if(automatic) return automaticAir==null?LocalAirPresentation.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.NO_REQUEST)
                :automaticAir.presentation(tick,x,y,z,maxAge);
        return localAirPreparation==null?LocalAirPresentation.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.NO_REQUEST)
                :localAirPreparation.presentation(tick,x,y,z,maxAge);
    }
    /** Server-thread-only read of the explicitly requested experimental domain. Never creates a domain,
     * loads chunks, waits for workers, or substitutes weather/legacy heat for a missing reading.
     * The caller supplies its acceptable age in game ticks; no production freshness default is selected.
     */
    public LocalAirReading queryLocalAir(double x,double y,double z,long maxAgeTicks) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Local air queries require the server thread");
        long tick=level.getGameTime();LocalAirReading.validate(tick,x,y,z,maxAgeTicks);
        return localAirPreparation==null?LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.NO_REQUEST)
                :localAirPreparation.query(tick,x,y,z,maxAgeTicks);
    }
    LocalAirRun.Status localAirRunStatus() { return localAirPreparation==null?null:localAirPreparation.runStatus(); }
    String localAirRunReason() { return localAirPreparation==null?"":localAirPreparation.runReason(); }
    Map<LocalAirMetrics.Stage,LocalAirMetrics.Row> localAirPreparationMetrics() { return localAirPreparation==null?Map.of():localAirPreparation.metrics(); }
    Map<String,Object> localAirReport() {
        var report=new java.util.LinkedHashMap<String,Object>();
        report.put("preparation",localAirStatus().name());
        report.put("forcingMode",localAirWeatherBounds==null?"FIXED":"LIVE_CENTER");
        report.put("weatherSampleTick",localAirWeatherSampleTick);report.put("weatherSampleMs",localAirWeatherSampleNanos/1e6);
        if(localAirWeather!=null) report.put("sampledWeather",localAirWeather);
        var run=localAirRunStatus();report.put("run",run==null?"UNAVAILABLE":run.name());report.put("reason",localAirRunReason());
        report.put("physicalJobInFlight",localAirJobOwner!=null);report.put("sharedBudgetMs",budgetMs);
        report.put("reservedLocalAirMs",localAirReservedMs);
        report.put("snapshotAdvanceMs",localAirAdvanceNanos/1e6);report.put("snapshotChargedUnits",localAirAdvanceUnits);
        report.put("attempts",localAirPreparation==null?0:localAirPreparation.attempts());
        report.put("restarts",localAirPreparation==null?0:localAirPreparation.restarts());
        report.put("startup",localAirPreparation==null?null:localAirPreparation.startup());
        report.put("thermalReadCells",localAirPreparation==null?0:localAirPreparation.thermalReadCells());
        report.put("thermalReusedCells",localAirPreparation==null?0:localAirPreparation.thermalReusedCells());
        report.put("lastRestart",localAirPreparation==null?"No request":localAirPreparation.lastRestart());
        report.put("restartCauses",localAirPreparation==null?Map.of():localAirPreparation.restartCauses());
        report.put("failureCauses",localAirPreparation==null?Map.of():localAirPreparation.failureCauses());
        report.put("topologyStages",localAirPreparation==null?0:localAirPreparation.topologyStages());
        report.put("editOutcomes",localAirPreparation==null?Map.of():localAirPreparation.editOutcomes());
        report.put("rereadCells",localAirPreparation==null?0:localAirPreparation.rereadCells());
        report.put("postCaptureRereads",localAirPreparation==null?0:localAirPreparation.postCaptureRereads());
        report.put("replans",localAirPreparation==null?0:localAirPreparation.replans());
        report.put("geometryEdits",localAirPreparation==null?Map.of():localAirPreparation.geometryEdits());
        report.put("editPhases",localAirPreparation==null?Map.of():localAirPreparation.editPhases());
        report.put("requeuedColumns",localAirPreparation==null?0:localAirPreparation.requeuedColumns());
        report.put("revisionSites",localAirPreparation==null?Map.of():localAirPreparation.revisionSites());
        report.put("weatherRevisions",localAirPreparation==null?0:localAirPreparation.weatherRevisions());
        var counted=localAirField();
        report.put("gridRebuilds",counted==null?0:counted.gridRebuilds);report.put("shortSteps",counted==null?0:counted.shortSteps);
        var dependencies=LOCAL_AIR_EPOCHS.get(level);
        report.put("invalidations",dependencies==null?null:dependencies.diagnostics());
        report.put("captureReadCells",localAirPreparation==null?0:localAirPreparation.captureReadCells());
        report.put("drivenSourceCells",localAirPreparation==null?0:localAirPreparation.drivenSourceCells());
        report.put("drivenReadCells",localAirPreparation==null?0:localAirPreparation.drivenReadCells());
        report.put("captureReusedCells",localAirPreparation==null?0:localAirPreparation.captureReusedCells());
        report.put("coverageReadCells",localAirPreparation==null?0:localAirPreparation.coverageReadCells());
        report.put("coverageReusedCells",localAirPreparation==null?0:localAirPreparation.coverageReusedCells());
        report.put("coverageBulkCells",localAirPreparation==null?0:localAirPreparation.coverageBulkCells());
        report.put("skyIndex",localAirSky.report());
        report.put("workerTimings",localAirPreparation==null?Map.of():localAirPreparation.metrics());
        if(automaticAir!=null) report.put("automatic",Map.of("settings",automaticAir.settings,"reservedCells",automaticAir.cells(),"domains",automaticAir.rows(),"decisions",automaticAir.decisions(),"layoutState",automaticAir.transferState(),"layoutTransfers",automaticAir.transfers(),"lastLayoutTransfer",automaticAir.lastTransfer()==null?Map.of():automaticAir.lastTransfer()));
        var frame=localAirField();report.put("fieldAvailable",frame!=null);
        if(frame!=null) {
            report.put("frameTick",frame.throughTick);report.put("ageTicks",LocalAirDiagnostics.age(frame,level.getGameTime()));
            report.put("acceptedSteps",frame.acceptedSteps);report.put("airNodes",frame.flow.nodes());report.put("faces",frame.flow.faces());
            report.put("geometryRevision",frame.geometryRevision);report.put("materialRevision",frame.materialRevision);
            report.put("sourceRevision",frame.sourceRevision);
            report.put("weatherRevision",frame.weatherRevision);report.put("weather",frame.weather);
            report.put("naturalInitialization",frame.naturalInitialization);
            report.put("geometryTransfers",frame.transfers);report.put("geometryExchangeEnergy",frame.geometryEnergy);
            report.put("quality",frame.quality);
        }
        return java.util.Collections.unmodifiableMap(report);
    }

    private HeatWorld(ServerLevel level) {
        this.level = level;
        LOCAL_AIR_SKY.put(level,localAirSky);
        airDemands=new LocalAirDemands(level);
    }
    /** Immutable planner input. Required sample cells only; no radius, tiles, admission or grid allocation yet. */
    public LocalAirDemandRegistry.Snapshot localAirDemands() {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Demand snapshots require the server thread");
        refreshAirDemands(true);return airDemands.registry.snapshot();
    }
    /** Explicit experimental planning policy; does not start coupled simulations or change readings. */
    public LocalAirPlanner.Plan planLocalAir(LocalAirPlanner.Settings settings) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Planning requires the server thread");
        java.util.Objects.requireNonNull(settings);
        if(airPlanner==null || !airPlanner.current().settings().equals(settings)) airPlanner=new LocalAirPlanner(settings);
        refreshAirDemands(true);return refreshAirPlan();
    }
    public LocalAirPlanner.Plan localAirPlan() {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Planning requires the server thread");
        return airPlanner==null?null:airPlanner.current();
    }
    public void stopLocalAirPlan() {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Planning requires the server thread");
        stopAutomaticAir();airPlanner=null;
    }
    private LocalAirPlanner.Plan refreshAirPlan() {
        nextAirPlanTick=level.getGameTime()+20;
        var demand=airDemands.registry.snapshot();
        if(testArea!=null) {
            var box=testArea.clone();java.util.function.Predicate<LocalAirDemandRegistry.Cell> within=c->inside(box,c.x(),c.y(),c.z());
            demand=new LocalAirDemandRegistry.Snapshot(demand.tick(),demand.revision(),demand.entries().stream().filter(e->within.test(e.request().cell())).toList(),demand.anchors().stream().filter(a->within.test(a.cell())).toList());
        }
        var plan=airPlanner.update(demand,level.getMinBuildHeight(),level.getMaxBuildHeight(),
                (x,z)->level.getChunkSource().getChunkNow(x,z)!=null);
        if(automaticAir!=null) {
            automaticAir.reconcile(plan,level.getMinBuildHeight(),level.getMaxBuildHeight());publishAutomaticEpochs();
        }
        return plan;
    }
    void startAutomaticAir(LocalAirAutomatic.Settings settings) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Automatic domains require server thread");
        if(airPlanner==null || !WeatherConfig.heat().enabled()) throw new IllegalStateException("Enable heat and configure air plan first");
        if(automaticAir!=null && !automaticAir.settings.equals(settings)) throw new IllegalStateException("Use air auto off before changing runtime limits");
        if(automaticAir==null) automaticAir=new LocalAirAutomatic(settings,new LocalAirAutomatic.Factory() {
            public boolean loaded(LocalAirPlanner.Tile tile) { return level.getChunkSource().getChunkNow(tile.x(),tile.z())!=null; }
            public LocalAirAutomatic.Capture capture(LocalAirGeometryCapture.Bounds bounds) {
                var epoch=new LocalAirDependencies(bounds);
                return new LocalAirAutomatic.Capture(new LocalAirWorldSource(level,epoch::get,epoch,localAirSky),epoch);
            }
            public LocalAirRun.Weather weather(LocalAirGeometryCapture.Bounds bounds) { return LocalAirWeatherSource.sample(level,bounds); }
            public LocalAirThermalState.Provider thermal() { return HeatWorld.this::beginAirThermal; }
            public void save(LocalAirRun.Seed seed) { persistAir(seed,null); }
        });
        refreshAirDemands(true);refreshAirPlan();
    }
    /** Legacy fields already carry the bounded thermal-only natural settling result. Never read a mutable grid. */
    LocalAirThermalState.Reader beginAirThermal(LocalAirGeometryCapture.Snapshot snapshot) {
        var estimates=regions.stream().filter(r->r.ship==null && !r.geometryDirty && !r.materialDirty && r.published!=null)
                .map(r->r.published).toList();
        var b=snapshot.bounds;
        return c->{
            int x=b.x()+b.cellX(c),y=b.y()+b.cellY(c),z=b.z()+b.cellZ(c);long pos=BlockPos.asLong(x,y,z);
            var chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
            if(chunk==null) throw new IllegalStateException("Thermal initialization chunk unloaded");
            var data=chunk.hasData(HeatContent.CHUNK_DATA)?chunk.getData(HeatContent.CHUNK_DATA):null;
            var saved=data==null?null:data.airValue(pos);
            if(saved!=null && saved.material.equals(snapshot.material(c))) return new LocalAirThermalState.Value(saved,0,true,"SAVED_COUPLED");
            float old=data==null?Float.NaN:data.value(pos);
            if(Float.isFinite(old)) return new LocalAirThermalState.Value(null,old,true,"LEGACY_SAVE");
            for(var p:estimates) if(p.contains(x,y,z)) {
                double offset=p.offsets()[p.index(x-p.minX(),y-p.minY(),z-p.minZ())];
                return new LocalAirThermalState.Value(null,offset,saved!=null,"LEGACY_THERMAL_ESTIMATE");
            }
            if(snapshot.natural(c) && snapshot.heat(c)!=null && snapshot.heat(c).active()) return null;
            return new LocalAirThermalState.Value(null,0,saved!=null,"AMBIENT");
        };
    }
    /** Only immutable accepted temperatures are written. Last accepted cells may survive a later invalidation,
     * but exact shape/material checks protect restore; they cannot become a current airflow reading. */
    void persistAir(LocalAirRun.Seed seed,LevelChunk unloading) {
        if(seed==null || !seed.frame().persistentThermal) return;
        var b=seed.snapshot().bounds;
        for(int c=0;c<b.cells();c++) {
            int x=b.x()+b.cellX(c),y=b.y()+b.cellY(c),z=b.z()+b.cellZ(c);
            var chunk=unloading==null?level.getChunkSource().getChunkNow(x>>4,z>>4):unloading;
            if(chunk==null || chunk.getPos().x!=(x>>4) || chunk.getPos().z!=(z>>4)) continue;
            var data=chunk.getData(HeatContent.CHUNK_DATA);data.putAir(BlockPos.asLong(x,y,z),LocalAirThermalState.save(seed,c));chunk.setUnsaved(true);
        }
    }
    void stopAutomaticAir() {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Automatic domains require server thread");
        if(automaticAir!=null) automaticAir.close();automaticAir=null;AUTOMATIC_AIR_EPOCHS.remove(level);
    }
    LocalAirAutomatic automaticAir() { return automaticAir; }
    private void publishAutomaticEpochs() { AUTOMATIC_AIR_EPOCHS.put(level,automaticAir.domains().stream().map(d->d.capture.dependencies()).toList()); }
    /** Separate experimental automatic query; production consumers and the manual viewer are unchanged. */
    public LocalAirReading queryAutomaticLocalAir(double x,double y,double z,long maxAgeTicks) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Automatic queries require server thread");
        long tick=level.getGameTime();LocalAirReading.validate(tick,x,y,z,maxAgeTicks);
        return automaticAir==null?LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.NO_REQUEST)
                :automaticAir.query(tick,x,y,z,maxAgeTicks);
    }
    private void refreshAirDemands(boolean force) {
        if(!WeatherConfig.heat().enabled()) { airDemands.clear();return; }
        airDemands.refresh(()->{
            var positions=new ArrayList<BlockPos>();
            for(var map:placedAir.values()) for(long pos:map.keySet()) positions.add(BlockPos.of(pos));
            return positions;
        },force);
    }
    /** Block-entity load notification also covers sensors whose load follows the chunk event. */
    public static void sensorDemandLoaded(ServerLevel level,BlockPos pos,BlockState state) {
        if(!level.getServer().isSameThread()) {
            var at=pos.immutable();level.getServer().execute(()->{
                var chunk=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
                if(chunk!=null) sensorDemandLoaded(level,at,chunk.getBlockState(at));
            });return;
        }
        var world=of(level);if(world!=null) world.airDemands.sensor(pos,state);
    }

    // ---- events ----

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)
                || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (level.getServer().isSameThread()) {
            of(level).onChunkLoad(chunk);
        } else {
            invalidateLocalAir(level,chunk.getPos(),"CHUNK_LOAD_HOOK");
            level.getServer().execute(() -> {
                if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk) {
                    of(level).onChunkLoad(chunk);
                }
            });
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        invalidateLocalAir(level,chunk.getPos(),"CHUNK_UNLOAD_HOOK");
        if(!level.getServer().isSameThread()) {
            var cp=chunk.getPos();level.getServer().execute(()->{
                var world=WORLDS.get(level);
                if(world!=null && level.getChunkSource().getChunkNow(cp.x,cp.z)==null) world.airDemands.unload(cp.x,cp.z);
            });return;
        }
        HeatWorld world = WORLDS.get(level);
        if (world != null) {
            world.onChunkUnload(chunk);
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            of(level).tick();
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        for (HeatWorld world : WORLDS.values()) {
            world.stopLocalAirPlan();
            world.airDemands.clear();
            world.clearLocalAirGeometry();
            world.persistAll();
        }
        WORLDS.clear();
        LOCAL_AIR_EPOCHS.clear();
        AUTOMATIC_AIR_EPOCHS.clear();
        LOCAL_AIR_SKY.clear();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /** A block changed in a loaded chunk (from {@code LevelChunkHeatMixin}; any thread). */
    public static void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState before, BlockState after) {
        if (!(chunk.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        var sky=LOCAL_AIR_SKY.get(level);
        if (level.getServer().isSameThread()) {
            if(sky!=null && (before==null || skyRelevant(before,after))) sky.blockChanged(pos.getX(),pos.getZ());
            HeatWorld world = WORLDS.get(level);
            if(world!=null && world.automaticAir!=null) world.automaticAir.beforeEdit(pos.getX(),pos.getY(),pos.getZ());
            if(world!=null && world.automaticAir!=null) for(var d:world.automaticAir.domains())
                applyAutomaticEdit(d,pos,before,after,level.getGameTime(),world.responseScale());
            if(world!=null && world.localAirSource!=null && !world.localAirSource.remember(pos,before)) {
                var epoch=LOCAL_AIR_EPOCHS.get(level);if(epoch!=null) epoch.historyLost(pos.getX(),pos.getY(),pos.getZ(),level.getGameTime());
            }
            var epoch=LOCAL_AIR_EPOCHS.get(level);
            if(epoch!=null && epoch.affectsBlock(pos.getX(),pos.getY(),pos.getZ())) {
                var prep=world==null?null:world.localAirPreparation;
                var outcome=prep==null?LocalAirPreparation.EditOutcome.GEOMETRY
                        :prep.classifyEdit(pos.getX(),pos.getY(),pos.getZ(),level.getGameTime(),world.responseScale(),before,after);
                if(outcome==LocalAirPreparation.EditOutcome.GEOMETRY) {
                    long previous=epoch.blockRevision(pos.getX(),pos.getY(),pos.getZ(),level.getGameTime(),String.valueOf(before),String.valueOf(after),LocalAirDependencies.MANUAL);
                    if(prep!=null && staticAirEdit(before,after)) {
                        prep.changeGeometry(pos.getX(),pos.getY(),pos.getZ(),previous,level.getGameTime(),world.responseScale(),
                                before.getBlock()==after.getBlock(),sameAirFluid(before,after));
                    }
                }
            }
            if (world != null) {
                world.blockChanged(pos, before, after);
            }
        } else {
            if(sky!=null) sky.blockChanged(pos.getX(),pos.getZ());
            for(var dependency:AUTOMATIC_AIR_EPOCHS.getOrDefault(level,List.of()))
                dependency.blockChanged(pos.getX(),pos.getY(),pos.getZ(),-1,String.valueOf(before),String.valueOf(after),LocalAirDependencies.OFF_THREAD);
            var dependencies=LOCAL_AIR_EPOCHS.get(level);
            if(dependencies!=null) dependencies.blockChanged(pos.getX(),pos.getY(),pos.getZ(),-1,String.valueOf(before),String.valueOf(after),LocalAirDependencies.OFF_THREAD);
            BlockPos at = pos.immutable();
            level.getServer().execute(() -> {
                HeatWorld world = WORLDS.get(level);
                if (world != null) {
                    world.blockChanged(at, null, level.getBlockState(at));
                }
            });
        }
    }

    // ---- sources ----
    /** Driven-candidate membership changes still require a new live driver history. */
    private static boolean staticAirEdit(BlockState before,BlockState after) {
        return before!=null && HeatTables.driven(before).isEmpty() && HeatTables.driven(after).isEmpty();
    }
    /** Only these properties can change a cached sky column or empty-section classification,
     * compared in local air's snow buckets: thin snow (1-3 layers) reads as air. */
    private static boolean skyRelevant(BlockState before,BlockState after) {
        before=LocalAirWorldSource.localAirState(before);after=LocalAirWorldSource.localAirState(after);
        return before.isAir()!=after.isAir() || before.getFluidState().isEmpty()!=after.getFluidState().isEmpty()
                || before.getBlock().hasDynamicShape() || after.getBlock().hasDynamicShape()
                || before.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).isEmpty()
                        !=after.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).isEmpty();
    }
    private static boolean sameAirFluid(BlockState before,BlockState after) {
        return before.getFluidState().createLegacyBlock().getBlock()==after.getFluidState().createLegacyBlock().getBlock();
    }
    private static void applyAutomaticEdit(LocalAirAutomatic.Domain d,BlockPos pos,BlockState before,BlockState after,long tick,double scale) {
        var epoch=d.capture.dependencies();var source=(LocalAirWorldSource)d.capture.world();var prep=d.preparation;
        if(!source.remember(pos,before)) epoch.historyLost(pos.getX(),pos.getY(),pos.getZ(),tick);
        if(!epoch.affectsBlock(pos.getX(),pos.getY(),pos.getZ())) return;
        if(prep.classifyEdit(pos.getX(),pos.getY(),pos.getZ(),tick,scale,before,after)==LocalAirPreparation.EditOutcome.GEOMETRY) {
            long previous=epoch.blockRevision(pos.getX(),pos.getY(),pos.getZ(),tick,String.valueOf(before),String.valueOf(after),LocalAirDependencies.APPLY_AUTOMATIC);
            if(staticAirEdit(before,after))
                prep.changeGeometry(pos.getX(),pos.getY(),pos.getZ(),previous,tick,scale,
                        before.getBlock()==after.getBlock(),sameAirFluid(before,after));
        }
    }

    void onChunkLoad(LevelChunk chunk) {
        airDemands.sourcesChanged();
        for(var blockEntity:chunk.getBlockEntities().values())
            if(blockEntity instanceof dev.brights0ng.meteodyne.instrument.SensorBlockEntity)
                airDemands.sensor(blockEntity.getBlockPos(),blockEntity.getBlockState());
        invalidateLocalAir(level,chunk.getPos(),"CHUNK_LOAD_APPLIED");
        long key = chunk.getPos().toLong();
        Long2ObjectOpenHashMap<BlockState> found = new Long2ObjectOpenHashMap<>(), air = new Long2ObjectOpenHashMap<>();
        scanPlaced(chunk, found, air);
        if (found.isEmpty()) {
            placed.remove(key);
        } else {
            placed.put(key, found);
        }
        if (air.isEmpty()) {
            placedAir.remove(key);
        } else {
            placedAir.put(key, air);
        }
        naturals.remove(key);
        if (chunk.hasData(HeatContent.CHUNK_DATA) && !chunk.getData(HeatContent.CHUNK_DATA).boxes().isEmpty()) {
            savedChunks.add(key);
        }
        touchChunk(chunk.getPos(), "chunk_load");
    }

    void onChunkUnload(LevelChunk chunk) {
        if(automaticAir!=null) for(var seed:automaticAir.thermalCheckpoints()) persistAir(seed,chunk);
        airDemands.unload(chunk.getPos().x,chunk.getPos().z);
        invalidateLocalAir(level,chunk.getPos(),"CHUNK_UNLOAD_APPLIED");
        ChunkPos cp = chunk.getPos();
        int[] chunkBox = {cp.getMinBlockX(), level.getMinBuildHeight(), cp.getMinBlockZ(), cp.getMaxBlockX(),
                level.getMaxBuildHeight() - 1, cp.getMaxBlockZ()};
        for (HeatRegion r : regions) {
            if (r.overlaps(chunkBox)) {
                persist(r);
            }
        }
        long key = cp.toLong();
        placed.remove(key);
        placedAir.remove(key);
        naturals.remove(key);
        savedChunks.remove(key);
        touchChunk(cp, "chunk_unload");
    }

    private void blockChanged(BlockPos pos, BlockState before, BlockState after) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        boolean isPlaced = placedCandidate(after), isAir = HeatTables.airSourceCandidate(after.getBlock());
        Long2ObjectOpenHashMap<BlockState> airMap = placedAir.get(key);
        if(isAir || airMap!=null && airMap.containsKey(pos.asLong())) airDemands.sourcesChanged();
        airDemands.sensor(pos,after);
        index(placed, key, pos, after, isPlaced);
        index(placedAir, key, pos, after, isAir);
        naturals.remove(key);
        // Classify only changes relevant to existing snapshots. Source indexing above remains unconditional.
        int reach = WindConfig.params().sideReach();
        boolean relevant = false;
        for (HeatRegion r : regions) {
            if (r.affectedBy(pos.getX(), pos.getY(), pos.getZ(), reach, true)) {
                relevant = true;
                break;
            }
        }
        if (!relevant) {
            return;
        }
        HeatTables.Change change = HeatTables.change(before, after);
        benchmark.blockChanged(before, after, change);
        for (HeatRegion r : regions) {
            boolean physical = change.geometry() && r.affectedBy(pos.getX(), pos.getY(), pos.getZ(), reach, change.heightmap());
            boolean material = change.material() && r.contains(pos.getX(), pos.getY(), pos.getZ());
            if (physical || material) {
                r.invalidateAt(pos.getX(), pos.getY(), pos.getZ(), physical, material);
                benchmark.invalidated(physical ? "block_geometry" : "block_material");
            }
        }
    }

    /** Puts or removes {@code pos} in one per-chunk source index. */
    private static void index(Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> index, long key, BlockPos pos,
            BlockState state, boolean member) {
        Long2ObjectOpenHashMap<BlockState> map = index.get(key);
        if (member) {
            if (map == null) {
                map = new Long2ObjectOpenHashMap<>();
                index.put(key, map);
            }
            map.put(pos.asLong(), state);
        } else if (map != null) {
            map.remove(pos.asLong());
            if (map.isEmpty()) {
                index.remove(key);
            }
        }
    }

    private void touchChunk(ChunkPos cp, String cause) {
        int reach = Math.max(1, WindConfig.params().sideReach());
        for (HeatRegion r : regions) {
            if (cp.getMinBlockX() <= r.box[3] + reach && cp.getMaxBlockX() >= r.box[0] - reach
                    && cp.getMinBlockZ() <= r.box[5] + reach && cp.getMaxBlockZ() >= r.box[2] - reach) {
                r.invalidate(true, true);
                benchmark.invalidated(cause);
            }
        }
    }

    /**
     * One pass over {@code chunk}: {@code placed} gets the legacy placed sources ({@link #placedCandidate}),
     * {@code air} the blocks that request local-air coverage in any state ({@link HeatTables#airSourceCandidate}).
     */
    private static void scanPlaced(LevelChunk chunk, Long2ObjectOpenHashMap<BlockState> placed,
            Long2ObjectOpenHashMap<BlockState> air) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()
                    || !section.maybeHas(s -> placedCandidate(s) || HeatTables.airSourceCandidate(s.getBlock()))) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        long pos = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                        if (placedCandidate(state)) {
                            placed.put(pos, state);
                        }
                        if (HeatTables.airSourceCandidate(state.getBlock())) {
                            air.put(pos, state);
                        }
                    }
                }
            }
        }
    }

    /** The sources in {@code chunk} that heat the air: placed ones, or natural ones. */
    private static List<Found> scan(LevelChunk chunk, boolean natural) {
        List<Found> out = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(s -> natural ? wanted(HeatTables.power(s), true)
                    : placedCandidate(s))) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        HeatPowerData.Variant v = HeatTables.power(state);
                        if (natural ? wanted(v, true) : placedCandidate(state)) {
                            out.add(new Found(baseX + x, baseY + y, baseZ + z, state, v));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean wanted(HeatPowerData.Variant v, boolean natural) {
        return v != null && v.inAir() && v.natural() == natural;
    }

    /** Whether a block in this state is indexed as a placed source: one now, or one its block entity may switch on. */
    private static boolean placedCandidate(BlockState state) {
        return wanted(HeatTables.power(state), false) || !HeatTables.driven(state).isEmpty();
    }

    private List<Found> naturalsIn(long key, long now) {
        Naturals n = naturals.get(key);
        if (n != null && now - n.tick < NATURAL_TTL) {
            return n.found;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        List<Found> found = chunk == null ? List.of() : scan(chunk, true);
        naturals.put(key, new Naturals(now, found));
        return found;
    }

    /** Players (not spectators) and ships in the level, and test anchors. */
    List<Vec3> anchors() {
        List<Vec3> out = people();
        HeatShips.get().addCentres(level, out);
        return out;
    }

    /** Players (not spectators) and test anchors. */
    private List<Vec3> people() {
        List<Vec3> out = new ArrayList<>(extraAnchors);
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) {
                out.add(p.position());
            }
        }
        return out;
    }

    // ---- ships (8f-5) ----

    /**
     * What a ship's regions take from where the ship is, once per plan per ship: the outside air at its world centre,
     * the wind against it in its own frame, whether a hangar roofs it, and whether a person is near.
     *
     * @param windX the wind over the ship, m/s, along its plot x (the world's wind at its height, less its own
     *              motion, cut by world blocks around it, turned into its frame)
     * @param roofed whether world blocks cover most of its top (a hangar): its open-sky cells act indoors
     */
    public record ShipAir(Vec3 worldCentre, double windX, double windZ, boolean roofed, boolean near) {
    }

    /** The ship whose plot holds the middle of {@code box}, or null for the world. */
    private Object shipOf(int[] box) {
        int cx = ((box[0] + box[3]) / 2) >> 4;
        int cz = ((box[2] + box[5]) / 2) >> 4;
        return HeatShips.get().containing(level, cx, cz);
    }

    /** What {@code ship}'s regions take from where it is now (public for the game tests). */
    public ShipAir shipAir(Object ship) {
        return shipAir(ship, List.of());
    }

    ShipAir shipAir(Object ship, List<Vec3> anchors) {
        return HeatShips.get().shipAir(level, ship, people(), NEAR);
    }

    /**
     * Forgets ship chunks that are gone (8f-5): a ship's plot chunks go without the usual unload event when it is
     * removed or disassembled.
     */
    private void pruneShipChunks() {
        java.util.function.LongPredicate gone = key -> Ships.get().inPlot(level, new ChunkPos(key))
                && level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null;
        placed.keySet().removeIf(gone);
        placedAir.keySet().removeIf(gone);
        naturals.keySet().removeIf(gone);
        savedChunks.removeIf(gone);
    }

    // ---- the tick ----

    void tick() {
        benchmark.tick(level.getGameTime());
        long timing=benchmark.begin();
        try {
            long demands=benchmark.begin();refreshAirDemands(false);benchmark.end("local_air_demand_ms",demands);
            if(airPlanner!=null && level.getGameTime()>=nextAirPlanTick) {
                long planning=benchmark.begin();refreshAirPlan();benchmark.end("local_air_planning_ms",planning);
            }
            tickSimulation();
        } finally { benchmark.end("thermal_tick_ms",timing); }
    }

    HeatBenchmark benchmark() { return benchmark; }

    private void tickSimulation() {
        observeResponse(level.getGameTime());
        if (paused) {
            return;
        }
        HeatSettings s = WeatherConfig.heat();
        long now = level.getGameTime();
        collect();
        if (!s.enabled()) {
            stopAutomaticAir();
            clearLocalAirGeometry();
            if (!regions.isEmpty()) {
                persistAll();
                for (HeatRegion r : regions) {
                    r.removed = true;
                }
                regions = new ArrayList<>();
                inputPlans.clear();
            }
            return;
        }
        if (now % PLAN_INTERVAL == 0) {
            plan(now, s);
            usedMsLast = usedMs;
            usedMs = 0;
        }
        prepareAllSnapshots(SNAPSHOT_UNITS, System.nanoTime() + SNAPSHOT_NANOS);
        dispatch(now, s);
        if (now - lastPersist >= PERSIST_INTERVAL) {
            persistAll();
            lastPersist = now;
        }
    }

    /** Groups the sources into regions, keeps or replaces the regions, and readies their next step's inputs. */
    void plan(long now, HeatSettings s) {
        long timing=benchmark.begin();
        long revision = HeatTables.revision();
        if (tableRevision != revision) {
            tableRevision = revision;
            naturals.clear();
            for (HeatRegion r : regions) {
                r.invalidate(true, true);
                benchmark.invalidated("data_reload");
            }
        }
        WindParams windParams = WindConfig.params();
        if (shelterParams.sideReach() != windParams.sideReach() || shelterParams.roofReach() != windParams.roofReach()) {
            shelterParams = windParams;
            for (HeatRegion r : regions) {
                r.invalidate(true, false);
                benchmark.invalidated("shelter_config");
            }
        }
        pruneShipChunks();
        List<Vec3> anchors = anchors();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;

        // Placed sources everywhere loaded, and the boxes they make on their own
        List<Found> all = new ArrayList<>();
        for (Long2ObjectMap.Entry<Long2ObjectOpenHashMap<BlockState>> chunk : placed.long2ObjectEntrySet()) {
            for (Long2ObjectMap.Entry<BlockState> e : chunk.getValue().long2ObjectEntrySet()) {
                long p = e.getLongKey();
                BlockState state = e.getValue();
                List<HeatPowerData.Variant> driven = HeatTables.driven(state);
                if (!driven.isEmpty()) {
                    // On by the block entity (8f-3): every driven variant that runs now, at its share
                    BlockPos at = BlockPos.of(p);
                    for (HeatPowerData.Variant v : driven) {
                        double share = v.inAir() && !v.natural() ? HeatDrivers.share(level, at, state, v) : 0;
                        if (share > 0) {
                            all.add(new Found(at.getX(), at.getY(), at.getZ(), state, v.scaled(share)));
                        }
                    }
                    continue;
                }
                HeatPowerData.Variant v = HeatTables.power(state);
                if (v != null && v.inAir() && !v.natural()) {
                    all.add(new Found(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), state, v));
                }
            }
        }
        List<HeatClusters.Cluster> placedBoxes = clusters(all, s, minY, maxY);
        if (testArea != null) {
            all.removeIf(f -> !inside(testArea, f.x, f.y, f.z));
            placedBoxes = clusters(all, s, minY, maxY);
        }

        // Natural sources near people and ships, or inside what placed sources keep going
        LongOpenHashSet chunks = new LongOpenHashSet();
        int reach = s.naturalReach();
        for (Vec3 a : anchors) {
            int r = (reach >> 4) + 1;
            int cx = (int) Math.floor(a.x) >> 4;
            int cz = (int) Math.floor(a.z) >> 4;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    chunks.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }
        for (HeatClusters.Cluster c : placedBoxes) {
            int[] b = c.box();
            for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) {
                for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) {
                    chunks.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        var airEstimateAreas=automaticAir==null?List.<LocalAirGeometryCapture.Bounds>of():automaticAir.domains().stream()
                .filter(d->d.preparation.status()!=LocalAirPreparation.Status.CLOSED).map(d->d.bounds).toList();
        for(var b:airEstimateAreas) {
            var tiles=b.tiles();for(int t=0;t<tiles.length;t+=3) chunks.add(ChunkPos.asLong((b.x()+tiles[t])>>4,(b.z()+tiles[t+2])>>4));
        }
        // 8f-5: every loaded ship's chunks; natural sources aboard always count (a ship is its own anchor)
        LongOpenHashSet aboard = new LongOpenHashSet();
        HeatShips.get().addLoadedChunks(level, aboard);
        chunks.addAll(aboard);
        double reach2 = (double) reach * reach;
        for (long key : chunks) {
            boolean onShip = aboard.contains(key);
            for (Found f : naturalsIn(key, now)) {
                boolean in = onShip || airEstimateAreas.stream().anyMatch(b->b.contains(f.x+.5,f.y+.5,f.z+.5));
                for (Vec3 a : anchors) {
                    double dx = f.x + 0.5 - a.x;
                    double dy = f.y + 0.5 - a.y;
                    double dz = f.z + 0.5 - a.z;
                    if (dx * dx + dy * dy + dz * dz <= reach2) {
                        in = true;
                        break;
                    }
                }
                if (!in) {
                    for (HeatClusters.Cluster c : placedBoxes) {
                        int[] b = c.box();
                        if (f.x >= b[0] && f.y >= b[1] && f.z >= b[2] && f.x <= b[3] && f.y <= b[4] && f.z <= b[5]) {
                            in = true;
                            break;
                        }
                    }
                }
                if (in) {
                    if (testArea != null && !inside(testArea, f.x, f.y, f.z)) {
                        continue;
                    }
                    all.add(f);
                }
            }
        }

        // The regions
        List<HeatClusters.Cluster> clusters = clusters(all, s, minY, maxY);
        List<HeatRegion> next = new ArrayList<>();
        Set<HeatRegion> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<HeatRegion> absorbed = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<HeatRegion, List<Found>> members = new IdentityHashMap<>();
        for (HeatClusters.Cluster c : clusters) {
            HeatRegion r = null;
            for (HeatRegion old : regions) {
                if (!kept.contains(old) && old.sameBox(c.box())) {
                    r = old;
                    break;
                }
            }
            List<Found> m = new ArrayList<>(c.members().length);
            boolean naturalOnly = true;
            for (int i : c.members()) {
                Found f = all.get(i);
                m.add(f);
                naturalOnly &= f.variant.natural();
            }
            if (r == null) {
                r = create(c.box(), absorbed);
                r.pendingSettle = naturalOnly && r.pendingInitial == null;
            }
            r.naturalOnly = naturalOnly;
            kept.add(r);
            members.put(r, m);
            next.add(r);
        }
        for (HeatRegion old : regions) {
            if (kept.contains(old)) {
                continue;
            }
            boolean overlapsNew = false;
            for (HeatRegion n : next) {
                if (n.overlaps(old.box)) {
                    overlapsNew = true;
                    break;
                }
            }
            if (absorbed.contains(old) || overlapsNew) {
                old.removed = true;
            } else if (old.naturalOnly) {
                old.removed = true;
            } else if (!allLoaded(old.box)) {
                persist(old);
                old.removed = true;
            } else if (old.published != null && old.published.maxMagnitude() < FADED) {
                clearSaved(old.box);
                old.removed = true;
            } else {
                members.put(old, List.of());
                next.add(old);
            }
        }

        // Saved rooms whose chunks are back but that no region covers yet (cooling ones, after a reload)
        for (long key : new LongOpenHashSet(savedChunks)) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null || !chunk.hasData(HeatContent.CHUNK_DATA)) {
                savedChunks.remove(key);
                continue;
            }
            for (int[] box : new ArrayList<>(chunk.getData(HeatContent.CHUNK_DATA).boxes())) {
                if (testArea != null && !HeatChunkData.overlaps(testArea, box)) {
                    continue;
                }
                boolean covered = false;
                for (HeatRegion n : next) {
                    if (n.overlaps(box)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered && allLoaded(box)) {
                    HeatRegion r = create(box, absorbed);
                    members.put(r, List.of());
                    next.add(r);
                }
            }
        }

        regions = next;
        inputPlans.clear();
        Map<Object, ShipAir> shipAir = new IdentityHashMap<>();
        for (HeatRegion r : regions) {
            r.ship = shipOf(r.box);
            ShipAir air = null;
            if (r.ship != null) {
                air = shipAir.computeIfAbsent(r.ship, ship -> shipAir(ship, anchors));
            }
            List<Found> inputs = members.getOrDefault(r, List.of());
            inputPlans.put(r, new InputPlan(inputs, anchors, air));
            ready(r, inputs, anchors, air);
        }
        benchmark.end("planning_ms",timing);
    }

    /**
     * The sources grouped into region boxes (8f-5): the world's on their own, and each ship's on its own, its boxes
     * kept inside its plot so two ships (or a ship and the world) never share a region. Member indices are into
     * {@code found}. Sources in a plot with no ship are left out.
     */
    private List<HeatClusters.Cluster> clusters(List<Found> found, HeatSettings s, int minY, int maxY) {
        Map<Object, List<Integer>> homes = new java.util.LinkedHashMap<>();
        Map<Object, Object> ships = new IdentityHashMap<>();
        for (int i = 0; i < found.size(); i++) {
            Found f = found.get(i);
            Object home = WORLD_HOME;
            if (Ships.get().inPlot(level, new ChunkPos(f.x >> 4, f.z >> 4))) {
                Object ship = HeatShips.get().shipAt(level, new BlockPos(f.x, f.y, f.z));
                if (ship == null) {
                    continue;
                }
                home = ship;
                ships.put(ship, ship);
            }
            homes.computeIfAbsent(home, h -> new ArrayList<>()).add(i);
        }
        List<HeatClusters.Cluster> out = new ArrayList<>();
        for (Map.Entry<Object, List<Integer>> e : homes.entrySet()) {
            List<Integer> idx = e.getValue();
            int n = idx.size();
            int[] x = new int[n];
            int[] y = new int[n];
            int[] z = new int[n];
            for (int i = 0; i < n; i++) {
                Found f = found.get(idx.get(i));
                x[i] = f.x;
                y[i] = f.y;
                z[i] = f.z;
            }
            Object ship = ships.get(e.getKey());
            for (HeatClusters.Cluster c : HeatClusters.cluster(x, y, z, s.margin(), s.maxSize(), minY, maxY)) {
                int[] members = new int[c.members().length];
                for (int i = 0; i < members.length; i++) {
                    members[i] = idx.get(c.members()[i]);
                }
                int[] box = c.box();
                if (ship != null) {
                    box = HeatShips.get().clipToPlot(box, ship);
                    if (box == null) {
                        continue;
                    }
                }
                out.add(new HeatClusters.Cluster(box, members));
            }
        }
        return out;
    }

    private static final Object WORLD_HOME = new Object();

    private static boolean inside(int[] b, int x, int y, int z) {
        return x >= b[0] && y >= b[1] && z >= b[2] && x <= b[3] && y <= b[4] && z <= b[5];
    }

    /** A new region over {@code box}, starting from the fields of the regions it overlaps, else from saved offsets. */
    private HeatRegion create(int[] box, Set<HeatRegion> absorbed) {
        HeatRegion r = new HeatRegion(nextId++, box, params);
        List<HeatRegion.Published> before = new ArrayList<>();
        for (HeatRegion old : regions) {
            if (old.overlaps(box)) {
                absorbed.add(old);
                if (old.published != null) {
                    before.add(old.published);
                }
            }
        }
        float[] initial = new float[r.nx * r.ny * r.nz];
        boolean any = false;
        Long2ObjectOpenHashMap<HeatChunkData> saved = new Long2ObjectOpenHashMap<>();
        for (int z = 0; z < r.nz; z++) {
            for (int x = 0; x < r.nx; x++) {
                int wx = box[0] + x;
                int wz = box[2] + z;
                long ck = ChunkPos.asLong(wx >> 4, wz >> 4);
                HeatChunkData data = saved.get(ck);
                if (data == null && !saved.containsKey(ck)) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
                    data = chunk != null && chunk.hasData(HeatContent.CHUNK_DATA) ? chunk.getData(HeatContent.CHUNK_DATA) : null;
                    saved.put(ck, data);
                }
                for (int y = 0; y < r.ny; y++) {
                    int wy = box[1] + y;
                    float v = Float.NaN;
                    for (HeatRegion.Published p : before) {
                        if (p.contains(wx, wy, wz)) {
                            v = p.offsets()[p.index(wx - p.minX(), wy - p.minY(), wz - p.minZ())];
                            break;
                        }
                    }
                    if (Float.isNaN(v) && data != null) {
                        v = data.value(BlockPos.asLong(wx, wy, wz));
                    }
                    if (!Float.isNaN(v) && v != 0) {
                        initial[r.index(x, y, z)] = v;
                        any = true;
                    }
                }
            }
        }
        r.pendingInitial = any ? initial : null;
        return r;
    }

    /**
     * Takes the region's blocks if they changed, and works out its sources, wind and whether anyone is near. A ship's
     * region ({@code air} non-null, 8f-5) takes its outside air, wind and nearness from where the ship is.
     */
    private void ready(HeatRegion r, List<Found> sources, List<Vec3> anchors, ShipAir air) {
        boolean roofed = air != null && air.roofed();
        if (roofed != r.roofed) {
            r.roofed = roofed;
            r.invalidate(true, false);
            benchmark.invalidated("ship_roof");
        }
        if (r.geometryDirty || r.materialDirty || r.geometry == null) { return; }
        int[] b = r.box;
        BlockPos centre = new BlockPos((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
        double outside = sources.isEmpty() ? 0
                : Temperature.at(level, air != null ? BlockPos.containing(air.worldCentre()) : centre);
        byte[] kinds = r.geometry.kinds();
        List<AirGrid.Source> out = new ArrayList<>();
        int[] dx = {-1, 1, 0, 0, 0, 0};
        int[] dy = {0, 0, -1, 1, 0, 0};
        int[] dz = {0, 0, 0, 0, -1, 1};
        for (Found f : sources) {
            int lx = f.x - b[0];
            int ly = f.y - b[1];
            int lz = f.z - b[2];
            if (lx <= 0 || ly <= 0 || lz <= 0 || lx >= r.nx - 1 || ly >= r.ny - 1 || lz >= r.nz - 1) {
                continue;
            }
            double power = params.gridPower(f.variant.power(), f.variant.radiant());
            double limit = HeatTables.offsetLimit(f.state, f.variant, outside);
            int own = r.index(lx, ly, lz);
            List<Integer> targets = new ArrayList<>(6);
            if (isAirLike(kinds[own])) {
                targets.add(own);
            } else {
                for (int d = 0; d < 6; d++) {
                    int n = r.index(lx + dx[d], ly + dy[d], lz + dz[d]);
                    if (isAirLike(kinds[n])) {
                        targets.add(n);
                    }
                }
            }
            if (f.variant.perFace()) {
                power *= targets.size();
            }
            if (targets.isEmpty()) {
                if (f.variant.perFace()) {
                    continue;
                }
                targets.add(own);
            }
            double each = power / targets.size();
            for (int t : targets) {
                out.add(new AirGrid.Source(t, each, limit));
            }
        }
        if (!r.sources.equals(out)) {
            r.sources = List.copyOf(out);
            r.sourceRevision++;
            benchmark.invalidated("source_inputs");
        }
        if (air != null) {
            r.windX = air.windX();
            r.windZ = air.windZ();
            r.near = air.near();
            return;
        }
        WindColumn wind = WindSources.column(level, centre.getX() + 0.5, centre.getZ() + 0.5);
        double[] w = wind == null ? new double[]{0, 0} : wind.at(0);
        r.windX = w[0];
        r.windZ = w[1];
        r.near = false;
        for (Vec3 a : anchors) {
            if (a.x >= b[0] - NEAR && a.x <= b[3] + 1 + NEAR && a.y >= b[1] - NEAR && a.y <= b[4] + 1 + NEAR
                    && a.z >= b[2] - NEAR && a.z <= b[5] + 1 + NEAR) {
                r.near = true;
                break;
            }
        }
    }

    private static boolean isAirLike(byte k) {
        return k == AirGrid.AIR || k == AirGrid.EXPOSED;
    }

    /** Round-robin preparation on the server thread. Changes between slices requeue their affected lines. */
    int prepareSnapshots(int maxUnits, long deadline) {
        long timing = System.nanoTime();
        int used = 0, idle = 0;
        while (!regions.isEmpty() && used < maxUnits && System.nanoTime() < deadline && idle < regions.size()) {
            HeatRegion r = regions.get(Math.floorMod(snapshotCursor++, regions.size()));
            if (!r.geometryDirty && !r.materialDirty && r.geometry != null) { idle++; continue; }
            idle = 0;
            long sliceStart = System.nanoTime();
            if (r.geometryUpdate == null) {
                r.geometryUpdate = new HeatGeometryUpdate(r, WindConfig.params(), r.fullGeometryDirty);
                if (r.geometry == null) { benchmark.invalidated("region_created"); }
            }
            HeatGeometryUpdate update = r.geometryUpdate;
            long reads = update.blockReads;
            int count = update.advance(level, Math.min(8, maxUnits - used), deadline);
            used += count;
            long slice = System.nanoTime() - sliceStart;
            update.nanos += slice;
            benchmark.sample("snapshot_slice_ms", slice);
            benchmark.work("sampled_blocks", update.blockReads - reads);
            benchmark.work("line_column_units", count);
            if (!update.pending()) {
                HeatRegion.Geometry result = update.finish();
                boolean changed = result != r.geometry;
                if (changed) { r.pendingGeometry = result; }
                r.geometry = result;
                benchmark.sample(update.full ? "geometry_snapshot_ms" : r.geometryDirty ? "geometry_incremental_ms" : "material_snapshot_ms", update.nanos);
                if (update.coverNanos > 0) {
                    benchmark.sample(update.full ? "cover_snapshot_ms" : "cover_incremental_ms", update.coverNanos);
                }
                benchmark.work(update.full ? "full_completions" : "incremental_completions", 1);
                if (!changed) { benchmark.work("thermal_noop_completions", 1); }
                r.preparedGeometryRevision = r.geometryRevision;
                r.preparedMaterialRevision = r.materialRevision;
                r.geometryDirty = r.materialDirty = r.fullGeometryDirty = false;
                r.geometryUpdate = null;
                InputPlan inputs = inputPlans.get(r);
                if (inputs != null) { ready(r, inputs.sources, inputs.anchors, inputs.air); }
            }
        }
        long elapsed = System.nanoTime() - timing;
        benchmark.sample("snapshot_prepare_ms", elapsed);
        if (maxUnits == SNAPSHOT_UNITS && elapsed > SNAPSHOT_NANOS) { benchmark.work("over_target_ticks", 1); }
        if (used == maxUnits) { benchmark.work("unit_cap_slices", 1); }
        return used;
    }

    /** Slow independent control retained for snapshot equivalence game tests. Never used by the live tick. */
    HeatRegion.Geometry referenceGeometry(HeatRegion r) { return geometry(r, true); }

    /** The region's blocks as cells (unloaded chunks and the box's faces are boundary). Server thread. */
    private HeatRegion.Geometry geometry(HeatRegion r, boolean rebuildCover) {
        int n = r.nx * r.ny * r.nz;
        byte[] kinds = new byte[n];
        float[] cond = new float[n];
        float[] cap = new float[n];
        byte[] open = new byte[n];
        int[] b = r.box;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < r.nz; z++) {
            for (int x = 0; x < r.nx; x++) {
                int wx = b[0] + x;
                int wz = b[2] + z;
                LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
                int top = chunk == null ? 0 : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15);
                for (int y = 0; y < r.ny; y++) {
                    int i = r.index(x, y, z);
                    if (chunk == null || x == 0 || y == 0 || z == 0 || x == r.nx - 1 || y == r.ny - 1 || z == r.nz - 1) {
                        kinds[i] = AirGrid.BOUNDARY;
                        continue;
                    }
                    int wy = b[1] + y;
                    HeatTables.Cell cell = HeatTables.cell(chunk.getBlockState(pos.set(wx, wy, wz)));
                    if (cell.kind() == AirGrid.AIR) {
                        // A ship under a hangar roof (8f-5): no open sky anywhere on it
                        kinds[i] = wy > top && !r.roofed ? AirGrid.EXPOSED : AirGrid.AIR;
                    } else {
                        kinds[i] = AirGrid.SOLID;
                        cond[i] = cell.conductance();
                        cap[i] = cell.capacity();
                        open[i] = (byte) cell.openFaces();
                    }
                }
            }
        }
        byte[] cover;
        if (rebuildCover) {
            long coverTiming=benchmark.begin();
            cover=cover(r,kinds);
            benchmark.end("cover_snapshot_ms",coverTiming);
        } else {
            cover=r.geometry.cover();
        }
        return new HeatRegion.Geometry(kinds, cond, cap, open, cover);
    }

    /**
     * Which faces of each air cell are sheltered: the ships' wind check ({@code ShelterProbe}) for a one-block box,
     * done as sweeps over a snapshot of the region's blocks widened by the side reach (16) instead of a probe walk per
     * cell. A side counts as covered when a solid block (not a fluid, not something open all round like a fence post)
     * lies within the side reach that way; the top, within the roof reach (32) above (the column's highest block, from
     * the heightmap, where nothing inside the box is above). Server thread.
     */
    private byte[] cover(HeatRegion r, byte[] kinds) {
        dev.brights0ng.paradynelib.wind.WindParams wp =
                dev.brights0ng.paradynelib.wind.WindConfig.params();
        int reach = wp.sideReach();
        int roofReach = wp.roofReach();
        int[] b = r.box;
        int ex = r.nx + 2 * reach;
        int ez = r.nz + 2 * reach;
        int ny = r.ny;
        boolean[] solid = new boolean[ex * ny * ez];
        int[] top = new int[r.nx * r.nz];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < ez; z++) {
            for (int x = 0; x < ex; x++) {
                int wx = b[0] - reach + x;
                int wz = b[2] - reach + z;
                LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
                if (chunk == null) {
                    continue;
                }
                int bx = x - reach;
                int bz = z - reach;
                if (bx >= 0 && bz >= 0 && bx < r.nx && bz < r.nz) {
                    top[bz * r.nx + bx] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15);
                }
                for (int y = 0; y < ny; y++) {
                    BlockState state = chunk.getBlockState(pos.set(wx, b[1] + y, wz));
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    HeatTables.Cell cell = HeatTables.cell(state);
                    solid[(z * ny + y) * ex + x] = cell.kind() == AirGrid.SOLID && cell.openFaces() != AirGrid.ALL_FACES;
                }
            }
        }
        byte[] cover = new byte[r.nx * r.ny * r.nz];
        // Along x (west and east), then along z (north and south)
        for (int z = reach; z < reach + r.nz; z++) {
            for (int y = 0; y < ny; y++) {
                int last = Integer.MIN_VALUE / 2;
                for (int x = 0; x < ex; x++) {
                    if (solid[(z * ny + y) * ex + x]) {
                        last = x;
                    } else if (x >= reach && x < reach + r.nx && x - last <= reach) {
                        cover[r.index(x - reach, y, z - reach)] |= HeatRegion.COVER_WEST;
                    }
                }
                last = Integer.MAX_VALUE / 2;
                for (int x = ex - 1; x >= 0; x--) {
                    if (solid[(z * ny + y) * ex + x]) {
                        last = x;
                    } else if (x >= reach && x < reach + r.nx && last - x <= reach) {
                        cover[r.index(x - reach, y, z - reach)] |= HeatRegion.COVER_EAST;
                    }
                }
            }
        }
        for (int x = reach; x < reach + r.nx; x++) {
            for (int y = 0; y < ny; y++) {
                int last = Integer.MIN_VALUE / 2;
                for (int z = 0; z < ez; z++) {
                    if (solid[(z * ny + y) * ex + x]) {
                        last = z;
                    } else if (z >= reach && z < reach + r.nz && z - last <= reach) {
                        cover[r.index(x - reach, y, z - reach)] |= HeatRegion.COVER_NORTH;
                    }
                }
                last = Integer.MAX_VALUE / 2;
                for (int z = ez - 1; z >= 0; z--) {
                    if (solid[(z * ny + y) * ex + x]) {
                        last = z;
                    } else if (z >= reach && z < reach + r.nz && last - z <= reach) {
                        cover[r.index(x - reach, y, z - reach)] |= HeatRegion.COVER_SOUTH;
                    }
                }
            }
        }
        // Up: the nearest solid above in the box, else the column's highest block
        for (int z = 0; z < r.nz; z++) {
            for (int x = 0; x < r.nx; x++) {
                int last = Integer.MAX_VALUE / 2;
                int highest = top[z * r.nx + x];
                for (int y = ny - 1; y >= 0; y--) {
                    if (solid[((z + reach) * ny + y) * ex + x + reach]) {
                        last = y;
                    } else {
                        int wy = b[1] + y;
                        boolean covered = last - y <= roofReach
                                || (last > ny && highest > b[4] && highest - wy <= roofReach);
                        if (covered) {
                            cover[r.index(x, y, z)] |= HeatRegion.COVER_TOP;
                        }
                    }
                }
            }
        }
        // Only air cells need it
        for (int i = 0; i < cover.length; i++) {
            if (!isAirLike(kinds[i])) {
                cover[i] = 0;
            }
        }
        return cover;
    }

    /**
     * Each air cell's share of the wind, from its sheltered faces and the wind's direction, by the ships' formula
     * ({@code Shelter.exposure}). Worked out for the 32 face patterns once, then looked up.
     */
    static float[] windShares(byte[] cover, double windX, double windZ, double roofWeight) {
        float[] byPattern = new float[32];
        double[] coverage = new double[dev.brights0ng.paradynelib.wind.Shelter.FACES];
        for (int bits = 0; bits < 32; bits++) {
            coverage[dev.brights0ng.paradynelib.wind.Shelter.TOP] = (bits & HeatRegion.COVER_TOP) != 0 ? 1 : 0;
            coverage[dev.brights0ng.paradynelib.wind.Shelter.NORTH] = (bits & HeatRegion.COVER_NORTH) != 0 ? 1 : 0;
            coverage[dev.brights0ng.paradynelib.wind.Shelter.SOUTH] = (bits & HeatRegion.COVER_SOUTH) != 0 ? 1 : 0;
            coverage[dev.brights0ng.paradynelib.wind.Shelter.WEST] = (bits & HeatRegion.COVER_WEST) != 0 ? 1 : 0;
            coverage[dev.brights0ng.paradynelib.wind.Shelter.EAST] = (bits & HeatRegion.COVER_EAST) != 0 ? 1 : 0;
            byPattern[bits] = (float) dev.brights0ng.paradynelib.wind.Shelter.exposure(coverage, windX, windZ,
                    roofWeight);
        }
        float[] shares = new float[cover.length];
        for (int i = 0; i < cover.length; i++) {
            shares[i] = byPattern[cover[i] & 31];
        }
        return shares;
    }

    // ---- stepping ----

    /** Same soft deadline and total work allowance; alternate which capture gets first access. */
    int prepareAllSnapshots(int maxUnits,long deadline) {
        if(maxUnits<0) throw new IllegalArgumentException("Nonnegative snapshot allowance required");
        if(localAirPreparation==null && automaticAir==null) return prepareSnapshots(maxUnits,deadline);
        int reserved=maxUnits/2+maxUnits%2,used=0;
        localAirSnapshotTurn=!localAirSnapshotTurn;
        // Reserve half for the first path, then let both paths reclaim unused allowance.
        // All calls retain the same deadline and the combined cap; alternate first access.
        if(localAirSnapshotTurn) {
            used=advanceLocalAir(reserved,deadline);
            used+=prepareSnapshots(maxUnits-used,deadline);
            used+=advanceLocalAir(maxUnits-used,deadline);
        } else {
            used=prepareSnapshots(reserved,deadline);
            used+=advanceLocalAir(maxUnits-used,deadline);
            used+=prepareSnapshots(maxUnits-used,deadline);
        }
        return used;
    }
    private int advanceLocalAir(int allowance,long deadline) {
        long start=System.nanoTime();
        var owners=localAirOwners();int used=0;
        for(int i=0;i<owners.size() && used<allowance && System.nanoTime()<deadline;i++) {
            int index=Math.floorMod(localAirCaptureCursor+i,owners.size());
            int share=Math.max(1,(allowance-used)/(owners.size()-i));
            used+=owners.get(index).advance(share,deadline);
        }
        localAirCaptureCursor++;long nanos=System.nanoTime()-start;
        localAirAdvanceNanos+=nanos;localAirAdvanceUnits+=used;
        benchmark.sample("local_air_snapshot_advance_ms",nanos);benchmark.work("local_air_charged_cell_units",used);
        return used;
    }

    /** Worker captures only the immutable work and completion queue, never HeatWorld/ServerLevel. */
    private boolean dispatchLocalAir() {
        if(localAirJobOwner!=null || budgetMs<=0) return false;
        var owners=localAirOwners();LocalAirPreparation.Work work=null;LocalAirPreparation selected=null;
        for(int i=0;i<owners.size();i++) {
            selected=owners.get(Math.floorMod(localAirDispatchCursor++,owners.size()));work=selected.takeWork();if(work!=null) break;
        }
        if(work==null) return false;
        localAirJobOwner=selected;
        localAirProfiling=benchmark.submit();
        var output=localAirDone;
          var dispatched=work;
          localAirReservedMs=work.estimate()?Math.min(budgetMs,LocalAirBatch.MAX_NANOS/1e6):0;
          budgetMs-=localAirReservedMs;
          try {
              if(work.estimate()) LocalAirBatch.submit(pool,dispatched,(long)(localAirReservedMs*1e6),output::add);
              else pool.execute(()->output.add(dispatched.run()));
          }
        catch(java.util.concurrent.RejectedExecutionException error) {
            localAirJobOwner.complete(new LocalAirPreparation.Result(work,null,null,0,error));localAirJobOwner=null;
              localAirProfiling=null;
              budgetMs+=localAirReservedMs;localAirReservedMs=0;
        }
        return true;
    }
    private List<LocalAirPreparation> localAirOwners() {
        var owners=new ArrayList<LocalAirPreparation>();if(localAirPreparation!=null) owners.add(localAirPreparation);
        if(automaticAir!=null) for(var d:automaticAir.domains()) owners.add(d.preparation);return owners;
    }

    private void dispatch(long now, HeatSettings s) {
        ensurePool(s.threads());
        double cores = s.budgetCores();
        budgetMs = Math.min(budgetMs + cores * 50, cores * 1000);
        int inFlight = localAirJobOwner==null?0:1;
        List<HeatRegion> due = new ArrayList<>();
        for (HeatRegion r : regions) {
            if (r.inFlight) {
                inFlight++;
            } else if (!r.geometryDirty && !r.materialDirty && r.geometry != null
                    && (r.lastStep == Long.MIN_VALUE || now - r.lastStep >= interval(r, s))) {
                due.add(r);
            }
        }
        // Most overdue first (in units of their own interval), so near and far regions share fairly
        due.sort((a, c) -> Double.compare(overdue(c, now, s), overdue(a, now, s)));
        int max = s.threads() * 2;
        observeLocalAir(now);
        localAirWorkerTurn=!localAirWorkerTurn;
        if(localAirWorkerTurn && inFlight<max && dispatchLocalAir()) inFlight++;
        for (HeatRegion r : due) {
            if (budgetMs <= 0 || inFlight >= max) {
                break;
            }
            Job job = take(r, now);
            pool.execute(() -> done.add(run(job)));
            inFlight++;
        }
        if(!localAirWorkerTurn && inFlight<max) dispatchLocalAir();
    }

    private static long interval(HeatRegion r, HeatSettings s) {
        return Math.round((r.near ? s.nearStep() : s.farStep()) * 20);
    }

    private static double overdue(HeatRegion r, long now, HeatSettings s) {
        if (r.lastStep == Long.MIN_VALUE) {
            return Double.MAX_VALUE;
        }
        return (now - r.lastStep) / (double) interval(r, s);
    }

    /** Hands the region's prepared inputs to a job. */
    private Job take(HeatRegion r, long now) {
        observeResponse(now);
        ThermalClock.Interval elapsed = r.lastStep == Long.MIN_VALUE ? new ThermalClock.Interval(0,0)
                : thermalClock.interval(r.lastStep,now);
        double seconds = elapsed.thermalSeconds();
        Job job = new Job(r, r.pendingGeometry, r.pendingInitial, r.pendingSettle, r.sources, r.windX, r.windZ,
                r.geometry == null ? null : r.geometry.cover(),
                dev.brights0ng.paradynelib.wind.WindConfig.params().roofWeight(), seconds, now, benchmark.submit(),
                r.preparedGeometryRevision, r.preparedMaterialRevision, r.sourceRevision,
                elapsed.gameSeconds(), responseScale());
        r.pendingGeometry = null;
        r.pendingInitial = null;
        r.pendingSettle = false;
        r.lastStep = now;
        r.inFlight = true;
        return job;
    }

    /** Runs on a worker (or inline in tests): applies the inputs, steps, and publishes. */
    private static Done run(Job job) {
        long start = System.nanoTime();
        long cpu=job.profiling==null?-1:dev.brights0ng.paradynelib.heat.air.ThermalTiming.cpu();
        long allocated=job.profiling==null?-1:dev.brights0ng.paradynelib.heat.air.ThermalTiming.allocated();
        HeatRegion r = job.region;
        try {
            AirGrid g = r.grid;
            if (job.geometry != null) {
                HeatRegion.Geometry geo = job.geometry;
                g.setCells(geo.kinds(), geo.conductances(), geo.capacities(), geo.openFaces());
            }
            if (job.initial != null) {
                g.setOffsets(job.initial);
            }
            g.clearSources();
            g.sources().addAll(job.sources);
            g.setWind(job.windX, job.windZ);
            if (job.cover != null) {
                g.setWindShares(windShares(job.cover, job.windX, job.windZ, job.roofWeight));
            }
            long solveStart=job.profiling==null?0:System.nanoTime();
            if (job.settle) {
                // Initialization estimates an already-hot natural region, not elapsed momentum or response time.
                g.settle(SETTLE_SECONDS);
            }
            if (job.seconds > 0) {
                g.step(job.seconds);
            }
            long publicationStart=job.profiling==null?0:System.nanoTime();
            int n = r.nx * r.ny * r.nz;
            float[] offsets = new float[n];
            byte[] kinds = new byte[n];
            float[] wind = new float[n];
            g.exportOffsets(offsets);
            g.exportKinds(kinds);
            g.exportWindShares(wind);
            int[] b = r.box;
            var published=new HeatRegion.Published(b[0],b[1],b[2],r.nx,r.ny,r.nz,offsets,kinds,wind,job.tick);
            long end=System.nanoTime();
            HeatBenchmark.JobMeasurement measurement=job.profiling==null?null:new HeatBenchmark.JobMeasurement(job.profiling,
                    end-start,dev.brights0ng.paradynelib.heat.air.ThermalTiming.difference(cpu,dev.brights0ng.paradynelib.heat.air.ThermalTiming.cpu()),
                    dev.brights0ng.paradynelib.heat.air.ThermalTiming.difference(allocated,dev.brights0ng.paradynelib.heat.air.ThermalTiming.allocated()),
                    start-job.profiling.submitted(),publicationStart-solveStart,end-publicationStart,n,job.geometry!=null,job.settle,job.seconds,
                    r.id,job.geometryRevision,job.materialRevision,job.sourceRevision,job.gameSeconds,job.responseScale);
            return new Done(r,published,end-start,measurement);
        } catch (Throwable t) {
            Meteodyne.LOGGER.error("Heat simulation step failed for region {}", r.id, t);
            return new Done(r, null, System.nanoTime() - start,null);
        }
    }

    private void collect() {
        LocalAirPreparation.Result air;
        while((air=localAirDone.poll())!=null) {
            double ms=air.nanos()/1e6;budgetMs+=localAirReservedMs-ms;localAirReservedMs=0;usedMs+=ms;
            benchmark.sample("local_air_worker_ms",air.nanos());
            benchmark.localAirCompleted(localAirProfiling,air.timing(),air.failed());localAirProfiling=null;
            if(localAirJobOwner!=null) localAirJobOwner.complete(air);
            localAirJobOwner=null;
            if(air.error()!=null) Meteodyne.LOGGER.error("Local air geometry preparation failed",air.error());
        }
        Done d;
        while ((d = done.poll()) != null) {
            HeatRegion r = d.region;
            benchmark.completed(d.measurement);
            r.inFlight = false;
            r.lastNanos = d.nanos;
            double ms = d.nanos / 1e6;
            budgetMs -= ms;
            usedMs += ms;
            if (!r.removed && d.published != null) {
                r.published = d.published;
            }
        }
    }

    private static synchronized void ensurePool(int threads) {
        if (pool != null && poolThreads == threads) {
            return;
        }
        if (pool != null) {
            pool.shutdown();
        }
        poolThreads = threads;
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "EAE Heat");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    // ---- saving ----

    /** Writes every saved region into its chunks. */
    public void persistAll() {
        long airSave=benchmark.begin();try { if(automaticAir!=null) automaticAir.persist(); } finally { benchmark.end("local_air_persist_ms",airSave); }
        for (HeatRegion r : regions) {
            persist(r);
        }
    }

    /** Writes a region's latest field into its loaded chunks (not natural-only ones). */
    private void persist(HeatRegion r) {
        HeatRegion.Published p = r.published;
        if (r.naturalOnly || p == null) {
            return;
        }
        int[] b = r.box;
        for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) {
            for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                HeatChunkData data = chunk.getData(HeatContent.CHUNK_DATA);
                data.clearBox(b);
                data.addBox(b);
                int x0 = Math.max(b[0] + 1, cx << 4);
                int x1 = Math.min(b[3] - 1, (cx << 4) + 15);
                int z0 = Math.max(b[2] + 1, cz << 4);
                int z1 = Math.min(b[5] - 1, (cz << 4) + 15);
                for (int wz = z0; wz <= z1; wz++) {
                    for (int wx = x0; wx <= x1; wx++) {
                        for (int wy = b[1] + 1; wy <= b[4] - 1; wy++) {
                            float v = p.offsets()[p.index(wx - b[0], wy - b[1], wz - b[2])];
                            data.put(BlockPos.asLong(wx, wy, wz), v);
                        }
                    }
                }
                chunk.setUnsaved(true);
                savedChunks.add(ChunkPos.asLong(cx, cz));
            }
        }
    }

    /** Forgets what was saved for {@code box} (and any saved box overlapping it) in its loaded chunks. */
    public void clearSaved(int[] box) {
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || !chunk.hasData(HeatContent.CHUNK_DATA)) {
                    continue;
                }
                HeatChunkData data = chunk.getData(HeatContent.CHUNK_DATA);
                data.clearBox(box);
                chunk.setUnsaved(true);
                if (data.boxes().isEmpty()) {
                    savedChunks.remove(ChunkPos.asLong(cx, cz));
                }
            }
        }
    }

    private boolean allLoaded(int[] box) {
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- reading ----

    /** How much warmer (negative: colder) than the outside air the simulation makes it at a world point, C. */
    public double offsetAt(double x, double y, double z) {
        double v = regionOffset(x, y, z);
        return Double.isNaN(v) ? 0 : v;
    }

    /** The offset at a point (world or plot), or NaN if no region holds it. */
    private double regionOffset(double x, double y, double z) {
        for (HeatRegion r : regions) {
            HeatRegion.Published p = r.published;
            if (p != null && p.contains(x, y, z)) {
                return p.offsetAt(x, y, z);
            }
        }
        return Double.NaN;
    }

    /**
     * The room's warmth, C, for someone at {@code at} (8f-5): a point in a ship's plot reads that ship's rooms; a
     * world point inside a ship's bounds reads the ship's rooms there if one holds it, else the world's.
     */
    public double roomAt(Vec3 at) {
        if (Ships.get().inPlot(level, BlockPos.containing(at))) {
            return offsetAt(at.x, at.y, at.z);
        }
        Vec3 plot = Ships.get().plotPoint(level, at.x, at.y, at.z);
        if (plot != null) {
            double v = regionOffset(plot.x, plot.y, plot.z);
            if (!Double.isNaN(v)) {
                return v;
            }
        }
        return offsetAt(at.x, at.y, at.z);
    }

    public List<HeatRegion> regions() {
        return List.copyOf(regions);
    }

    /** CPU milliseconds the steps finished in the last second took. */
    public double usedMsLastSecond() {
        return usedMsLast;
    }

    public int placedSourceCount() {
        int n = 0;
        for (Long2ObjectOpenHashMap<BlockState> m : placed.values()) {
            n += m.size();
        }
        return n;
    }

    // ---- tests and debug ----

    /** Places that count as a player for natural sources and stepping (game tests, the climatology run). */
    public void setExtraAnchors(List<Vec3> anchors) {
        this.extraAnchors = List.copyOf(anchors);
    }

    /** Stops (or restarts) the live tick, for game tests that drive the regions themselves. */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * Plans only sources inside {@code box} ({minX, minY, minZ, maxX, maxY, maxZ}, world blocks; null for all), so a
     * game test's region doesn't take in its neighbours' fires.
     */
    public void setTestArea(int[] box) {
        this.testArea = box == null ? null : box.clone();
    }

    /** Plans now. */
    public void planNow() {
        plan(level.getGameTime(), WeatherConfig.heat());
        prepareSnapshots(Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * Plans, then runs every region {@code seconds} on this thread (waiting for any step already running). For game
     * tests and the debug command; outside the budget.
     */
    public void runNow(double seconds) {
        ThermalClock.thermalSeconds(seconds,responseScale());
        waitForJobs();
        long now = level.getGameTime();
        plan(now, WeatherConfig.heat());
        prepareSnapshots(Integer.MAX_VALUE, Long.MAX_VALUE);
        for (HeatRegion r : regions) {
            Job job = take(r, now);
            Job timed = new Job(job.region, job.geometry, job.initial, job.settle, job.sources, job.windX, job.windZ,
                    job.cover, job.roofWeight, ThermalClock.thermalSeconds(seconds,job.responseScale), job.tick, job.profiling,
                    job.geometryRevision, job.materialRevision, job.sourceRevision, seconds, job.responseScale);
            done.add(run(timed));
        }
        collect();
    }

    /** Effective live rate. Overrides are world-session-only and never write the config or temperatures. */
    public double responseScale() {
        return responseOverride == null ? WeatherConfig.heat().responseScale() : responseOverride;
    }

    public Double responseScaleOverride() { return responseOverride; }

    public void setResponseScaleOverride(Double scale) {
        if (scale != null) { ThermalClock.requireScale(scale); }
        long now = level.getGameTime();
        observeResponse(now);
        responseOverride = scale;
        observeResponse(now);
    }

    private void observeResponse(long now) {
        thermalClock.observe(now,responseScale());
        observeLocalAir(now);
        if(automaticAir!=null) automaticAir.observe(now,responseScale());
    }

    private void waitForJobs() {
        long until = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < until) {
            collect();
            boolean busy = false;
            for (HeatRegion r : regions) {
                busy |= r.inFlight;
            }
            if (!busy) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    /** Drops every region without saving (as if the world had just loaded; game tests). */
    public void forgetRegions() {
        clearLocalAirGeometry();
        waitForJobs();
        for (HeatRegion r : regions) {
            r.removed = true;
        }
        regions = new ArrayList<>();
        inputPlans.clear();
        snapshotCursor = 0;
        naturals.clear();
    }
}
