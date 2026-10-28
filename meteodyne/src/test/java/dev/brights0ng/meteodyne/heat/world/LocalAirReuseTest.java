// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirReuseTest {
    private static final Bounds OLD=new Bounds(0,0,0,3,3,3),NEXT=new Bounds(1,0,0,3,3,3);
    private static final Cell AIR=LocalAirPreparationTest.AIR,SOLID=LocalAirPreparationTest.SOLID;
    private static final class Data {
        final Map<Long,Cell> cells=new HashMap<>();
        final List<World> worlds=new ArrayList<>();
        boolean proofContext;
        long material=1;
        void edit(int x,int y,int z,Cell cell) {
            cells.put(blockKey(x,y,z),cell);
            for(var w:worlds) w.deps.blockChanged(x,y,z);
        }
    }
    private static final class World implements LocalAirCoverage.World {
        final Data data;final LocalAirDependencies deps;final long context;
        long[] reads;int samples;
        World(Data data,Bounds bounds) {this.data=data;deps=new LocalAirDependencies(bounds);context=data.worlds.size()+1;data.worlds.add(this);}
        public long contextId() {return context;}
        public long geometryRevision() {return deps.get();}
        public long materialRevision() {return data.material;}
        public int minBuildY() {return 0;}
        public int maxBuildY() {return 32;}
        public boolean sameWorld(Source other) {return other instanceof World w && data==w.data;}
        public void retainRead(int x,int y,int z) {deps.read(x,y,z);}
        public void retainReads(ReadBounds box) {deps.readBounds(box);}
        public Footprint lastReads() {return new Footprint(reads);}
        public Cell sample(int x,int y,int z) {
            samples++;deps.read(x,y,z);reads=new long[]{blockKey(x,y,z)};
            if(data.proofContext && x==2 && z==1 && y>=3) {
                deps.read(20,10,20);reads=new long[]{blockKey(x,y,z),blockKey(20,10,20)};
                if(data.cells.containsKey(blockKey(20,10,20))) return SOLID;
            }
            return data.cells.getOrDefault(blockKey(x,y,z),y==0?SOLID:AIR);
        }
    }
    private static LocalAirGeometryCapture capture(World world,Bounds bounds,List<LocalAirReuse> leases) {
        var c=new LocalAirGeometryCapture(bounds,bounds.cells(),world,null,Set.of(),leases);
        c.advance(bounds.cells(),Long.MAX_VALUE);assertEquals(Status.CAPTURED,c.status(),c.reason());return c;
    }
    private static LocalAirCoverage coverage(World w,Snapshot snapshot,List<LocalAirReuse> leases) {
        var c=new LocalAirCoverage(LocalAirCoverage.plan(snapshot),w,10000,null,Set.of(),leases);
        c.advance(10000,Long.MAX_VALUE);assertEquals(LocalAirCoverage.Status.ADMITTED,c.status(),c.reason());return c;
    }
    @Test void shiftedCoverageReusesOnlyOverlapAndProvenColumnsAndMatchesColdGeometry() {
        var data=new Data();var donor=new World(data,OLD);var old=capture(donor,OLD,List.of()).snapshot();
        var oldCoverage=coverage(donor,old,List.of());
        var lease=new LocalAirReuse(old,oldCoverage.certificate(),Set.of(),donor,()->true);
        var target=new World(data,NEXT);var capture=capture(target,NEXT,List.of(lease));var snapshot=capture.snapshot();
        assertEquals(18,capture.reusedCells());assertEquals(9,capture.readCells());
        var reused=coverage(target,snapshot,List.of(lease));
        var coldWorld=new World(data,NEXT);var coldSnapshot=capture(coldWorld,NEXT,List.of()).snapshot();var cold=coverage(coldWorld,coldSnapshot,List.of());
        assertTrue(reused.reusedCells()>0);assertTrue(reused.sampledCells()<cold.sampledCells());
        var a=reused.certificate().bake(snapshot,dev.brights0ng.paradynelib.heat.air.ThermalGeometry.Coefficients.legacyMaterials());
        var b=cold.certificate().bake(coldSnapshot,dev.brights0ng.paradynelib.heat.air.ThermalGeometry.Coefficients.legacyMaterials());
        assertEquals(b.flow().nodes(),a.flow().nodes());assertEquals(b.flow().faces(),a.flow().faces());
        for(int cell=0;cell<NEXT.cells();cell++) assertEquals(coldSnapshot.material(cell),snapshot.material(cell));
        System.out.printf("SHIFT_REUSE geometry reads %d -> %d; coverage reads %d -> %d; reused sky positions %d%n",NEXT.cells(),capture.readCells(),cold.sampledCells(),reused.sampledCells(),reused.reusedCells());
    }
    @Test void sourceEditsAreRecapturedAndRetirementWorldAndMaterialChangesWithdrawLeases() {
        var data=new Data();var donor=new World(data,OLD);var snapshot=capture(donor,OLD,List.of()).snapshot();var valid=new AtomicBoolean(true);
        var heat=new Heat(3,60,Double.NaN,false);data.cells.put(blockKey(1,1,1),new Cell(AIR.shape(),1,1,1,1,heat));
        var lease=new LocalAirReuse(snapshot,null,Set.of(blockKey(1,1,1)),donor,valid::get);
        var target=new World(data,NEXT);var c=capture(target,NEXT,List.of(lease));
        assertEquals(17,c.reusedCells());assertEquals(heat,c.snapshot().heat(NEXT.index(0,1,1)));
        valid.set(false);assertEquals(0,capture(target,NEXT,List.of(lease)).reusedCells());valid.set(true);
        var other=new World(new Data(),NEXT);assertEquals(0,capture(other,NEXT,List.of(lease)).reusedCells());
        data.material++;assertEquals(0,capture(target,NEXT,List.of(lease)).reusedCells());
    }
    @Test void importedProofDependenciesSurviveDonorRetirementAndProtectExternalEditsAndChunks() {
        var data=new Data();data.proofContext=true;var donor=new World(data,OLD);
        var old=capture(donor,OLD,List.of()).snapshot();var proof=coverage(donor,old,List.of()).certificate();var valid=new AtomicBoolean(true);
        var lease=new LocalAirReuse(old,proof,Set.of(),donor,valid::get);var target=new World(data,NEXT);
        var next=capture(target,NEXT,List.of(lease)).snapshot();var accepted=coverage(target,next,List.of(lease));
        assertTrue(accepted.reusedCells()>0);assertTrue(target.deps.affectsBlock(20,10,20));
        var certificate=accepted.certificate();valid.set(false);long revision=target.deps.get();target.deps.chunkChanged(20>>4,20>>4);
        assertEquals(revision+1,target.deps.get());assertFalse(certificate.current(target));
    }
    @Test void donorRevisionDuringSlicedCapturePreventsFurtherReuseAndTargetRevisionRejectsTheSnapshot() {
        var data=new Data();var donor=new World(data,OLD);var old=capture(donor,OLD,List.of()).snapshot();
        var lease=new LocalAirReuse(old,null,Set.of(),donor,()->true);var target=new World(data,NEXT);
        var capture=new LocalAirGeometryCapture(NEXT,NEXT.cells(),target,null,Set.of(),List.of(lease));
        capture.advance(1,Long.MAX_VALUE);int reused=capture.reusedCells();data.edit(1,1,1,SOLID);
        capture.advance(100,Long.MAX_VALUE);assertEquals(Status.STALE,capture.status());assertEquals(reused,capture.reusedCells());
        assertThrows(IllegalStateException.class,capture::snapshot);
    }
    private static void prepare(LocalAirPreparation p,boolean field) {
        for(int i=0;i<500;i++) {
            p.observe(0,1);p.advance(1000,Long.MAX_VALUE);p.observe(0,1);var work=p.takeWork();if(work!=null) p.complete(work.run());
            if(field?p.field()!=null:p.published()!=null) return;
        }
        fail(p.status()+" "+p.runReason());
    }
    @Test void layoutTargetReadsThermalInputsOnlyForNewCellsAndWaitsForCommitToReportPublication() {
        var data=new Data();var donorWorld=new World(data,OLD);
        var donor=new LocalAirPreparation(donorWorld,OLD,OLD.cells(),10000,new LocalAirRun.Settings(20,0,0,4));
        donor.thermalInitialization(s->c->new LocalAirThermalState.Value(null,5,true,"CONTROL"));prepare(donor,true);
        var targetWorld=new World(data,NEXT);var target=new LocalAirPreparation(targetWorld,NEXT,NEXT.cells(),10000,new LocalAirRun.Settings(20,0,0,4));
        int[] reads={0};target.thermalInitialization(s->c->{reads[0]++;return LocalAirThermalState.Value.ambient();});
        target.reuseEvidence(List.of(donor.reuseEvidence()));target.awaitLayoutSeed();prepare(target,false);
        assertEquals(9,reads[0]);assertEquals(9,target.thermalReadCells());assertEquals(18,target.thermalReusedCells());
        assertNull(target.startup().firstFieldMs());assertTrue(donor.holdLayout(0,1));assertTrue(target.holdLayout(0,1));
        target.installLayoutSeed(LocalAirRun.LayoutSeed.from(List.of(donor.layoutCheckpoint())));prepare(target,true);
        assertNull(target.startup().firstFieldMs(),"A projected target still awaits public ownership commit");
        target.resumeLayout();assertNotNull(target.startup().firstFieldMs());
        var frame=target.field();assertEquals(25,frame.temperature(frame.flow.nodeAt(.5,1.5,1.5)),1e-12);
        assertEquals(0,frame.restoredCells,"Live donor cells must not inflate disk restoration counts");
        assertEquals(0,frame.sourceEnergy);assertEquals(0,frame.acceptedSteps);donor.close();target.close();
    }
}
