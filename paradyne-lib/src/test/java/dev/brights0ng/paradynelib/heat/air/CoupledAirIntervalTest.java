// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Offline orchestration controls only; no runtime worker/scheduler is implemented by these tests. */
class CoupledAirIntervalTest {
    private record Fixture(CoupledAirGrid grid,ThermalGeometry geometry,double[] background,double[] exterior) { }

    private static Fixture fixture(FlowSettings settings) {
        var b=new FlowGeometry.Builder(5,6,3);
        for(int x=0;x<5;x++) for(int z=0;z<3;z++) { b.cell(x,0,z,FlowShape.FULL); }
        var f=b.build();var g=ThermalTransportTest.geometry(f,.1,.035);
        var t=CoupledAirGridTest.grid(g,FlowBoundary.builder(f).build(),settings,ThermalTransport.Settings.defaults());
        t.setSources(new ThermalTransport.Source(f.nodeAt(2.5,1.5,1.5),20,65));
        return new Fixture(t,g,ThermalTransportTest.filled(f.nodes(),20),new double[f.faces()]);
    }

    /** Test-only resumable job: at most cap accepted steps, never cross a rate transition. */
    private static final class Job {
        final Fixture f;final List<ThermalClock.Segment> segments;
        int segment,accepted;long tick;double sourceEnergy,thermalSeconds;
        Job(Fixture f,List<ThermalClock.Segment> segments) {
            this.f=f;this.segments=segments;tick=segments.isEmpty()?0:segments.getFirst().startTick();
        }
        boolean finished() { return segment==segments.size(); }
        boolean advance(int cap) {
            if(cap<=0) { throw new IllegalArgumentException("Positive step cap required"); }
            for(int work=0;work<cap && !finished();work++) {
                var s=segments.get(segment);long end=Math.min(tick+10,s.endTick());
                var result=f.grid.step((end-tick)/20.0,s.scale(),f.background,f.exterior);
                if(!result.ready()) { return false; }
                CoupledAirGridTest.ready(result);tick=end;accepted++;
                sourceEnergy+=result.sourceEnergy();thermalSeconds+=result.thermalSeconds();
                if(tick==s.endTick()) { segment++; }
            }
            return true;
        }
    }

    private static void sameState(Fixture a,Fixture b) {
        for(int i=0;i<a.geometry.states();i++) { assertEquals(a.grid.temperature(i),b.grid.temperature(i)); }
        for(int face=0;face<a.geometry.flow.faces();face++) { assertEquals(a.grid.faceFlux(face),b.grid.faceFlux(face)); }
        assertEquals(a.grid.energy(),b.grid.energy());
    }

    @Test void oneTenAnd120SecondDispatchIntervalsMatchAtEverySupportedScaleWithBoundedJobs() {
        for(double scale:new double[]{.1,.5,1,2,10}) {
            var reference=fixture(FlowSettings.DEFAULT);double referenceSource=0;
            for(int i=0;i<240;i++) {
                var r=reference.grid.step(.5,scale,reference.background,reference.exterior);
                CoupledAirGridTest.ready(r);referenceSource+=r.sourceEnergy();
            }
            for(int interval:new int[]{1,10,120}) {
                var actual=fixture(FlowSettings.DEFAULT);var clock=new ThermalClock(2400);clock.observe(0,scale);
                int accepted=0;double source=0,thermal=0;
                for(int end=interval*20;end<=2400;end+=interval*20) {
                    clock.observe(end,scale);var job=new Job(actual,clock.segments(end-interval*20,end));
                    while(!job.finished()) {
                        int before=job.accepted;assertTrue(job.advance(7));assertTrue(job.accepted-before<=7);
                    }
                    accepted+=job.accepted;source+=job.sourceEnergy;thermal+=job.thermalSeconds;
                }
                assertEquals(240,accepted);assertEquals(120*scale,thermal,1e-9);
                assertEquals(referenceSource,source,1e-9);sameState(reference,actual);
            }
        }
    }

    @Test void offStepHistoricalChangesSurviveYieldsAndAveragingThemWouldChangeTheCoupledAnswer() {
        var expectedSegments=List.of(new ThermalClock.Segment(0,73,.5),new ThermalClock.Segment(73,1200,2),
                new ThermalClock.Segment(1200,2400,1));
        var reference=fixture(FlowSettings.DEFAULT);
        for(var s:expectedSegments) for(long tick=s.startTick();tick<s.endTick();tick+=10) {
            CoupledAirGridTest.ready(reference.grid.step(Math.min(10,s.endTick()-tick)/20.0,s.scale(),reference.background,reference.exterior));
        }
        var clock=new ThermalClock(2400);clock.observe(0,.5);clock.observe(73,2);clock.observe(1200,1);clock.observe(2400,1);
        var actual=fixture(FlowSettings.DEFAULT);var job=new Job(actual,clock.segments(0,2400));
        while(!job.finished()) { assertTrue(job.advance(7)); }
        assertEquals(2400,job.tick);assertEquals(241,job.accepted);
        assertEquals(clock.interval(0,2400).thermalSeconds(),job.thermalSeconds,1e-9);sameState(reference,actual);
        var averaged=fixture(FlowSettings.DEFAULT);double average=clock.interval(0,2400).thermalSeconds()/120;
        // Keep the exact same physical partition so only the rate history differs.
        for(var s:expectedSegments) for(long tick=s.startTick();tick<s.endTick();tick+=10) {
            CoupledAirGridTest.ready(averaged.grid.step(Math.min(10,s.endTick()-tick)/20.0,average,averaged.background,averaged.exterior));
        }
        double difference=0;
        for(int i=0;i<actual.geometry.states();i++) { difference=Math.max(difference,Math.abs(actual.grid.temperature(i)-averaged.grid.temperature(i))); }
        assertTrue(difference>1e-4,"Equal total thermal time must not erase coupled rate history: "+difference);
    }

    @Test void aRejectedStepLeavesTheElapsedTimePendingAndDirectLongStepsAreRejected() {
        var settings=new FlowSettings(512,1e-8,1e-10,1e-7,1,0,1,.001,3000);
        var f=fixture(settings);double before=f.grid.energy();
        var job=new Job(f,List.of(new ThermalClock.Segment(0,2400,1)));
        assertFalse(job.advance(7));assertFalse(job.finished());assertEquals(0,job.tick);
        assertEquals(0,job.accepted);assertEquals(0,job.sourceEnergy);assertEquals(0,job.thermalSeconds);
        assertEquals(before,f.grid.energy());
        for(double seconds:new double[]{10,120}) {
            assertEquals(CoupledAirGrid.Status.INVALID_INPUT,f.grid.step(seconds,1,f.background,f.exterior).status());
            assertEquals(before,f.grid.energy());
        }
    }

    @Test void aGivenTemperatureFieldUsesTheSameBuoyancyAndWindMomentumAtEveryScale() {
        for(int wind:new int[]{0,2}) {
            var f=new FlowGeometry.Builder(5,6,3).openExterior(wind!=0).build();
            var g=ThermalTransportTest.geometry(f,0,0);double[] expected=null;
            for(double scale:new double[]{.1,.5,1,2,10}) {
                var t=new CoupledAirGrid(g,FlowBoundary.wind(f,wind,0,0),FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());
                for(int i=0;i<g.states();i++) { t.setTemperature(i,20); }
                FlowGridTest.ready(t.initialize(wind,0,0));
                // Buoyancy control: initial flux and conduction/mixing/source are zero.
                // Wind control: uniform ambient temperature stays unchanged under transport.
                if(wind==0) { t.setTemperature(f.nodeAt(2.5,1.5,1.5),25); }
                CoupledAirGridTest.ready(t.step(.5,scale,ThermalTransportTest.filled(f.nodes(),20),ThermalTransportTest.filled(f.faces(),20)));
                double[] flux=new double[f.faces()];double maximum=0;
                for(int e=0;e<flux.length;e++) { flux[e]=t.faceFlux(e);maximum=Math.max(maximum,Math.abs(flux[e])); }
                assertTrue(maximum>.001);
                if(expected==null) { expected=flux; }
                else if(wind==0) { assertArrayEquals(expected,flux); }
                // Uniform wind transport may leave round-off within the iterative thermal
                // residual. This is far below the unchanged 1e-7 divergence criterion.
                else { assertArrayEquals(expected,flux,1e-9); }
            }
        }
    }
}
