// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Prepaid, bounded natural-preparation continuation. Each slice requeues behind other pool work.
 * No world/owner access, no new thread, and no additional mutable solver owner.
 */
final class LocalAirBatch implements Runnable {
    static final long MAX_NANOS=50_000_000L;
    private static final int MAX_SLICES=64;
    private final Executor executor;
    private final LocalAirPreparation.Work work;
    private final Consumer<LocalAirPreparation.Result> output;
    private final long allowance;
    private long queued=System.nanoTime(),wall,queue,cpu,maxSlice;
    private int slices;
    private boolean cpuKnown=true;
    private LocalAirPreparation.Result last;
    private LocalAirBatch(Executor executor,LocalAirPreparation.Work work,long allowance,Consumer<LocalAirPreparation.Result> output) {
        this.executor=executor;this.work=work;this.output=output;this.allowance=Math.max(1,Math.min(MAX_NANOS,allowance));
        if(!work.estimate()) throw new IllegalArgumentException("Only natural preparation can continue without owner collection");
    }
    static void submit(Executor executor,LocalAirPreparation.Work work,long allowance,Consumer<LocalAirPreparation.Result> output) {
        executor.execute(new LocalAirBatch(executor,work,allowance,output));
    }
    @Override public void run() {
        if(last!=null && !work.continues(last)) {finish(null);return;}
        last=work.runSlice(queued,allowance-wall);
        var timing=last.timing();slices++;wall+=last.nanos();queue+=timing.queueNanos();
        cpuKnown&=timing.cpuNanos()>=0;if(timing.cpuNanos()>=0) cpu+=timing.cpuNanos();
        maxSlice=Math.max(maxSlice,last.nanos());
        if(work.continues(last) && wall<allowance && slices<MAX_SLICES) {
            queued=System.nanoTime();
            try {executor.execute(this);}
            catch(java.util.concurrent.RejectedExecutionException error) {finish(error);}
        } else finish(null);
    }
    private void finish(Throwable error) {
        work.batchCompleted();
        var timing=new LocalAirMetrics.Timing(last.timing().stage(),queue,wall,cpuKnown?cpu:-1,slices,maxSlice);
        output.accept(new LocalAirPreparation.Result(work,last.plan(),last.completion(),last.simulation(),wall,
                error==null?last.error():error,timing,last.estimation()));
    }
}
