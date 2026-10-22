// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** Owner-thread admission/publication gate for a future existing-worker dispatch.
 * Owns no executor or solver. Published geometry alone is not a ready temperature/velocity field.
 */
final class LocalAirGeometryHandoff {
    static final class Job {
        private final Snapshot snapshot;
        private final LocalAirCoverage.Certificate certificate;
        private Job(Snapshot snapshot,LocalAirCoverage.Certificate certificate) { this.snapshot=snapshot;this.certificate=certificate; }
        Completion bake(ThermalGeometry.Coefficients coefficients) { return new Completion(this,certificate.bake(snapshot,coefficients)); }
    }
    static final class Completion {
        private final Job job;
        private final Prepared prepared;
        private Completion(Job job,Prepared prepared) { this.job=job;this.prepared=prepared; }
    }
    private final Thread owner=Thread.currentThread();
    private final LocalAirCoverage.World world;
    private Job active,publishedJob;
    private Prepared published;
    LocalAirGeometryHandoff(LocalAirCoverage.World world) { this.world=java.util.Objects.requireNonNull(world); }
    private void checkOwner() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Handoff requires owner thread"); }
    private boolean current(Job job) { return job.certificate.current(world); }
    Job begin(Snapshot snapshot,LocalAirCoverage.Certificate certificate) {
        checkOwner();invalidate();
        if(!certificate.current(world)) throw new IllegalStateException("Stale coverage certificate");
        // Validate identity before dispatch, without baking geometry on the owner thread.
        if(!LocalAirCoverage.matches(certificate,snapshot)) throw new IllegalArgumentException("Certificate belongs to another snapshot");
        active=new Job(snapshot,certificate);return active;
    }
    boolean complete(Completion result) {
        checkOwner();
        if(result.job!=active) return false;
        if(!current(active)) { invalidate();return false; }
        published=result.prepared;publishedJob=active;active=null;return true;
    }
    void failed(Job job) { checkOwner();if(job==active) invalidate(); }
    void invalidate() { checkOwner();active=null;published=null;publishedJob=null; }
    Prepared published() {
        checkOwner();if(publishedJob!=null && !current(publishedJob)) invalidate();
        return published;
    }
}
