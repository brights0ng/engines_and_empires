// Written by ChatGPT; reuses the benchmark fixture adapted from AirGridTest (original fixture authorship unmarked).
package dev.brights0ng.paradynelib.heat.air;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Matched physical-time refinement and domain-padding evidence. No tuning or production defaults change. */
final class CoupledAirQualification {
    private static final int HEAT=600,COOL=80,WINDOW_START=540;
    record Outcome(Map<String,Object> row,double[] heated,double[] cooled,double[] mean,boolean[] air) { }

    static int substeps(double dt) {
        if(!Double.isFinite(dt) || dt<=0 || dt>1) { throw new IllegalArgumentException("Invalid physical timestep"); }
        int n=(int)Math.rint(1/dt);
        if(n<1 || n>16 || n*dt!=1) { throw new IllegalArgumentException("Timestep must partition a physical second into at most 16 steps"); }
        return n;
    }

    static List<Object> timesteps(ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,Consumer<Map<String,Object>> checkpoint) {
        var rows=new ArrayList<Object>();
        for(int opening=0;opening<3;opening++) {
            var h=CoupledAirBenchmark.house(opening);var cases=new ArrayList<Outcome>();
            for(double dt:new double[]{1,.5,.25}) {
                var c=run(h,opening,6,6,dt,mode,cacheOrigins,kernel);cases.add(c);checkpoint.accept(c.row);
            }
            var reference=cases.getLast();
            for(var c:cases) {
                c.row.put("comparisonReferenceStep",.25);c.row.put("differenceFromReference",difference(c,reference));rows.add(c.row);
            }
        }
        return rows;
    }

    static List<Object> boundaries(ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,double dt,Consumer<Map<String,Object>> checkpoint) {
        substeps(dt);var rows=new ArrayList<Object>();
        for(int opening=0;opening<3;opening++) {
            var a=run(CoupledAirBenchmark.house(opening,8,8),opening,8,8,dt,mode,cacheOrigins,kernel);
            checkpoint.accept(a.row);
            var b=run(CoupledAirBenchmark.house(opening,16,16),opening,16,16,dt,mode,cacheOrigins,kernel);
            checkpoint.accept(b.row);
            for(var c:List.of(a,b)) {
                c.row.put("comparisonReferencePadding",16);c.row.put("differenceFromReference",difference(c,b));rows.add(c.row);
            }
        }
        return rows;
    }

    /** Compare these rows with the padding-8, ground-depth-3 rows in the boundary report. */
    static List<Object> ground(ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,double dt,Consumer<Map<String,Object>> checkpoint) {
        substeps(dt);var rows=new ArrayList<Object>();
        for(int opening=0;opening<3;opening++) {
            var c=run(CoupledAirBenchmark.house(opening,8,8,6),opening,8,8,dt,mode,cacheOrigins,kernel);
            rows.add(c.row);checkpoint.accept(c.row);
        }
        return rows;
    }

    static List<Object> one(ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,double dt,String specification) {
        String[] parts=specification.split(",");
        if(parts.length!=4 && parts.length!=5) { throw new IllegalArgumentException("Case is opening,padding,topPadding,groundDepth[,responseScale]"); }
        int opening=Integer.parseInt(parts[0]),padding=Integer.parseInt(parts[1]),top=Integer.parseInt(parts[2]),ground=Integer.parseInt(parts[3]);
        double scale=parts.length==5?ThermalClock.requireScale(Double.parseDouble(parts[4])):1;
        return List.of(run(CoupledAirBenchmark.house(opening,padding,top,ground),opening,padding,top,dt,mode,cacheOrigins,kernel,scale).row);
    }

    static List<Object> responses(ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,double dt,Consumer<Map<String,Object>> checkpoint) {
        substeps(dt);
        var rows=new ArrayList<Object>();
        for(int opening=0;opening<3;opening++) for(double scale:new double[]{.1,.5,1,2,10}) {
            var c=run(CoupledAirBenchmark.house(opening),opening,6,6,dt,mode,cacheOrigins,kernel,scale);
            rows.add(c.row);checkpoint.accept(c.row);
        }
        return rows;
    }

    private static Outcome run(CoupledAirBenchmark.House h,int opening,int padding,int topPadding,double dt,
                               ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel) {
        return run(h,opening,padding,topPadding,dt,mode,cacheOrigins,kernel,1);
    }

    private static Outcome run(CoupledAirBenchmark.House h,int opening,int padding,int topPadding,double dt,
                               ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,double scale) {
        ThermalClock.requireScale(scale);
        int perSecond=substeps(dt);var f=h.flow();
        var grid=new CoupledAirGrid(h.thermal(),FlowBoundary.wind(f,0,0,0),FlowSettings.DEFAULT,
                ThermalTransport.Settings.defaults(),mode,cacheOrigins,kernel);
        for(int i=0;i<h.thermal().states();i++) { grid.setTemperature(i,20); }
        if(!grid.initialize(0,0,0).ready()) { throw new IllegalStateException("Qualification initialization failed"); }
        grid.setSources(new ThermalTransport.Source(f.nodeAt(h.mid()+.5,h.y0()+1.5,h.mid()+.5),75,Double.NaN));
        double[] bg=new double[f.nodes()],ext=new double[f.faces()];Arrays.fill(bg,20);Arrays.fill(ext,20);
        int cells=(h.x1()-h.x0()+1)*(h.x1()-h.x0()+1)*(h.y1()-h.y0()+1);
        boolean[] air=new boolean[cells];
        double[] field=new double[cells*4],previous=field.clone(),older=field.clone(),mean=field.clone();
        double[] minimum=field.clone(),maximum=field.clone();Arrays.fill(minimum,Double.POSITIVE_INFINITY);Arrays.fill(maximum,Double.NEGATIVE_INFINITY);
        double[] heated=null,cooled=null;
        double initialEnergy=grid.energy(),sourceEnergy=0,exteriorEnergy=0,maxError=0,maxDivergence=0,maxVelocityCurvature=0;
        int accepted=0,completed=0,lateSamples=0,iterations=0,sweeps=0;String failure="";
        var samples=new ArrayList<Object>();var series=new ArrayList<Object>();var timing=new ThermalTiming.Samples();
        samples.add(sample(h,grid,0));
        outer:for(int second=1;second<=HEAT+COOL;second++) {
            if(second==HEAT+1) { grid.setSources(); }
            for(int sub=1;sub<=perSecond;sub++) {
                var step=grid.step(dt,scale,bg,ext);
                if(!step.ready()) { failure=step.toString();break outer; }
                accepted++;timing.add(step.nanos());sourceEnergy+=step.sourceEnergy();exteriorEnergy+=step.exteriorEnergy();
                maxError=Math.max(maxError,Math.abs(step.energyError()));maxDivergence=Math.max(maxDivergence,step.flow().pressure().maxDivergence());
                iterations=Math.max(iterations,step.flow().pressure().iterations());sweeps=Math.max(sweeps,Math.max(step.first().sweeps(),step.second().sweeps()));
                double time=second-1+sub*dt;
                if(time>=WINDOW_START && time<=HEAT) {
                    field(h,grid,field,air);
                    double weight=(time==WINDOW_START || time==HEAT)?.5*dt:dt;
                    for(int i=0;i<field.length;i++) {
                        mean[i]+=weight*field[i]/(HEAT-WINDOW_START);
                        minimum[i]=Math.min(minimum[i],field[i]);maximum[i]=Math.max(maximum[i],field[i]);
                        if(i%4!=0 && lateSamples>=2) { maxVelocityCurvature=Math.max(maxVelocityCurvature,Math.abs(field[i]-2*previous[i]+older[i])); }
                    }
                    System.arraycopy(previous,0,older,0,field.length);System.arraycopy(field,0,previous,0,field.length);lateSamples++;
                    var point=new LinkedHashMap<String,Object>();point.put("physicalSecond",time);
                    point.put("plumeVerticalSpeed",grid.componentVelocity(f.nodeAt(h.mid()+.5,h.y0()+2.5,h.mid()+.5),1));
                    point.put("roomOffsetC",h.mean(grid));series.add(point);
                }
            }
            completed=second;
            if(second==15 || second==60 || second==120 || second==240 || second==300 || second==360 || second==600 || second==640 || second==680) {
                samples.add(sample(h,grid,second));
            }
            if(second==HEAT) { heated=new double[field.length];field(h,grid,heated,air); }
            if(second%100==0 || second==HEAT+COOL) {
                System.out.println("qualification opening="+opening+" padding="+padding+" dt="+dt+" scale="+scale+" second="+second+" room="+h.mean(grid));
            }
        }
        if(completed==HEAT+COOL) { cooled=new double[field.length];field(h,grid,cooled,air); }
        var row=new LinkedHashMap<String,Object>();row.put("opening",List.of("sealed","window","missing-wall").get(opening));
        row.put("physicalStep",dt);row.put("responseScale",scale);row.put("sourcePower",75);row.put("sourceLimit","unlimited");
        row.put("horizontalPadding",padding);row.put("topPadding",topPadding);row.put("groundDepth",h.y0()-1);
        row.put("nx",f.nx);row.put("ny",f.ny);row.put("nz",f.nz);row.put("states",h.thermal().states());row.put("faces",f.faces());
        row.put("geometryFingerprint",Arrays.hashCode(f.area)*31L+Arrays.hashCode(f.nodeCell));
        row.put("completedPhysicalSeconds",completed);row.put("acceptedSteps",accepted);row.put("firstFailure",failure);
        row.put("advancedPhysicalSeconds",accepted*dt);
        row.put("advancedThermalSeconds",accepted*dt*scale);row.put("deliveredSourceEnergy",sourceEnergy);
        row.put("heatingSeconds",HEAT);row.put("coolingSeconds",COOL);row.put("samples",samples);
        row.put("lateWindowStart",WINDOW_START);row.put("lateWindowEnd",HEAT);row.put("lateProbeSeries",series);
        row.put("lateWindowSamples",lateSamples);row.put("lateWindowComplete",heated!=null);
        row.put("houseFieldOrder","z/y/x within house bounds; per cell: absolute temperature, vx, vy, vz; material velocity is zero");
        row.put("houseAirMask",air);row.put("houseFieldAt600",heated);row.put("houseFieldAt680",cooled);row.put("houseFieldLateMean",mean);
        double tempRange=0,velocityRange=0;
        for(int i=0;i<field.length;i++) {
            if(i%4==0) { tempRange=Math.max(tempRange,maximum[i]-minimum[i]); }
            else { velocityRange=Math.max(velocityRange,maximum[i]-minimum[i]); }
        }
        row.put("lateMaxHouseTemperatureRangeC",lateSamples==0?null:tempRange);
        row.put("lateMaxHouseVelocityRange",lateSamples==0?null:velocityRange);
        row.put("lateMaxVelocitySecondDifference",lateSamples<3?null:maxVelocityCurvature);
        row.put("lateMaxVelocitySecondDifferenceOverDtSquared",lateSamples<3?null:maxVelocityCurvature/(dt*dt));
        row.put("maxStepEnergyError",maxError);row.put("maxDivergence",maxDivergence);
        row.put("totalEnergyBudgetError",grid.energy()-initialEnergy-sourceEnergy-exteriorEnergy);
        row.put("maxPressureIterations",iterations);row.put("maxThermalHalfSweeps",sweeps);row.put("stepWallMs",timing.summary(1e6));
        return new Outcome(row,heated,cooled,mean,air);
    }

    static void field(CoupledAirBenchmark.House h,CoupledAirGrid grid,double[] values,boolean[] air) {
        int cell=0;
        for(int z=h.x0();z<=h.x1();z++) for(int y=h.y0();y<=h.y1();y++) for(int x=h.x0();x<=h.x1();x++) {
            int node=h.flow().nodeAt(x+.5,y+.5,z+.5),state=node>=0?node:h.thermal().materialState(h.flow().index(x,y,z));
            air[cell]=node>=0;values[cell*4]=grid.temperature(state);
            for(int axis=0;axis<3;axis++) { values[cell*4+axis+1]=node>=0?grid.componentVelocity(node,axis):0; }
            cell++;
        }
    }

    private static Map<String,Object> difference(Outcome a,Outcome b) {
        var result=new LinkedHashMap<String,Object>();
        if(a.heated==null || a.cooled==null || b.heated==null || b.cooled==null) { result.put("available",false);return result; }
        if(!Arrays.equals(a.air,b.air)) { throw new IllegalStateException("Different house occupancy in comparison"); }
        result.put("available",true);result.put("at600",error(a.heated,b.heated,a.air));
        result.put("at680",error(a.cooled,b.cooled,a.air));result.put("lateMean",error(a.mean,b.mean,a.air));return result;
    }

    static Map<String,Object> error(double[] a,double[] b,boolean[] air) {
        double sumT=0,maxT=0,sumV=0,maxV=0;int velocities=0;
        for(int i=0;i<air.length;i++) {
            double t=Math.abs(a[i*4]-b[i*4]);sumT+=t*t;maxT=Math.max(maxT,t);
            if(air[i]) for(int axis=1;axis<=3;axis++) {
                double v=Math.abs(a[i*4+axis]-b[i*4+axis]);sumV+=v*v;maxV=Math.max(maxV,v);velocities++;
            }
        }
        var result=new LinkedHashMap<String,Object>();result.put("maxTemperatureC",maxT);result.put("rmsTemperatureC",Math.sqrt(sumT/air.length));
        result.put("maxVelocity",maxV);result.put("rmsVelocity",velocities==0?0:Math.sqrt(sumV/velocities));return result;
    }

    private static Map<String,Object> sample(CoupledAirBenchmark.House h,CoupledAirGrid grid,int second) {
        var result=new LinkedHashMap<String,Object>();result.put("physicalSecond",second);result.put("roomOffsetC",h.mean(grid));
        result.put("materialOffsetC",h.materialMean(grid));
        result.put("nearSourceOffsetC",grid.temperature(h.flow().nodeAt(h.mid()+1.5,h.y0()+1.5,h.mid()+.5))-20);return result;
    }
}
