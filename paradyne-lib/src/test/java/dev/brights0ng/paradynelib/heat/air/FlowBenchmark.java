// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Companion to the frozen thermal baseline. Combined means wind + supplied-temperature buoyancy,
 * not coupled heat transport. Failed/unaccepted candidates are always reported, never timed as valid progress.
 */
public final class FlowBenchmark {
    private static volatile Object sink;
    private record Scene(String name,int nx,int ny,int nz,int layout) { int cells() { return nx*ny*nz; } }
    private static final List<Scene> SCENES=List.of(new Scene("multiroom32",32,32,32,4),
            new Scene("stress48",48,48,48,4),new Scene("row50",64,8,8,0),
            new Scene("sealed-house",24,16,24,1),new Scene("partial-block-house",24,16,24,3));

    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("fork")) {
            run(Path.of(args[1]),Integer.parseInt(args[2]),Boolean.parseBoolean(args[3]),PressureProjector.Preconditioner.valueOf(args[4]));return;
        }
        boolean quick=args.length>0 && Boolean.parseBoolean(args[0]);int forks=quick?1:3;
        var method=PressureProjector.Preconditioner.valueOf(args.length>1?args[1]:"MODIFIED_INCOMPLETE_CHOLESKY");
        String stamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
        Path directory=Path.of("build","benchmarks","flow",stamp).toAbsolutePath();Files.createDirectories(directory);
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        if(!Files.exists(Path.of(java))) { java=Path.of(System.getProperty("java.home"),"bin","java").toString(); }
        for(int f=1;f<=forks;f++) {
            Path arguments=directory.resolve("fork-"+f+".args");
            String cp=System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"");
            Files.writeString(arguments,"-Xms512m\n-Xmx1g\n-XX:+UseG1GC\n-cp\n\""+cp+"\"\n"+
                    FlowBenchmark.class.getName()+"\nfork\n\""+directory.toString().replace("\\","/")+"\"\n"+f+"\n"+quick+"\n"+method+"\n");
            int code=new ProcessBuilder(java,"@"+arguments).inheritIO().start().waitFor();
            if(code!=0) { throw new IllegalStateException("Flow benchmark fork failed: "+code); }
        }
        System.out.println("Flow benchmark reports: "+directory);
    }

    private static ThermalBenchmark.Fixture thermal(Scene s) {
        return ThermalBenchmark.fixture(new ThermalBenchmark.Scene(s.name,s.nx,s.ny,s.nz,s.layout));
    }
    private static FlowGeometry geometry(Scene s,ThermalBenchmark.Fixture fixture) {
        var b=new FlowGeometry.Builder(s.nx,s.ny,s.nz).openExterior(true).revision(1);
        var partial=new FlowShape.Builder().solidBox(0,0,0,16,2,16).build();
        for(int z=0;z<s.nz;z++) for(int y=0;y<s.ny;y++) for(int x=0;x<s.nx;x++) {
            int i=(z*s.ny+y)*s.nx+x;
            if(fixture.kinds()[i]==AirGrid.SOLID) { b.cell(x,y,z,fixture.open()[i]!=0?partial:FlowShape.FULL); }
            if(s.name.equals("row50") && x>=7 && x<57 && y==3 && z==4) { b.cell(x,y,z,FlowShape.FULL); }
        }
        return b.build();
    }
    private static void run(Path directory,int fork,boolean quick,PressureProjector.Preconditioner method) throws Exception {
        int warm=quick?1:5,samples=quick?2:10;
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("writtenBy","ChatGPT");report.put("schema","pure-flow-v2");report.put("fork",fork);report.put("quick",quick);
        report.put("preconditioner",method);
        report.put("java",System.getProperty("java.runtime.version"));report.put("cpu",System.getenv("PROCESSOR_IDENTIFIER"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("warmupSteps",warm);report.put("sampleSteps",samples);
        report.put("qualityContract","All accepted steps: max volume-normalized divergence <= 1e-7/s. Failures retain previous field; no heat transport.");
        report.put("settings",FlowSettings.DEFAULT.toString());
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Scene s:SCENES) {
            var fixture=thermal(s);long alloc=ThermalTiming.allocated(),start=System.nanoTime();
            var g=geometry(s,fixture);long prep=System.nanoTime()-start,bytes=ThermalTiming.difference(alloc,ThermalTiming.allocated());
            // Existing thermal fixture/control remains unchanged, including the partial-door legacy bitmask.
            var control=fixture.grid();for(int i=0;i<10;i++) { control.step(1); }
            var thermalWall=new ThermalTiming.Samples();var thermalCpu=new ThermalTiming.Samples();
            for(int i=0;i<10;i++) { long cpu=ThermalTiming.cpu();start=System.nanoTime();control.step(1);thermalWall.add(System.nanoTime()-start);thermalCpu.add(ThermalTiming.difference(cpu,ThermalTiming.cpu())); }
            for(String mode:List.of("wind","buoyancy","combined","storm")) for(double dt:new double[]{.25,.5,1}) for(int cap:new int[]{64,512}) {
                boolean wind=!mode.equals("buoyancy");double windX=mode.equals("storm")?20:wind?6:0,windZ=mode.equals("storm")?6:wind?2:0;
                var settings=new FlowSettings(cap,1e-8,1e-10,1e-7,1,.05,1,100,3000);
                var boundary=FlowBoundary.wind(g,windX,0,windZ);
                alloc=ThermalTiming.allocated();start=System.nanoTime();var flow=new FlowGrid(g,boundary,settings,method);
                long coefficients=System.nanoTime()-start,solverBytes=ThermalTiming.difference(alloc,ThermalTiming.allocated());
                flow.seedUniform(windX,0,windZ);start=System.nanoTime();var initial=flow.projectInitial();long initialNanos=System.nanoTime()-start;
                double[] bg=new double[g.nodes()];Arrays.fill(bg,20);double[] t=bg.clone();
                if(mode.equals("buoyancy") || mode.equals("combined")) {
                    for(int n=0;n<t.length;n++) { if(Math.abs(g.cx[n]-s.nx*.5)<1.5 && Math.abs(g.cz[n]-s.nz*.5)<1.5 && g.cy[n]<6) { t[n]=80; } }
                }
                var wall=new ThermalTiming.Samples();var cpu=new ThermalTiming.Samples();var transport=new ThermalTiming.Samples();
                var pressure=new ThermalTiming.Samples();var allocated=new ThermalTiming.Samples();
                int accepted=0,rejected=0,maxIterations=0,blocked=0;double maxDiv=0,maxResidual=0,maxImbalance=0;
                String lastStatus=initial.toString();
                if(initial.ready()) {
                    for(int i=-warm;i<samples;i++) {
                        long c=ThermalTiming.cpu();alloc=ThermalTiming.allocated();start=System.nanoTime();
                        var result=flow.step(dt,t,bg);long elapsed=System.nanoTime()-start;
                        long usedCpu=ThermalTiming.difference(c,ThermalTiming.cpu()),usedBytes=ThermalTiming.difference(alloc,ThermalTiming.allocated());
                        if(i<0) { continue; }
                        wall.add(elapsed);cpu.add(usedCpu);allocated.add(usedBytes);transport.add(result.transportNanos());pressure.add(result.pressureNanos());
                        if(result.ready()) { accepted++; } else { rejected++; }
                        lastStatus=result.status()+"/"+(result.pressure()==null?"none":result.pressure().status());blocked+=result.blockedTraces();
                        if(result.pressure()!=null) {
                            maxIterations=Math.max(maxIterations,result.pressure().iterations());maxDiv=Math.max(maxDiv,result.pressure().maxDivergence());
                            maxResidual=Math.max(maxResidual,result.pressure().finalResidual());maxImbalance=Math.max(maxImbalance,Math.abs(result.pressure().boundaryImbalance()));
                        }
                    }
                }
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("scene",s.name);row.put("cells",s.cells());row.put("nodes",g.nodes());row.put("faces",g.faces());row.put("components",g.components());
                row.put("thermalGeometryFingerprint",java.util.Objects.hash(Arrays.hashCode(fixture.kinds()),Arrays.hashCode(fixture.open()),Arrays.hashCode(fixture.shares())));
                row.put("flowGeometryFingerprint",java.util.Objects.hash(Arrays.hashCode(g.volume),Arrays.hashCode(g.low),Arrays.hashCode(g.high),Arrays.hashCode(g.area)));
                row.put("mode",mode);row.put("dt",dt);row.put("pressureCap",cap);row.put("geometryMs",prep/1e6);row.put("geometryAllocatedBytes",bytes);
                row.put("solverCreateMs",coefficients/1e6);row.put("solverAllocatedBytes",solverBytes);row.put("initialMs",initialNanos/1e6);
                row.put("preconditioner",method);row.put("incompleteFactorActive",flow.usesIncompleteCholesky());
                row.put("initialStatus",initial.status());row.put("initialQuality",initial.pressure());
                row.put("accepted",accepted);row.put("rejected",rejected);row.put("lastStatus",lastStatus);
                row.put("maxIterations",maxIterations);row.put("maxDivergence",maxDiv);row.put("maxResidual",maxResidual);row.put("maxBoundaryImbalance",maxImbalance);
                row.put("blockedTraces",blocked);row.put("wallMs",wall.summary(1e6));row.put("cpuMs",cpu.summary(1e6));
                row.put("transportMs",transport.summary(1e6));row.put("pressureMs",pressure.summary(1e6));row.put("allocatedBytes",allocated.summary(1));
                row.put("thermalControlWallMs",thermalWall.summary(1e6));row.put("thermalControlCpuMs",thermalCpu.summary(1e6));rows.add(row);sink=flow;
            }
            System.out.println("Flow fork "+fork+": "+s.name+" complete");
        }
        report.put("results",rows);Files.writeString(directory.resolve("fork-"+fork+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        StringBuilder csv=new StringBuilder("scene,mode,dt,cap,nodes,faces,initial_status,accepted,rejected,wall_mean_ms,cpu_mean_ms,transport_mean_ms,pressure_mean_ms,max_iterations,max_divergence,max_residual,max_boundary_imbalance,geometry_ms,solver_bytes,allocated_mean_bytes,thermal_control_wall_ms\n");
        for(var row:rows) {
            csv.append(String.format(Locale.ROOT,"%s,%s,%s,%s,%s,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%s,%.9g,%.9g,%.9g,%.6f,%s,%.0f,%.6f%n",
                    row.get("scene"),row.get("mode"),row.get("dt"),row.get("pressureCap"),row.get("nodes"),row.get("faces"),row.get("initialStatus"),row.get("accepted"),row.get("rejected"),
                    ((ThermalTiming.Summary)row.get("wallMs")).mean(),((ThermalTiming.Summary)row.get("cpuMs")).mean(),
                    ((ThermalTiming.Summary)row.get("transportMs")).mean(),((ThermalTiming.Summary)row.get("pressureMs")).mean(),row.get("maxIterations"),
                    row.get("maxDivergence"),row.get("maxResidual"),row.get("maxBoundaryImbalance"),row.get("geometryMs"),row.get("solverAllocatedBytes"),
                    ((ThermalTiming.Summary)row.get("allocatedBytes")).mean(),((ThermalTiming.Summary)row.get("thermalControlWallMs")).mean()));
        }
        Files.writeString(directory.resolve("fork-"+fork+".csv"),csv);
    }
}
