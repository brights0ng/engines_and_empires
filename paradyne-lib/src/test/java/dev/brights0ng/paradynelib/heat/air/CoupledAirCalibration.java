// Written by ChatGPT; reuses the existing CoupledAirBenchmark house adapted from AirGridTest.
package dev.brights0ng.paradynelib.heat.air;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Offline physical-time calibration. No changes to production coefficients, sources or solver settings. */
final class CoupledAirCalibration {
    static final int WINDOW_SECONDS=100, REQUIRED_WINDOWS=6, MAX_HEAT_SECONDS=6000, COOL_SECONDS=900;
    static final double TEMPERATURE_DRIFT=.001, FLUX_DRIFT=1e-5, STORAGE_RATE=.001;
    static final double ROOM_WINDOW_RANGE=.01;
    record Protocol(int windowSeconds,int requiredWindows,int maxHeatSeconds) {
        Protocol {
            if(windowSeconds<1 || requiredWindows<1 || (long)windowSeconds*requiredWindows>maxHeatSeconds) {
                throw new IllegalArgumentException("Equilibrium windows must fit the positive work cap");
            }
        }
        static Protocol named(String name) {
            return switch(name) {
                case "short" -> new Protocol(WINDOW_SECONDS,REQUIRED_WINDOWS,MAX_HEAT_SECONDS);
                case "long" -> new Protocol(1000,3,10000);
                default -> throw new IllegalArgumentException("Unknown equilibrium protocol: "+name);
            };
        }
    }

    static final class SteadyMonitor {
        private final int required;
        private int stable;
        SteadyMonitor() { this(REQUIRED_WINDOWS); }
        SteadyMonitor(int required) { this.required=required; }
        boolean observe(double coupledTemperatureChange,double legacyTemperatureChange,double fluxChange,double storageRate) {
            return observe(coupledTemperatureChange,legacyTemperatureChange,fluxChange,storageRate,0,0,0);
        }
        boolean observe(double coupledTemperatureChange,double legacyTemperatureChange,double fluxChange,double storageRate,
                        double coupledRange,double legacyRange,double fluxRange) {
            if(within(coupledTemperatureChange,TEMPERATURE_DRIFT) && within(legacyTemperatureChange,TEMPERATURE_DRIFT)
                    && within(fluxChange,FLUX_DRIFT) && within(storageRate,STORAGE_RATE)
                    && within(coupledRange,TEMPERATURE_DRIFT) && within(legacyRange,TEMPERATURE_DRIFT)
                    && within(fluxRange,FLUX_DRIFT)) { stable++; }
            else { stable=0; }
            return stable>=required;
        }
        int stableWindows() { return stable; }
        private static boolean within(double value,double bound) { return Double.isFinite(value) && value>=0 && value<=bound; }
    }

    /** Qualifies thermal statistics separately from instantaneous flow, which may retain a bounded cycle. */
    static final class ThermalMonitor {
        private final int required;
        private int stable;
        ThermalMonitor() { this(REQUIRED_WINDOWS); }
        ThermalMonitor(int required) { this.required=required; }
        boolean observe(double coupledMeanChange,double legacyMeanChange,double storageRate,double roomRange) {
            if(SteadyMonitor.within(coupledMeanChange,TEMPERATURE_DRIFT)
                    && SteadyMonitor.within(legacyMeanChange,TEMPERATURE_DRIFT)
                    && SteadyMonitor.within(storageRate,STORAGE_RATE)
                    && SteadyMonitor.within(roomRange,ROOM_WINDOW_RANGE)) { stable++; }
            else { stable=0; }
            return stable>=required;
        }
        int stableWindows() { return stable; }
    }

    static double trailingMean(double[] values,int completed,int window) {
        double sum=0;int first=Math.max(1,completed-window+1);
        for(int i=first;i<=completed;i++) { sum+=values[i]; }
        return sum/(completed-first+1);
    }

    /** First sampled crossing, with 0 as the initial condition. Invalid/nonpositive targets are unavailable. */
    static int firstCrossing(double[] values,int completed,double target,boolean rising) {
        if(!Double.isFinite(target) || target<=0) { return -1; }
        for(int i=0;i<=completed;i++) {
            if(Double.isFinite(values[i]) && (rising?values[i]>=target:values[i]<=target)) { return i; }
        }
        return -1;
    }

    static List<Object> run(ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,Protocol protocol) {
        return run(mode,cacheOrigins,kernel,protocol,1,row->{});
    }

    static List<Object> run(ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,Protocol protocol,
                            double dt,Consumer<Map<String,Object>> checkpoint) {
        CoupledAirQualification.substeps(dt);
        var rows=new ArrayList<Object>();
        for(int opening=0;opening<3;opening++) {
            var row=house(opening,mode,cacheOrigins,kernel,protocol,dt);rows.add(row);checkpoint.accept(row);
        }
        return rows;
    }

    private static Map<String,Object> house(int opening,ThermalTransport.Mode mode,boolean cacheOrigins,FlowGrid.Kernel kernel,Protocol protocol,double dt) {
        int perSecond=CoupledAirQualification.substeps(dt),accepted=0,windowSamples=0;
        int windowSeconds=protocol.windowSeconds(),requiredWindows=protocol.requiredWindows(),maxHeatSeconds=protocol.maxHeatSeconds();
        var h=CoupledAirBenchmark.house(opening);var f=h.flow();
        var grid=new CoupledAirGrid(h.thermal(),FlowBoundary.wind(f,0,0,0),FlowSettings.DEFAULT,
                ThermalTransport.Settings.defaults(),mode,cacheOrigins,kernel);
        for(int i=0;i<h.thermal().states();i++) { grid.setTemperature(i,20); }
        if(!grid.initialize(0,0,0).ready()) { throw new IllegalStateException("Calibration initialization failed"); }
        int source=f.nodeAt(h.mid()+.5,h.y0()+1.5,h.mid()+.5);
        grid.setSources(new ThermalTransport.Source(source,75,Double.NaN));
        double[] bg=new double[f.nodes()],ext=new double[f.faces()];Arrays.fill(bg,20);Arrays.fill(ext,20);
        double[] previousTemperature=new double[h.thermal().states()],previousLegacy=new double[f.shapes.length];
        double[] previousFlux=new double[f.faces()];Arrays.fill(previousTemperature,20);
        double[] sumTemperature=new double[previousTemperature.length],meanTemperature=previousTemperature.clone();
        double[] minTemperature=new double[previousTemperature.length],maxTemperature=minTemperature.clone();
        double[] sumLegacy=new double[previousLegacy.length],meanLegacy=previousLegacy.clone();
        double[] minLegacy=new double[previousLegacy.length],maxLegacy=minLegacy.clone();
        double[] sumFlux=new double[previousFlux.length],meanFlux=previousFlux.clone();
        double[] minFlux=new double[previousFlux.length],maxFlux=minFlux.clone();
        Arrays.fill(minTemperature,Double.POSITIVE_INFINITY);Arrays.fill(maxTemperature,Double.NEGATIVE_INFINITY);
        Arrays.fill(minFlux,Double.POSITIVE_INFINITY);Arrays.fill(maxFlux,Double.NEGATIVE_INFINITY);
        Arrays.fill(minLegacy,Double.POSITIVE_INFINITY);Arrays.fill(maxLegacy,Double.NEGATIVE_INFINITY);
        double[] coupledHistory=new double[maxHeatSeconds+1],legacyHistory=coupledHistory.clone();
        var monitor=new SteadyMonitor(requiredWindows);var thermalMonitor=new ThermalMonitor(requiredWindows);
        var windows=new ArrayList<Object>();var heating=new ArrayList<Object>();
        var cooling=new ArrayList<Object>();var timing=new ThermalTiming.Samples();
        double initialEnergy=grid.energy(),windowEnergy=initialEnergy,sourceEnergy=0,exteriorEnergy=0,maxError=0,maxDivergence=0;
        double windowSource=0,windowExterior=0;
        double roomMinimum=Double.POSITIVE_INFINITY,roomMaximum=Double.NEGATIVE_INFINITY;
        int completed=0;boolean qualified=false;String failure="";
        heating.add(sample(h,grid,0));
        heatingLoop:for(int second=1;second<=maxHeatSeconds;second++) {
          for(int sub=0;sub<perSecond;sub++) {
            var result=grid.step(dt,1,bg,ext);
            if(!result.ready()) { failure=result.toString();break heatingLoop; }
            accepted++;windowSamples++;timing.add(result.nanos());
            sourceEnergy+=result.sourceEnergy();exteriorEnergy+=result.exteriorEnergy();
            windowSource+=result.sourceEnergy();windowExterior+=result.exteriorEnergy();
            maxError=Math.max(maxError,Math.abs(result.energyError()));
            maxDivergence=Math.max(maxDivergence,result.flow().pressure().maxDivergence());
            double room=h.mean(grid);roomMinimum=Math.min(roomMinimum,room);roomMaximum=Math.max(roomMaximum,room);
            for(int i=0;i<sumTemperature.length;i++) {
                double t=grid.temperature(i);sumTemperature[i]+=t*dt;
                minTemperature[i]=Math.min(minTemperature[i],t);maxTemperature[i]=Math.max(maxTemperature[i],t);
            }
            for(int face=0;face<sumFlux.length;face++) {
                double q=grid.faceFlux(face);sumFlux[face]+=q*dt;
                minFlux[face]=Math.min(minFlux[face],q);maxFlux[face]=Math.max(maxFlux[face],q);
            }
          }
            // Keep the legacy control at its historical one-second step.
            h.legacy().step(1);completed=second;
            coupledHistory[second]=h.mean(grid);legacyHistory[second]=h.legacyMean();
            for(int z=0;z<f.nz;z++) for(int y=0;y<f.ny;y++) for(int x=0;x<f.nx;x++) {
                int i=f.index(x,y,z);double t=h.legacy().offset(x,y,z);sumLegacy[i]+=t;
                minLegacy[i]=Math.min(minLegacy[i],t);maxLegacy[i]=Math.max(maxLegacy[i],t);
            }
            if(second%10==0 || second==15) { heating.add(sample(h,grid,second)); }
            if(second%windowSeconds==0) {
                double temperatureChange=0,legacyChange=0,fluxChange=0;
                double meanTemperatureChange=0,meanLegacyChange=0,meanFluxChange=0,temperatureRange=0,legacyRange=0,fluxRange=0;
                for(int i=0;i<previousTemperature.length;i++) {
                    double t=grid.temperature(i);temperatureChange=Math.max(temperatureChange,Math.abs(t-previousTemperature[i]));previousTemperature[i]=t;
                    double mean=sumTemperature[i]/windowSeconds;
                    meanTemperatureChange=Math.max(meanTemperatureChange,Math.abs(mean-meanTemperature[i]));meanTemperature[i]=mean;
                    temperatureRange=Math.max(temperatureRange,maxTemperature[i]-minTemperature[i]);sumTemperature[i]=0;
                }
                for(int z=0;z<f.nz;z++) for(int y=0;y<f.ny;y++) for(int x=0;x<f.nx;x++) {
                    int i=f.index(x,y,z);double t=h.legacy().offset(x,y,z);
                    legacyChange=Math.max(legacyChange,Math.abs(t-previousLegacy[i]));previousLegacy[i]=t;
                    double mean=sumLegacy[i]/windowSeconds;
                    meanLegacyChange=Math.max(meanLegacyChange,Math.abs(mean-meanLegacy[i]));meanLegacy[i]=mean;sumLegacy[i]=0;
                    legacyRange=Math.max(legacyRange,maxLegacy[i]-minLegacy[i]);
                }
                for(int face=0;face<previousFlux.length;face++) {
                    double q=grid.faceFlux(face);fluxChange=Math.max(fluxChange,Math.abs(q-previousFlux[face]));previousFlux[face]=q;
                    double mean=sumFlux[face]/windowSeconds;
                    meanFluxChange=Math.max(meanFluxChange,Math.abs(mean-meanFlux[face]));meanFlux[face]=mean;sumFlux[face]=0;
                    fluxRange=Math.max(fluxRange,maxFlux[face]-minFlux[face]);
                }
                double energy=grid.energy(),storage=(energy-windowEnergy)/windowSeconds;
                boolean strict=monitor.observe(temperatureChange,legacyChange,fluxChange,Math.abs(storage),temperatureRange,legacyRange,fluxRange);
                qualified=thermalMonitor.observe(meanTemperatureChange,meanLegacyChange,Math.abs(storage),roomMaximum-roomMinimum);
                var window=new LinkedHashMap<String,Object>();window.put("physicalSecond",second);
                window.put("maxCoupledStateChangeC",temperatureChange);window.put("maxLegacyCellChangeC",legacyChange);
                window.put("maxFaceFluxChange",fluxChange);window.put("meanStorageRate",storage);
                window.put("meanNetSourceExteriorPower",(windowSource+windowExterior)/windowSeconds);
                window.put("consecutiveStrictStableWindows",monitor.stableWindows());window.put("strictSteadyQualified",strict);
                window.put("maxCoupledWindowMeanChangeC",meanTemperatureChange);window.put("maxLegacyWindowMeanChangeC",meanLegacyChange);
                window.put("maxFaceWindowMeanChange",meanFluxChange);window.put("maxLocalTemperatureRangeC",temperatureRange);
                window.put("maxFaceFluxRange",fluxRange);window.put("roomMeanRangeC",roomMaximum-roomMinimum);
                window.put("maxLegacyCellRangeC",legacyRange);
                window.put("coupledSamples",windowSamples);windowSamples=0;
                window.put("consecutiveThermallyStableWindows",thermalMonitor.stableWindows());windows.add(window);
                windowEnergy=energy;windowSource=0;windowExterior=0;
                roomMinimum=Double.POSITIVE_INFINITY;roomMaximum=Double.NEGATIVE_INFINITY;
                Arrays.fill(minTemperature,Double.POSITIVE_INFINITY);Arrays.fill(maxTemperature,Double.NEGATIVE_INFINITY);
                Arrays.fill(minFlux,Double.POSITIVE_INFINITY);Arrays.fill(maxFlux,Double.NEGATIVE_INFINITY);
                Arrays.fill(minLegacy,Double.POSITIVE_INFINITY);Arrays.fill(maxLegacy,Double.NEGATIVE_INFINITY);
                System.out.println("equilibrium opening="+opening+" dt="+dt+" second="+second+" coupled="+h.mean(grid)
                        +" mean-dT="+meanTemperatureChange+" dFlux="+fluxChange+" storage="+storage+" thermally-stable="+thermalMonitor.stableWindows());
                if(qualified) { break; }
            }
        }
        double coupledStart=h.mean(grid),legacyStart=h.legacyMean();
        double coupledTarget=qualified?trailingMean(coupledHistory,completed,requiredWindows*windowSeconds):coupledStart;
        double legacyTarget=qualified?trailingMean(legacyHistory,completed,requiredWindows*windowSeconds):legacyStart;
        var row=new LinkedHashMap<String,Object>();row.put("opening",List.of("sealed","window","missing-wall").get(opening));
        row.put("sourcePower",75);row.put("sourceLimit","unlimited, matching accepted virtual-air fixture");
        row.put("physicalStep",dt);row.put("legacyPhysicalStep",1);row.put("responseScale",1);row.put("equilibriumQualified",qualified);
        row.put("targetSamplingSeconds",1);row.put("coupledWindowSamplingSeconds",dt);
        row.put("equilibriumDefinition","thermal window-mean convergence and energy balance, permitting bounded local circulation");
        row.put("strictInstantaneousSteadyQualified",monitor.stableWindows()>=requiredWindows);
        row.put("completedHeatingSeconds",completed);row.put("firstFailure",failure);
        row.put("windowSeconds",windowSeconds);row.put("requiredConsecutiveWindows",requiredWindows);
        row.put("maxHeatSeconds",maxHeatSeconds);row.put("temperatureChangeThresholdC",TEMPERATURE_DRIFT);
        row.put("faceFluxChangeThreshold",FLUX_DRIFT);row.put("storageRateThreshold",STORAGE_RATE);
        row.put("roomWindowRangeThresholdC",ROOM_WINDOW_RANGE);
        row.put("legacyFinalRoomOffsetC",legacyStart);row.put("coupledFinalRoomOffsetC",coupledStart);
        if(qualified) { row.put("legacyEquilibriumMeanOffsetC",legacyTarget);row.put("coupledEquilibriumMeanOffsetC",coupledTarget); }
        row.put("legacyFinalMaterialOffsetC",legacyMaterialMean(h));
        row.put("coupledFinalMaterialOffsetC",h.materialMean(grid));
        if(qualified) {
            row.put("legacySecondsTo95PercentOwnEquilibrium",firstCrossing(legacyHistory,completed,.95*legacyTarget,true));
            row.put("coupledSecondsTo95PercentOwnEquilibrium",firstCrossing(coupledHistory,completed,.95*coupledTarget,true));
            row.put("coupledSecondsTo95PercentLegacyEquilibrium",firstCrossing(coupledHistory,completed,.95*legacyTarget,true));
            row.put("legacySecondsTo50PercentOwnEquilibrium",firstCrossing(legacyHistory,completed,.5*legacyTarget,true));
            row.put("coupledSecondsTo50PercentOwnEquilibrium",firstCrossing(coupledHistory,completed,.5*coupledTarget,true));
        }
        row.put("heatingSamples",heating);row.put("settlingWindows",windows);
        double heatBudgetError=grid.energy()-initialEnergy-sourceEnergy-exteriorEnergy;
        row.put("heatingTotalEnergyBudgetError",heatBudgetError);
        if(qualified && failure.isEmpty()) {
            h.legacy().clearSources();grid.setSources();
            double[] coupledCool=new double[COOL_SECONDS+1],legacyCool=coupledCool.clone();
            coupledCool[0]=coupledStart;legacyCool[0]=legacyStart;cooling.add(sample(h,grid,0));
            row.put("legacyCoolingStartRoomOffsetC",legacyStart);row.put("coupledCoolingStartRoomOffsetC",coupledStart);
            int cooled=0;
            coolingLoop:for(int second=1;second<=COOL_SECONDS;second++) {
              for(int sub=0;sub<perSecond;sub++) {
                var result=grid.step(dt,1,bg,ext);
                if(!result.ready()) { failure=result.toString();break coolingLoop; }
                accepted++;timing.add(result.nanos());
                sourceEnergy+=result.sourceEnergy();exteriorEnergy+=result.exteriorEnergy();
                maxError=Math.max(maxError,Math.abs(result.energyError()));
                maxDivergence=Math.max(maxDivergence,result.flow().pressure().maxDivergence());
              }
                h.legacy().step(1);cooled=second;
                coupledCool[second]=h.mean(grid);legacyCool[second]=h.legacyMean();
                if(second%10==0 || second==15) { cooling.add(sample(h,grid,second)); }
            }
            row.put("completedCoolingSeconds",cooled);row.put("coolingSamples",cooling);
            row.put("legacyCoolingHalfSeconds",firstCrossing(legacyCool,cooled,.5*legacyStart,false));
            row.put("coupledCoolingHalfSeconds",firstCrossing(coupledCool,cooled,.5*coupledStart,false));
            row.put("legacyCoolingTo10PercentSeconds",firstCrossing(legacyCool,cooled,.1*legacyStart,false));
            row.put("coupledCoolingTo10PercentSeconds",firstCrossing(coupledCool,cooled,.1*coupledStart,false));
            if(cooled>=40) {
                row.put("legacyFractionRemainingAt40Seconds",legacyCool[40]/legacyStart);
                row.put("coupledFractionRemainingAt40Seconds",coupledCool[40]/coupledStart);
            }
        }
        row.put("firstFailure",failure);row.put("maxStepEnergyError",maxError);row.put("maxDivergence",maxDivergence);
        row.put("acceptedSteps",accepted);row.put("advancedPhysicalSeconds",accepted*dt);
        row.put("deliveredSourceEnergy",sourceEnergy);row.put("exteriorEnergy",exteriorEnergy);
        row.put("totalEnergyBudgetError",grid.energy()-initialEnergy-sourceEnergy-exteriorEnergy);
        row.put("stepWallMs",timing.summary(1e6));
        System.out.println("calibration complete opening="+opening+" qualified="+qualified+" heating="+completed+" failure="+failure);
        return row;
    }

    private static Map<String,Object> sample(CoupledAirBenchmark.House h,CoupledAirGrid grid,int second) {
        var sample=new LinkedHashMap<String,Object>();sample.put("physicalSecond",second);
        sample.put("legacyMeanOffsetC",h.legacyMean());sample.put("coupledMeanOffsetC",h.mean(grid));
        sample.put("coupledMeanMaterialOffsetC",h.materialMean(grid));
        sample.put("legacyMeanMaterialOffsetC",legacyMaterialMean(h));
        double legacyNear=h.legacy().offset(h.mid()+1,h.y0()+1,h.mid());
        double coupledNear=grid.temperature(h.flow().nodeAt(h.mid()+1.5,h.y0()+1.5,h.mid()+.5))-20;
        sample.put("legacyNearSourceOffsetC",legacyNear);sample.put("coupledNearSourceOffsetC",coupledNear);
        return sample;
    }

    private static double legacyMaterialMean(CoupledAirBenchmark.House h) {
        double sum=0;int count=0;
        for(int z=h.x0();z<=h.x1();z++) for(int y=h.y0();y<=h.y1();y++) for(int x=h.x0();x<=h.x1();x++) {
            if(h.thermal().materialState(h.flow().index(x,y,z))>=0) { sum+=h.legacy().offset(x,y,z);count++; }
        }
        return sum/count;
    }
}
