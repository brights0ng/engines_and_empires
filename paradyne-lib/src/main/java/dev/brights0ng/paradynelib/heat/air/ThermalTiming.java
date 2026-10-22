// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Optional benchmark counters. Unsupported CPU/allocation measurements remain unavailable, never zero. */
public final class ThermalTiming {
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    public static long cpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()
                ? THREADS.getCurrentThreadCpuTime() : -1;
    }
    public static long allocated() {
        return THREADS instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported()
                && bean.isThreadAllocatedMemoryEnabled() ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
    }
    public static long difference(long before, long after) { return before < 0 || after < before ? -1 : after-before; }
    public record Summary(int count, Double mean, Double p50, Double p95, Double p99, Double max, double total) { }
    /** Single-owner collection; live worker results are added only when collected on the server thread. */
    public static final class Samples {
        private final List<Long> values = new ArrayList<>();
        public void add(long value) { if (value >= 0) { values.add(value); } }
        public Summary summary(double divisor) {
            if (values.isEmpty()) { return new Summary(0,null,null,null,null,null,0); }
            long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(sorted);
            double total=0;
            for (long value:sorted) { total+=value/divisor; }
            return new Summary(sorted.length,total/sorted.length,percentile(sorted,0.5)/divisor,
                    percentile(sorted,0.95)/divisor,percentile(sorted,0.99)/divisor,sorted[sorted.length-1]/divisor,total);
        }
        private static long percentile(long[] sorted,double fraction) {
            return sorted[Math.max(0,(int)Math.ceil(fraction*sorted.length)-1)];
        }
    }
    private ThermalTiming() { }
}
