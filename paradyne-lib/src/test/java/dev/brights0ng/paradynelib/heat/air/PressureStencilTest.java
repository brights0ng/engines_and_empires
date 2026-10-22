// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PressureStencilTest {
    @Test void rowOperatorMatchesSharedFaceFluxWithSymmetricPressureReferences() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        for(boolean exterior:new boolean[]{false,true}) {
            var g=new FlowGeometry.Builder(4,2,2).openExterior(exterior).cell(1,0,0,pane).cell(1,1,0,pane).build();
            boolean[] pinned=new boolean[g.nodes()],chosen=new boolean[g.components()];
            if(!exterior) for(int i=0;i<g.nodes();i++) {
                if(!chosen[g.component[i]]) { pinned[i]=true;chosen[g.component[i]]=true; }
            }
            double[] weights=new double[g.faces()],diag=new double[g.nodes()],x=new double[g.nodes()],expected=x.clone(),actual=x.clone();
            for(int i=0;i<x.length;i++) { x[i]=Math.sin(i+.5); }
            for(int f=0;f<g.faces();f++) {
                double w=g.area[f]/g.distance[f];weights[f]=w;int l=g.low[f],h=g.high[f];
                if(l>=0) { diag[l]+=w; }if(h>=0) { diag[h]+=w; }
                double flux=w*((l<0 || pinned[l]?0:x[l])-(h<0 || pinned[h]?0:x[h]));
                if(l>=0 && !pinned[l]) { expected[l]+=flux; }if(h>=0 && !pinned[h]) { expected[h]-=flux; }
            }
            for(int i=0;i<x.length;i++) if(pinned[i]) { diag[i]=1;expected[i]=x[i]; }
            var stencil=new PressureStencil(g,weights,diag,pinned,PressureProjector.Preconditioner.INCOMPLETE_CHOLESKY);
            stencil.apply(x,actual);assertArrayEquals(expected,actual,1e-13);
            double dot=0;for(int i=0;i<x.length;i++) { dot+=x[i]*expected[i]; }
            assertEquals(dot,stencil.applyAndDot(x,actual),1e-12);assertArrayEquals(expected,actual,1e-13);
        }
    }

    @Test void incompleteFactorIsExactForASealedOneDimensionalPassage() {
        var g=new FlowGeometry.Builder(24,1,1).build();var boundary=FlowBoundary.builder(g).build();
        double[] initial=new double[g.faces()],corrected=initial.clone();
        for(int f=0;f<initial.length;f++) { initial[f]=Math.sin(f+1); }
        var p=new PressureProjector(g,boundary,PressureProjector.Preconditioner.INCOMPLETE_CHOLESKY);assertTrue(p.usesIncompleteCholesky());
        var q=p.project(initial,1,corrected,FlowSettings.DEFAULT);
        assertTrue(q.ready(),q.toString());assertEquals(1,q.iterations());
        for(double velocity:corrected) { assertEquals(0,velocity,1e-12); }
    }

    @Test void preconditionersAreSymmetricPositiveAndOverwriteReusedBuffers() {
        var g=new FlowGeometry.Builder(4,3,2).openExterior(true).build();
        double[] weights=new double[g.faces()],diag=new double[g.nodes()];
        for(int f=0;f<g.faces();f++) {
            double w=g.area[f]/g.distance[f];weights[f]=w;
            if(g.low[f]>=0) { diag[g.low[f]]+=w; }if(g.high[f]>=0) { diag[g.high[f]]+=w; }
        }
        for(var method:PressureProjector.Preconditioner.values()) {
            var s=new PressureStencil(g,weights,diag,new boolean[g.nodes()],method);
            assertEquals(method!=PressureProjector.Preconditioner.DIAGONAL,s.incomplete());
            double[] a=diag.clone(),b=a.clone(),pa=a.clone(),pb=a.clone();
            for(int i=0;i<a.length;i++) { a[i]=Math.sin(i+1);b[i]=Math.cos(2*i+1); }
            s.precondition(a,pa);s.precondition(b,pb);double left=0,right=0,energy=0;
            for(int i=0;i<a.length;i++) { left+=a[i]*pb[i];right+=b[i]*pa[i];energy+=a[i]*pa[i]; }
            assertEquals(left,right,1e-12);assertTrue(energy>0);
            double[] retained=pa.clone();Arrays.fill(pa,Double.NaN);s.precondition(a,pa);assertArrayEquals(retained,pa);
        }
    }

    @Test void unsafeFactorFallsBackToPositiveDiagonalInsteadOfPublishingNonfiniteWork() {
        var g=new FlowGeometry.Builder(2,1,1).build();
        var s=new PressureStencil(g,new double[]{2},new double[]{1,1},new boolean[2],PressureProjector.Preconditioner.INCOMPLETE_CHOLESKY);
        assertFalse(s.incomplete());double[] out=new double[2];s.precondition(new double[]{3,-4},out);
        assertArrayEquals(new double[]{3,-4},out);
    }

    @Test void diagonalAndIncompleteSolvesAgreeAtTheSameConservationTolerance() {
        var g=new FlowGeometry.Builder(9,6,5).openExterior(true).cell(3,2,2,FlowShape.FULL).build();
        var boundary=FlowBoundary.wind(g,6,0,2);double[] initial=new double[g.faces()],a=initial.clone(),b=initial.clone();
        for(int f=0;f<initial.length;f++) { initial[f]=g.axis[f]==0?6:g.axis[f]==2?2:0; }
        var diagonal=new PressureProjector(g,boundary,PressureProjector.Preconditioner.DIAGONAL);
        var qa=diagonal.project(initial,.5,a,FlowSettings.DEFAULT);assertTrue(qa.ready(),qa.toString());
        for(var method:new PressureProjector.Preconditioner[]{PressureProjector.Preconditioner.INCOMPLETE_CHOLESKY,
                PressureProjector.Preconditioner.MODIFIED_INCOMPLETE_CHOLESKY}) {
            var incomplete=new PressureProjector(g,boundary,method);var qb=incomplete.project(initial,.5,b,FlowSettings.DEFAULT);
            assertTrue(qb.ready(),qb.toString());assertTrue(qb.iterations()<qa.iterations());assertArrayEquals(a,b,1e-7);
        }
    }
}
