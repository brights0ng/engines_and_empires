import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Summarises a Java Flight Recorder file: where the sampled threads spent their time, by method, both "self" (the
 * method itself was running) and "total" (it was anywhere on the stack). Run with any JDK 17+:
 *
 * <pre>java tools/profiling/JfrTop.java run/cloud-profile.jfr [thread-name-filter] [top-n]</pre>
 *
 * The filter keeps samples whose thread name contains it (e.g. "cloud mesher"); "*" keeps every thread and lists
 * the busiest threads first.
 */
public class JfrTop {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        String filter = args.length > 1 ? args[1] : "*";
        int top = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        Map<String, Integer> self = new HashMap<>(), total = new HashMap<>(), byThread = new HashMap<>();
        Map<String, Long> alloc = new HashMap<>();
        int samples = 0;
        for (RecordedEvent e : RecordingFile.readAllEvents(file)) {
            String type = e.getEventType().getName();
            String thread = e.hasField("sampledThread") && e.getThread("sampledThread") != null
                    ? e.getThread("sampledThread").getJavaName()
                    : e.getThread() != null ? e.getThread().getJavaName() : "?";
            if (thread == null) {
                thread = "?";
            }
            if (type.equals("jdk.ExecutionSample")) {
                byThread.merge(thread, 1, Integer::sum);
            }
            if (!filter.equals("*") && !thread.contains(filter)) {
                continue;
            }
            RecordedStackTrace st = e.getStackTrace();
            if (st == null) {
                continue;
            }
            List<RecordedFrame> frames = st.getFrames();
            if (frames.isEmpty()) {
                continue;
            }
            if (type.equals("jdk.ObjectAllocationSample")) {
                alloc.merge(name(frames.get(0)) + " -> " + e.getClass("objectClass").getName(),
                        e.getLong("weight"), Long::sum);
                continue;
            }
            if (!type.equals("jdk.ExecutionSample")) {
                continue;
            }
            samples++;
            self.merge(name(frames.get(0)), 1, Integer::sum);
            Set<String> seen = new HashSet<>();
            for (RecordedFrame f : frames) {
                String n = name(f);
                if (seen.add(n)) {
                    total.merge(n, 1, Integer::sum);
                }
            }
        }
        System.out.println("Samples per thread (all threads):");
        print(byThread, sum(byThread), 15);
        System.out.println("\n" + samples + " samples matching '" + filter + "'.\nSelf (running in the method):");
        print(self, samples, top);
        System.out.println("\nTotal (anywhere on the stack):");
        print(total, samples, top);
        if (!alloc.isEmpty()) {
            System.out.println("\nAllocation (sampled bytes, MB):");
            alloc.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(15)
                    .forEach(x -> System.out.printf("  %8.1f  %s%n", x.getValue() / 1e6, x.getKey()));
        }
    }

    private static String name(RecordedFrame f) {
        String c = f.getMethod().getType().getName();
        return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethod().getName();
    }

    private static int sum(Map<String, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static void print(Map<String, Integer> m, int of, int top) {
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(top)
                .forEach(e -> System.out.printf("  %5.1f%%  %6d  %s%n", 100.0 * e.getValue() / Math.max(1, of),
                        e.getValue(), e.getKey()));
    }
}
