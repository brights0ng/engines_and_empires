// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

/** Opt-in, forked fixed-workload benchmark. Timings are observations, never pass/fail thresholds. */
public final class ThermalBenchmark {
    private static final String VERSION="thermal-baseline-v2";
    private static volatile Object sink;
    private static final int WARMUP=30, SAMPLES=100, FORKS=3, BATCH=10;
    record Scene(String name,int nx,int ny,int nz,int layout) {
        int cells() { return nx*ny*nz; }
    }
    private static final List<Scene> SCENES=List.of(new Scene("open16",16,16,16,0),
            new Scene("sealed-house",24,16,24,1),new Scene("doorway-house",24,16,24,2),
            new Scene("partial-block-house",24,16,24,3),new Scene("multiroom32",32,32,32,4),new Scene("stress48",48,48,48,4));
    record Fixture(Scene scene,byte[] kinds,byte[] open,float[] conductance,float[] capacity,float[] shares) {
        int index(int x,int y,int z) { return (z*scene.ny+y)*scene.nx+x; }
        void apply(AirGrid grid) { grid.setCells(kinds,conductance,capacity,open); grid.setWind(6,2); grid.setWindShares(shares); }
        AirGrid grid() {
            AirGrid grid=new AirGrid(scene.nx,scene.ny,scene.nz,AirParams.DEFAULT);
            apply(grid);
            grid.addSource(scene.nx/2,3,scene.nz/2,75,30);
            return grid;
        }
    }
    static Fixture fixture(Scene s) {
        int n=s.cells();
        var f=new Fixture(s,new byte[n],new byte[n],new float[n],new float[n],new float[n]);
        int left=s.nx/4,right=s.nx*3/4-1,front=s.nz/4,back=s.nz*3/4-1,top=Math.min(10,s.ny-3);
        for (int z=1;z<s.nz-1;z++) for (int y=1;y<s.ny-1;y++) for (int x=1;x<s.nx-1;x++) {
            int i=f.index(x,y,z);
            boolean inside=s.layout>0 && x>=left && x<=right && z>=front && z<=back && y>=2 && y<=top;
            boolean solid=y==1 || inside && (x==left || x==right || z==front || z==back || y==2 || y==top);
            boolean doorway=inside && x==left && z==s.nz/2 && (y==3 || y==4) && s.layout>=2;
            boolean partition=s.layout>=4 && inside && x==s.nx/2 && z!=s.nz/2 && y>2 && y<top;
            solid=(solid && !doorway) || partition;
            f.kinds[i]=solid?AirGrid.SOLID:inside?AirGrid.AIR:AirGrid.EXPOSED;
            f.conductance[i]=1; f.capacity[i]=1;
            f.open[i]=(byte)(s.layout==3 && doorway ? AirGrid.WEST|AirGrid.EAST : 0);
            if (s.layout==3 && doorway) { f.kinds[i]=AirGrid.SOLID; }
            f.shares[i]=!solid && !inside?1:0;
        }
        return f;
    }
    public static void main(String[] args) throws Exception {
        if (args.length>0 && args[0].equals("fork")) { runFork(Path.of(args[1]),Integer.parseInt(args[2])); return; }
        String label=args.length==0?"baseline":args[0];
        if (!label.matches("[A-Za-z0-9_-]{1,48}")) { throw new IllegalArgumentException("Use a short alphanumeric benchmark label"); }
        String stamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
        Path directory=Path.of("build","benchmarks","thermal",label+"-"+stamp).toAbsolutePath();
        Files.createDirectories(directory);
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        if (!Files.exists(Path.of(java))) { java=Path.of(System.getProperty("java.home"),"bin","java").toString(); }
        for (int fork=1;fork<=FORKS;fork++) {
            Path arguments=directory.resolve("fork-"+fork+".args");
            String classpath=System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"");
            Files.writeString(arguments,"-Xms512m\n-Xmx512m\n-XX:+UseG1GC\n-cp\n\""+classpath+"\"\n"+
                    ThermalBenchmark.class.getName()+"\nfork\n\""+directory.toString().replace("\\","/")+"\"\n"+fork+"\n");
            int result=new ProcessBuilder(java,"@"+arguments).inheritIO().start().waitFor();
            if (result!=0) { throw new IllegalStateException("Benchmark fork failed: "+result); }
        }
        summarize(directory,args.length>1 && !args[1].isBlank()?Path.of(args[1]):null);
        System.out.println("Thermal baseline reports: "+directory);
    }
    private static void runFork(Path directory,int fork) throws Exception {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("writtenBy","ChatGPT"); report.put("schema",VERSION); report.put("fork",fork);
        report.put("timeUtc",Instant.now().toString()); report.put("java",System.getProperty("java.runtime.version"));
        report.put("vm",System.getProperty("java.vm.name")); report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("cpu",System.getenv("PROCESSOR_IDENTIFIER")); report.put("heapBytes",Runtime.getRuntime().maxMemory());
        report.put("vmArguments",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("revision",git("rev-parse","HEAD")); report.put("dirty",!git("status","--porcelain").isBlank());
        report.put("minimumWarmupOperations",WARMUP);report.put("minimumWarmupWallMs",300);
        report.put("samplesPerPhase",SAMPLES);report.put("operationsPerSample",BATCH);
        report.put("sampleUnit","Per-operation averages of ten-operation batches; CPU timer resolution is coarse on some Windows JVMs. Percentiles describe batch averages, not individual operation latency.");
        report.put("ambientWindMps",List.of(6,2)); report.put("airParameters",AirParams.DEFAULT.toString());
        List<Map<String,Object>> rows=new ArrayList<>();
        for (Scene scene:SCENES) {
            Fixture fixture=fixture(scene);
            measure(rows,scene,"grid-create",0,()->{ sink=fixture.grid(); return null; });
            for (int seconds:new int[]{1,10}) {
                AirGrid grid=fixture.grid();
                measure(rows,scene,"step-"+seconds+"s",seconds,()->{ grid.step(seconds); return grid; });
            }
            AirGrid grid=fixture.grid();
            measure(rows,scene,"geometry-apply-and-step-1s",1,()->{ fixture.apply(grid); grid.step(1); return grid; });
            measure(rows,scene,"publication",0,()->{
                float[] offsets=new float[scene.cells()],wind=new float[scene.cells()]; byte[] kinds=new byte[scene.cells()];
                grid.exportOffsets(offsets);grid.exportKinds(kinds);grid.exportWindShares(wind);
                sink=new Object[]{offsets,kinds,wind};return grid;
            });
        }
        report.put("results",rows);
        Files.writeString(directory.resolve("fork-"+fork+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        StringBuilder csv=new StringBuilder("scene,cells,phase,simulated_seconds,wall_mean_ms,wall_p50_ms,wall_p95_ms,cpu_mean_ms,allocated_mean_bytes\n");
        for (var row:rows) {
            var wall=(ThermalTiming.Summary)row.get("wallMs"); var cpu=(ThermalTiming.Summary)row.get("cpuMs");
            var allocated=(ThermalTiming.Summary)row.get("allocatedBytes");
            csv.append(String.format(Locale.ROOT,"%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.0f%n",row.get("scene"),row.get("cells"),row.get("phase"),
                    row.get("simulatedSeconds"),wall.mean(),wall.p50(),wall.p95(),cpu.mean(),allocated.mean()));
        }
        Files.writeString(directory.resolve("fork-"+fork+".csv"),csv);
        System.out.println("Thermal benchmark fork "+fork+" complete ("+rows.size()+" workloads)");
    }
    private static void measure(List<Map<String,Object>> rows,Scene scene,String phase,int seconds,Supplier<AirGrid> operation) {
        long warmupUntil=System.nanoTime()+300_000_000L;
        for (int i=0;i<WARMUP || System.nanoTime()<warmupUntil;i++) { operation.get(); }
        var wall=new ThermalTiming.Samples(); var cpu=new ThermalTiming.Samples(); var allocated=new ThermalTiming.Samples();
        for (int i=0;i<SAMPLES;i++) {
            long a=ThermalTiming.allocated(),c=ThermalTiming.cpu(),t=System.nanoTime();
            AirGrid grid=null;
            for(int batch=0;batch<BATCH;batch++) { grid=operation.get(); }
            wall.add((System.nanoTime()-t)/BATCH);
            long cpuNanos=ThermalTiming.difference(c,ThermalTiming.cpu()),bytes=ThermalTiming.difference(a,ThermalTiming.allocated());
            cpu.add(cpuNanos<0?-1:cpuNanos/BATCH);allocated.add(bytes<0?-1:bytes/BATCH);
            if (grid!=null) { sink=grid.offset(scene.nx/2,3,scene.nz/2); }
        }
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("scene",scene.name);row.put("dimensions",List.of(scene.nx,scene.ny,scene.nz));row.put("cells",scene.cells());
        var fixture=fixture(scene);
        row.put("geometryFingerprint",java.util.Objects.hash(java.util.Arrays.hashCode(fixture.kinds),java.util.Arrays.hashCode(fixture.open),java.util.Arrays.hashCode(fixture.shares)));
        row.put("phase",phase);row.put("simulatedSeconds",seconds);row.put("wallMs",wall.summary(1e6));
        row.put("cpuMs",cpu.summary(1e6));row.put("allocatedBytes",allocated.summary(1));rows.add(row);
    }
    static void summarize(Path directory,Path baseline) throws Exception {
        Map<String,com.google.gson.JsonObject> old=new LinkedHashMap<>();
        if(baseline!=null) {
            Path file=Files.isDirectory(baseline)?baseline.resolve("summary.json"):baseline;
            var previous=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(!previous.get("schema").getAsString().equals(VERSION)) { throw new IllegalArgumentException("Benchmark schema differs; rerun the baseline with the same harness"); }
            for(var row:previous.getAsJsonArray("results")) {
                var value=row.getAsJsonObject();old.put(value.get("scene").getAsString()+"/"+value.get("phase").getAsString(),value);
            }
        }
        Map<String,List<com.google.gson.JsonObject>> groups=new LinkedHashMap<>();
        for(int fork=1;fork<=FORKS;fork++) {
            var report=JsonParser.parseString(Files.readString(directory.resolve("fork-"+fork+".json"))).getAsJsonObject();
            for(var entry:report.getAsJsonArray("results")) {
                var row=entry.getAsJsonObject();String key=row.get("scene").getAsString()+"/"+row.get("phase").getAsString();
                groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);
            }
        }
        List<Map<String,Object>> results=new ArrayList<>();
        StringBuilder csv=new StringBuilder("scene,cells,phase,wall_median_fork_mean_ms,wall_min_fork_mean_ms,wall_max_fork_mean_ms,cpu_median_fork_mean_ms,baseline_wall_ratio\n");
        for(var group:groups.entrySet()) {
            var first=group.getValue().getFirst();
            double[] means=group.getValue().stream().mapToDouble(v->v.getAsJsonObject("wallMs").get("mean").getAsDouble()).sorted().toArray();
            double[] cpus=group.getValue().stream().filter(v->v.getAsJsonObject("cpuMs").has("mean"))
                    .mapToDouble(v->v.getAsJsonObject("cpuMs").get("mean").getAsDouble()).sorted().toArray();
            Map<String,Object> row=new LinkedHashMap<>();row.put("scene",first.get("scene").getAsString());row.put("cells",first.get("cells").getAsInt());
            row.put("phase",first.get("phase").getAsString());row.put("geometryFingerprint",first.get("geometryFingerprint").getAsInt());
            row.put("wallMedianForkMeanMs",means[means.length/2]);row.put("wallMinForkMeanMs",means[0]);row.put("wallMaxForkMeanMs",means[means.length-1]);
            row.put("cpuMedianForkMeanMs",cpus.length==0?null:cpus[cpus.length/2]);
            Double ratio=null;
            if(old.containsKey(group.getKey())) {
                var before=old.get(group.getKey());
                if(before.get("geometryFingerprint").getAsInt()!=first.get("geometryFingerprint").getAsInt()) { throw new IllegalStateException("Geometry differs for "+group.getKey()); }
                ratio=means[means.length/2]/before.get("wallMedianForkMeanMs").getAsDouble();
            }
            row.put("baselineWallRatio",ratio);results.add(row);
            csv.append(String.format(Locale.ROOT,"%s,%s,%s,%.6f,%.6f,%.6f,%s,%s%n",row.get("scene"),row.get("cells"),row.get("phase"),means[means.length/2],means[0],means[means.length-1],row.get("cpuMedianForkMeanMs"),ratio));
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("writtenBy","ChatGPT");report.put("schema",VERSION);report.put("forks",FORKS);
        report.put("aggregation","Median of per-fork means; min/max expose fork variability. Individual fork JSON retains percentiles and allocation counts.");
        report.put("comparedTo",baseline==null?null:baseline.toAbsolutePath().toString());report.put("results",results);
        Files.writeString(directory.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        Files.writeString(directory.resolve("summary.csv"),csv);
    }

    private static String git(String... args) {
        try {
            List<String> command=new ArrayList<>();command.add("git");command.addAll(List.of(args));
            Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
            String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
            return process.waitFor()==0?result:"unavailable";
        } catch (Exception e) { return "unavailable"; }
    }
    private ThermalBenchmark() { }
}
