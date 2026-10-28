// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;

/** Private equilibrium preparation, never elapsed gameplay time. Reuses the transient stencil and thermostat.
 * Eight half-second flow samples provide conservative mean shared flux for stationary thermal relaxation.
 * Verification distinguishes stationary fields from bounded thermal cycles. No world access.
 */
public final class AirThermalInitializer {
    public static final int MAX_ITERATIONS=128,FLOW_SAMPLES=8,SWEEPS_PER_ITERATION=256;
    public record Quality(int iterations,long sweeps,double relaxationResidual,double temperatureChange,double velocityChange,double verificationDrift,String qualification,double windowRange,int validationSteps) { }
    public static final class Solution {
        private final ThermalGeometry geometry;
        private final double[] temperature,velocity;
        public final Quality quality;
        private Solution(ThermalGeometry geometry,double[] temperature,double[] velocity,Quality quality) {
            this.geometry=geometry;this.temperature=temperature.clone();this.velocity=velocity.clone();this.quality=quality;
        }
        public double temperature(int state) { return temperature[state]; }
        public FlowGrid.Step initialize(CoupledAirGrid grid,double[] temperatures) { return grid.initializeMapped(geometry,temperatures,velocity); }
    }
    private final ThermalGeometry geometry;
    private final FlowBoundary boundary;
    private final ThermalTransport thermal;
    private final ThermalTransport.Source[] sources;
    private final FlowGrid flow;
    private final double[] air,background,exterior,previous,previousVelocity,meanFlux;
    private int iterations,iterationSweeps,stable,flowSamples;
    private long sweeps;
    private boolean relaxing;
    private double temperatureChange=Double.POSITIVE_INFINITY,velocityChange=Double.POSITIVE_INFINITY,residual=Double.POSITIVE_INFINITY;
    private Solution solution;
    private CoupledAirGrid verification;
    private double[] verificationStart,windowSum,windowMin,windowMax,previousWindow;
    private int verificationSteps,windowSamples,stableWindows;
    private boolean statistical;
    private double verificationDrift,windowRange;
    public AirThermalInitializer(ThermalGeometry geometry,FlowBoundary boundary,double[] initial,double ambient,ThermalTransport.Source[] sources,double windX,double windZ) {
        if(initial.length!=geometry.states() || !Double.isFinite(ambient) || ambient<=-273.15 || geometry.unrepresentedFluidCells()!=0) throw new IllegalArgumentException("Invalid initializer state");
        this.geometry=geometry;this.boundary=boundary;this.sources=sources.clone();thermal=new ThermalTransport(geometry,ThermalTransport.Settings.defaults());
        flow=new FlowGrid(geometry.flow,boundary,FlowSettings.DEFAULT);
        air=new double[geometry.airStates()];background=new double[air.length];exterior=new double[geometry.flow.faces()];previous=initial.clone();previousVelocity=new double[exterior.length];meanFlux=new double[exterior.length];
        Arrays.fill(background,ambient);Arrays.fill(exterior,ambient);thermal.setMixingReference(background);thermal.setSources(sources);
        for(int i=0;i<initial.length;i++) {check(initial[i]);thermal.setTemperature(i,initial[i]);}
        flow.seedUniform(windX,0,windZ);if(!flow.projectInitial().ready()) throw new IllegalStateException("Natural initializer projection failed");
    }
    private static void check(double t) {if(!Double.isFinite(t) || t<=-273.15 || t>FlowSettings.DEFAULT.maxTemperatureC()) throw new IllegalArgumentException("Unsupported initialization temperature");}
    /** Strict work cap (flow updates or sweeps) and soft deadline checked every eight sweeps; a coupled step remains atomic. */
    public int advance(int allowance,long deadline) {
        if(allowance<0) throw new IllegalArgumentException("Negative initialization allowance");int used=0;
        while(solution==null && used<allowance && System.nanoTime()<deadline) {
            if(verification!=null) {verifyStep();used++;continue;}
            if(!relaxing) {
                if(flowSamples==0) {thermal.copyTemperatures(previous);thermal.copyAirTemperatures(air);Arrays.fill(meanFlux,0);}
                var step=flow.step(.5,air,background);used++;
                if(!step.ready()) throw new IllegalStateException("Natural initializer flow rejected: "+step.status());
                for(int f=0;f<meanFlux.length;f++) meanFlux[f]+=flow.faceFlux(f)/FLOW_SAMPLES;
                if(++flowSamples<FLOW_SAMPLES) continue;
                relaxing=true;iterationSweeps=0;
                if(System.nanoTime()>=deadline || used>=allowance) break;
            }
            int count=Math.min(8,Math.min(allowance-used,SWEEPS_PER_ITERATION-iterationSweeps));
            residual=thermal.relaxStationary(meanFlux,exterior,count);used+=count;sweeps+=count;iterationSweeps+=count;
            if(iterationSweeps<SWEEPS_PER_ITERATION && residual>1e-9) continue;
            temperatureChange=0;velocityChange=0;
            for(int i=0;i<previous.length;i++) {check(thermal.temperature(i));temperatureChange=Math.max(temperatureChange,Math.abs(previous[i]-thermal.temperature(i)));}
            for(int f=0;f<previousVelocity.length;f++) {double mean=meanFlux[f]/geometry.flow.faceArea(f);velocityChange=Math.max(velocityChange,Math.abs(previousVelocity[f]-mean));previousVelocity[f]=mean;}
            // Damped fixed-point iteration changes only the private estimate, not physical coefficients or flow time.
            for(int i=0;i<previous.length;i++) thermal.setTemperature(i,previous[i]+.25*(thermal.temperature(i)-previous[i]));
            iterations++;relaxing=false;flowSamples=0;
            stable=temperatureChange<=1e-5 && velocityChange<=1e-6 && residual<=1e-8?stable+1:0;
            if(stable>=6) beginVerification(false);
            else if(iterations>=MAX_ITERATIONS) beginVerification(true);
        }
        return used;
    }
    /** Qualification uses unchanged coupled steps, including all pressure and energy checks. */
    private void beginVerification(boolean statistical) {
        verificationStart=new double[geometry.states()];double[] velocity=new double[geometry.flow.faces()];
        for(int i=0;i<verificationStart.length;i++) verificationStart[i]=thermal.temperature(i);
        for(int f=0;f<velocity.length;f++) velocity[f]=meanFlux[f]/geometry.flow.faceArea(f);
        verification=new CoupledAirGrid(geometry,boundary,FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());verification.setSources(sources);
        if(!verification.initializeMapped(geometry,verificationStart,velocity).ready()) throw new IllegalStateException("Natural estimate reprojection rejected");
        this.statistical=statistical;verificationSteps=0;verificationDrift=0;
        windowSum=new double[geometry.states()];windowMin=windowSum.clone();windowMax=windowSum.clone();resetWindow();
    }
    private void resetWindow() {Arrays.fill(windowSum,0);Arrays.fill(windowMin,Double.POSITIVE_INFINITY);Arrays.fill(windowMax,Double.NEGATIVE_INFINITY);windowSamples=0;}
    private void verifyStep() {
        var step=verification.step(.5,1,background,exterior);verificationSteps++;
        if(!step.ready()) throw new IllegalStateException("Natural estimate verification rejected: "+step.status());
        for(int state=0;state<verificationStart.length;state++) {
            double t=verification.temperature(state);verificationDrift=Math.max(verificationDrift,Math.abs(verificationStart[state]-t));
            windowSum[state]+=t;windowMin[state]=Math.min(windowMin[state],t);windowMax[state]=Math.max(windowMax[state],t);
        }
        windowSamples++;
        if(!statistical && verificationSteps==4) {
            if(verificationDrift<=1e-3) {finish("STATIONARY");return;}
            // A statistically settled thermal field may coexist with a bounded flow cycle.
            statistical=true;previousWindow=null;stableWindows=0;resetWindow();
        }
        if(statistical && windowSamples==128) {
            double change=0;windowRange=0;
            for(int state=0;state<windowSum.length;state++) {
                windowSum[state]/=128;windowRange=Math.max(windowRange,windowMax[state]-windowMin[state]);
                if(previousWindow!=null) change=Math.max(change,Math.abs(previousWindow[state]-windowSum[state]));
            }
            stableWindows=previousWindow!=null && change<=.001 && windowRange<=.01?stableWindows+1:0;
            previousWindow=windowSum.clone();verificationDrift=change;
            if(stableWindows>=3) {finish("THERMAL_WINDOW");return;}
            resetWindow();
        }
        if(verificationSteps>=516) throw new IllegalStateException("Natural thermal estimate did not qualify within bounded verification; window drift="+verificationDrift+", range="+windowRange);
    }
    private void finish(String qualification) {
        double[] t=new double[geometry.states()],v=new double[geometry.flow.faces()];
        for(int i=0;i<t.length;i++) {t[i]=verification.temperature(i);if(windowSamples>0) windowRange=Math.max(windowRange,windowMax[i]-windowMin[i]);}
        for(int f=0;f<v.length;f++) v[f]=verification.faceFlux(f)/geometry.flow.faceArea(f);
        solution=new Solution(geometry,t,v,new Quality(iterations,sweeps,residual,temperatureChange,velocityChange,verificationDrift,qualification,windowRange,verificationSteps));
        verification=null;
    }
    public boolean ready() { return solution!=null; }
    public Solution solution() { if(solution==null) throw new IllegalStateException("Natural estimate is not qualified");return solution; }
    public Quality quality() {return new Quality(iterations,sweeps,residual,temperatureChange,velocityChange,Double.NaN,"PENDING",windowRange,verificationSteps);}
}
