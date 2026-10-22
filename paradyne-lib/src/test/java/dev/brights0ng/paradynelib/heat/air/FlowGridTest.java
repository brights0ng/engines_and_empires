// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowGridTest {
    @Test void cachedSamplingMatchesReferenceThroughPartialPocketsAndBothWindDirections() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var slab=new FlowShape.Builder().solidBox(0,0,0,16,8,16).build();
        var g=new FlowGeometry.Builder(6,4,3).openExterior(true).cell(2,1,1,pane).cell(3,1,1,slab).build();
        for(double sign:new double[]{1,-1}) {
            var boundary=FlowBoundary.wind(g,sign*2,0,sign*.5);
            var a=new FlowGrid(g,boundary,accurate(),PressureProjector.Preconditioner.MODIFIED_INCOMPLETE_CHOLESKY,false);
            var b=new FlowGrid(g,boundary,accurate(),PressureProjector.Preconditioner.MODIFIED_INCOMPLETE_CHOLESKY,true);
            a.seedUniform(sign*2,0,sign*.5);b.seedUniform(sign*2,0,sign*.5);
            ready(a.projectInitial());ready(b.projectInitial());
            double[] bg=filled(g.nodes(),20),t=bg.clone();
            for(int i=0;i<t.length;i++) { t[i]+=10*Math.sin(i); }
            for(int step=0;step<6;step++) {
                var old=a.step(.25,t,bg);var cached=b.step(.25,t,bg);ready(old);ready(cached);
                assertEquals(old.blockedTraces(),cached.blockedTraces());
                assertEquals(old.pressure(),cached.pressure());
                for(int f=0;f<g.faces();f++) { assertEquals(a.faceVelocity(f),b.faceVelocity(f)); }
            }
        }
    }

    static FlowSettings accurate() { return new FlowSettings(512,1e-10,1e-12,1e-7,1,0,1,100,3000); }
    static double[] filled(int n,double value) { double[] a=new double[n];Arrays.fill(a,value);return a; }
    static FlowGrid grid(FlowGeometry g) { return new FlowGrid(g,FlowBoundary.builder(g).build(),accurate()); }
    static void ready(FlowGrid.Step step) { assertTrue(step.ready(),step.toString());assertTrue(step.pressure().maxDivergence()<1e-7); }

    @Test void fullEmptyAndFluidShapesHaveExactOccupancy() {
        assertSame(FlowShape.EMPTY,new FlowShape.Builder().build());
        assertSame(FlowShape.FULL,new FlowShape.Builder().solidBox(0,0,0,16,16,16).build());
        var slab=new FlowShape.Builder().solidBox(0,0,0,16,8,16).fluidBox(0,0,0,16,12,16).build();
        assertEquals(.5,slab.solidVolume());assertEquals(.25,slab.fluidVolume());assertEquals(.25,slab.airVolume(0));
        var g=new FlowGeometry.Builder(1,1,1).cell(0,0,0,slab).build();
        assertEquals(1,g.fluidInterfaceArea(0));assertEquals(0,g.solidInterfaceArea(0));
        assertEquals(0,new FlowGeometry.Builder(1,1,1).cell(0,0,0,FlowShape.WATER).build().nodes());
    }

    @Test void overlapUsesMatchingPatchesInsteadOfMinimumOpening() {
        var bottom=new FlowShape.Builder().solidBox(0,0,0,16,8,16).build();
        var top=new FlowShape.Builder().solidBox(0,8,0,16,16,16).build();
        var closed=new FlowGeometry.Builder(2,1,1).cell(0,0,0,bottom).cell(1,0,0,top).build();
        assertEquals(0,closed.faces());assertEquals(2,closed.components());
        var open=new FlowGeometry.Builder(2,1,1).cell(0,0,0,bottom).cell(1,0,0,bottom).build();
        assertEquals(1,open.faces());assertEquals(.5,open.faceArea(0));assertEquals(1,open.components());
    }

    @Test void thinSnowStairsAndFenceRetainAir() {
        var snow=new FlowShape.Builder().solidBox(0,0,0,16,2,16).build();
        var stair=new FlowShape.Builder().solidBox(0,0,0,16,8,16).solidBox(8,8,0,16,16,16).build();
        var fence=new FlowShape.Builder().solidBox(6,0,6,10,16,10).solidBox(0,6,7,16,10,9).build();
        assertEquals(.875,snow.airVolume(0));assertEquals(.25,stair.airVolume(0));
        var g=new FlowGeometry.Builder(3,2,1).cell(0,0,0,snow).cell(1,0,0,stair).cell(2,0,0,fence).build();
        assertEquals(1,g.components());assertTrue(g.faces()>0);
    }

    @Test void separatingPaneAndClosedDoorCannotConnectAcrossTheirOwnBlock() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var closed=new FlowGeometry.Builder(3,1,1).cell(1,0,0,pane).build();
        assertEquals(2,pane.components());assertEquals(2,closed.components());
        var openDoor=new FlowShape.Builder().solidBox(0,0,0,16,16,2).build();
        assertEquals(1,new FlowGeometry.Builder(3,1,1).cell(1,0,0,openDoor).build().components());
        var trapdoor=new FlowShape.Builder().solidBox(0,7,0,16,9,16).build();
        assertEquals(2,new FlowGeometry.Builder(1,3,1).cell(0,1,0,trapdoor).build().components());
        var flow=grid(closed);flow.seedUniform(5,0,0);ready(flow.projectInitial());
        for(int f=0;f<closed.faces();f++) { assertEquals(0,flow.faceFlux(f),1e-9); }
    }

    @Test void pressureMatrixIsSymmetricForPartialAndDisconnectedComponents() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var g=new FlowGeometry.Builder(3,2,1).cell(1,0,0,pane).cell(1,1,0,pane).build();
        var projector=new PressureProjector(g,FlowBoundary.builder(g).build());
        double[] a=new double[g.nodes()],b=new double[a.length],aa=new double[a.length],ab=new double[a.length];
        for(int i=0;i<a.length;i++) { a[i]=Math.sin(i+1);b[i]=Math.cos(i*2+1); }
        projector.apply(a,aa);projector.apply(b,ab);double left=0,right=0,energy=0;
        for(int i=0;i<a.length;i++) { left+=a[i]*ab[i];right+=b[i]*aa[i];energy+=a[i]*aa[i]; }
        assertEquals(left,right,1e-12);assertTrue(energy>0);
    }

    @Test void uniformOpenWindSurvivesTransportAndProjectionAtAllStepSizes() {
        var g=new FlowGeometry.Builder(6,5,4).openExterior(true).build();
        for(double dt:new double[]{.25,.5,1}) {
            var flow=new FlowGrid(g,FlowBoundary.wind(g,3,0,1),accurate());flow.seedUniform(3,0,1);ready(flow.projectInitial());
            for(int i=0;i<3;i++) { ready(flow.step(dt,filled(g.nodes(),20),filled(g.nodes(),20))); }
            for(int f=0;f<g.faces();f++) { assertEquals(g.faceAxis(f)==0?3:g.faceAxis(f)==2?1:0,flow.faceVelocity(f),1e-8); }
        }
    }

    @Test void obstacleRoutesWindWithoutAnInfiniteBlockedColumn() {
        var g=new FlowGeometry.Builder(12,5,5).openExterior(true).cell(3,2,2,FlowShape.FULL).build();
        var flow=new FlowGrid(g,FlowBoundary.wind(g,3,0,0),accurate());flow.seedUniform(3,0,0);ready(flow.projectInitial());
        assertTrue(flow.componentVelocity(g.nodeAt(10.5,2.5,2.5),0)>2.5);
        assertTrue(flow.componentVelocity(g.nodeAt(2.5,3.5,2.5),1)>.01);
        ready(flow.step(.5,filled(g.nodes(),20),filled(g.nodes(),20)));
    }

    @Test void sealedHotAndColdColumnsHaveOppositeCurrentsAndReturnFlow() {
        var g=new FlowGeometry.Builder(5,5,3).build();
        for(double delta:new double[]{40,-40}) {
            var flow=grid(g);ready(flow.projectInitial());double[] t=filled(g.nodes(),20),bg=t.clone();
            for(int y=0;y<4;y++) { t[g.nodeAt(2.5,y+.5,1.5)]+=delta; }
            ready(flow.step(.5,t,bg));
            assertTrue(flow.componentVelocity(g.nodeAt(2.5,2.5,1.5),1)*delta>0);
            assertTrue(flow.componentVelocity(g.nodeAt(.5,2.5,1.5),1)*delta<0);
            assertEquals(0,flow.step(.5,t,bg).pressure().boundaryImbalance(),1e-12);
        }
    }

    @Test void altitudeDependentAmbientHasNoSpuriousBuoyancy() {
        var g=new FlowGeometry.Builder(3,5,3).build();var flow=grid(g);ready(flow.projectInitial());
        double[] bg=new double[g.nodes()];for(int i=0;i<bg.length;i++) { bg[i]=20-g.cy[i]*.2; }
        ready(flow.step(1,bg.clone(),bg));for(int f=0;f<g.faces();f++) { assertEquals(0,flow.faceVelocity(f)); }
    }

    @Test void traceStopsAtThinWallsEvenWhenEndpointIsAirBeyondThem() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var g=new FlowGeometry.Builder(3,1,1).cell(1,0,0,pane).build();var trace=new FlowGeometry.Trace();
        g.trace(.5,.5,.5,2,0,0,trace);assertTrue(trace.blocked);assertTrue(trace.x<1+7.0/16);
        assertEquals(g.nodeAt(1.1,.5,.5),trace.node);
        g.trace(2.5,.5,.5,-2,0,0,trace);assertTrue(trace.blocked);assertTrue(trace.x>1+9.0/16);
        assertThrows(IllegalArgumentException.class,()->g.trace(1.5,.5,.5,1,0,0,trace));
    }

    @Test void tinyAirVolumeRemainsExplicitAndConservative() {
        var tiny=new FlowShape.Builder().solidBox(0,1,0,16,16,16).solidBox(0,0,1,16,1,16).solidBox(0,0,0,15,1,1).build();
        var g=new FlowGeometry.Builder(2,1,1).cell(0,0,0,tiny).build();assertEquals(1.0/4096,g.airVolume(0));
        var flow=grid(g);flow.seedUniform(1,0,0);ready(flow.projectInitial());
        assertEquals(0,flow.faceFlux(0),1e-12);
    }

    @Test void invalidInputsAndIterationExhaustionCannotReplaceValidField() {
        var g=new FlowGeometry.Builder(8,6,4).build();
        var limited=new FlowSettings(1,1e-12,1e-14,1e-10,1,0,1,100,3000);
        var flow=new FlowGrid(g,FlowBoundary.builder(g).build(),limited);ready(flow.projectInitial());
        double[] bg=filled(g.nodes(),20),t=bg.clone();t[23]=100;
        var failed=flow.step(.5,t,bg);assertEquals(FlowGrid.Status.PRESSURE_FAILED,failed.status());
        assertEquals(PressureProjector.Status.ITERATION_LIMIT,failed.pressure().status());
        for(int f=0;f<g.faces();f++) { assertEquals(0,flow.faceVelocity(f)); }
        assertEquals(FlowGrid.Status.INVALID_INPUT,flow.step(2,t,bg).status());
        t[0]=Double.NaN;assertEquals(FlowGrid.Status.INVALID_INPUT,flow.step(.5,t,bg).status());
        t[0]=-273.15;assertEquals(FlowGrid.Status.INVALID_INPUT,flow.step(.5,t,bg).status());
        assertThrows(IllegalStateException.class,()->flow.seedFace(0,1));
    }

    @Test void incompatibleSealedPrescribedFluxIsRejected() {
        var g=new FlowGeometry.Builder(2,2,2).openExterior(true).build();var builder=FlowBoundary.builder(g);
        for(int f=0;f<g.faces();f++) { if(g.exterior(f)) { builder.prescribe(f,g.low[f]<0 && g.axis[f]==0?1:0); } }
        var flow=new FlowGrid(g,builder.build(),accurate());var result=flow.projectInitial();
        assertFalse(flow.valid());assertEquals(PressureProjector.Status.INCOMPATIBLE_BOUNDARY,result.pressure().status());
        assertThrows(IllegalStateException.class,()->flow.faceVelocity(0));
    }

    @Test void geometryRevisionAndBoundaryOwnershipAreExplicit() {
        var a=new FlowGeometry.Builder(1,1,1).revision(1).build();var b=new FlowGeometry.Builder(1,1,1).revision(2).build();
        assertThrows(IllegalArgumentException.class,()->new FlowGrid(b,FlowBoundary.builder(a).build(),accurate()));
        var flow=grid(b);ready(flow.projectInitial());assertEquals(2,flow.revision());
    }

    @Test void sealedHouseExcludesExteriorWindWhileDoorwayVentilates() {
        for(boolean door:new boolean[]{false,true}) {
            var builder=new FlowGeometry.Builder(9,7,7).openExterior(true);
            for(int z=1;z<=5;z++) for(int y=1;y<=5;y++) for(int x=2;x<=6;x++) {
                boolean wall=x==2 || x==6 || y==1 || y==5 || z==1 || z==5;
                boolean opening=door && (x==2 || x==6) && z==3 && y==3;
                if(wall && !opening) { builder.cell(x,y,z,FlowShape.FULL); }
            }
            var g=builder.build();var flow=new FlowGrid(g,FlowBoundary.wind(g,6,0,0),accurate());
            flow.seedUniform(6,0,0);ready(flow.projectInitial());int room=g.nodeAt(4.5,3.5,3.5);
            if(door) { assertTrue(flow.componentVelocity(room,0)>.1); }
            else { assertEquals(0,flow.componentVelocity(room,0),1e-7); }
        }
    }

    @Test void stormWindAndCornerTraceRemainBoundedAndCannotCrossPane() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var g=new FlowGeometry.Builder(5,5,3).openExterior(true).cell(2,2,1,pane).build();
        var flow=new FlowGrid(g,FlowBoundary.wind(g,20,0,6),accurate());flow.seedUniform(20,0,6);ready(flow.projectInitial());
        ready(flow.step(.25,filled(g.nodes(),20),filled(g.nodes(),20)));
        var trace=new FlowGeometry.Trace();g.trace(1.5,1.5,1.5,2,2,0,trace);
        assertTrue(trace.blocked);assertTrue(trace.x<2+7.0/16);
        for(int f=0;f<g.faces();f++) { assertTrue(Double.isFinite(flow.faceVelocity(f))); }
    }

    @Test void failedPressureAndSpeedChecksPreserveANonzeroPublishedField() {
        var g=new FlowGeometry.Builder(8,6,4).openExterior(true).build();
        for(int cap:new int[]{1,512}) {
            var settings=new FlowSettings(cap,1e-12,1e-14,1e-10,1,0,1,10,3000);
            var flow=new FlowGrid(g,FlowBoundary.wind(g,1,0,0),settings);flow.seedUniform(1,0,0);ready(flow.projectInitial());
            double[] t=filled(g.nodes(),20),bg=t.clone();t[23]=cap==1?100:3000;
            var step=flow.step(1,t,bg);
            assertEquals(cap==1?FlowGrid.Status.PRESSURE_FAILED:FlowGrid.Status.SPEED_LIMIT,step.status());
            for(int f=0;f<g.faces();f++) { assertEquals(g.axis[f]==0?1:0,flow.faceVelocity(f)); }
        }
    }

    @Test void thermalResponseRateDoesNotChangeMomentumTime() {
        var g=new FlowGeometry.Builder(3,4,3).build();double[] bg=filled(g.nodes(),20),t=bg.clone();t[5]=60;
        var slow=grid(g);var fast=grid(g);ready(slow.projectInitial());ready(fast.projectInitial());
        var a=new ThermalClock(2400);var b=new ThermalClock(2400);a.observe(0,.1);b.observe(0,10);a.observe(20,.1);b.observe(20,10);
        assertNotEquals(a.interval(0,20).thermalSeconds(),b.interval(0,20).thermalSeconds());
        ready(slow.step(a.interval(0,20).gameSeconds(),t,bg));ready(fast.step(b.interval(0,20).gameSeconds(),t,bg));
        for(int f=0;f<g.faces();f++) { assertEquals(slow.faceVelocity(f),fast.faceVelocity(f)); }
    }

    @Test void unacceptedProjectionCannotChangeThePressureWarmStart() {
        var g=new FlowGeometry.Builder(4,3,2).build();var p=new PressureProjector(g,FlowBoundary.builder(g).build());
        double[] seed=new double[g.faces()],corrected=seed.clone();
        for(int f=0;f<seed.length;f++) { seed[f]=g.axis[f]==0?1:0; }
        assertTrue(p.project(seed,1,corrected,accurate()).ready());double[] retained=new double[g.nodes()];
        for(int i=0;i<retained.length;i++) { retained[i]=p.pressureCorrection(i); }
        // This is a numerically successful candidate that the owner can still reject on speed limits.
        Arrays.fill(seed,0);assertTrue(p.projectCandidate(seed,1,corrected,accurate()).ready());
        for(int i=0;i<retained.length;i++) { assertEquals(retained[i],p.pressureCorrection(i)); }
        seed[0]=Double.NaN;assertEquals(PressureProjector.Status.NON_FINITE,p.project(seed,1,corrected,accurate()).status());
        for(int i=0;i<retained.length;i++) { assertEquals(retained[i],p.pressureCorrection(i)); }
    }
}
