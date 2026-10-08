package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.Arrays;

/**
 * Small caches keyed by a long, without boxing, for the mesher's per-section caches (2026-10-08: boxed {@code Long}
 * keys were gigabytes of garbage per minute and a tenth of a storm's build time). Keys should already be well mixed
 * ({@link CloudVoxelizer#mix}). Not thread-safe: one per section build.
 */
final class LongCaches {

    private static int slot(long k, int mask) {
        return (int) (k ^ (k >>> 29)) & mask;
    }

    /** long to float, open addressing; {@link #get} returns {@code missing} for an absent key. */
    static final class Floats {
        private long[] keys = new long[1024];
        private float[] vals = new float[1024];
        private boolean[] used = new boolean[1024];
        private int size;

        float get(long k, float missing) {
            int mask = keys.length - 1;
            for (int s = slot(k, mask); used[s]; s = (s + 1) & mask) {
                if (keys[s] == k) {
                    return vals[s];
                }
            }
            return missing;
        }

        void put(long k, float v) {
            if (size * 2 >= keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int s = slot(k, mask);
            while (used[s]) {
                if (keys[s] == k) {
                    vals[s] = v;
                    return;
                }
                s = (s + 1) & mask;
            }
            used[s] = true;
            keys[s] = k;
            vals[s] = v;
            size++;
        }

        private void grow() {
            long[] ok = keys;
            float[] ov = vals;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            vals = new float[ok.length * 2];
            used = new boolean[ok.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ou[i]) {
                    put(ok[i], ov[i]);
                }
            }
        }
    }

    /** long to long, open addressing; {@link #get} returns {@code missing} for an absent key. */
    static final class Longs {
        private long[] keys = new long[1024];
        private long[] vals = new long[1024];
        private boolean[] used = new boolean[1024];
        private int size;

        long get(long k, long missing) {
            int mask = keys.length - 1;
            for (int s = slot(k, mask); used[s]; s = (s + 1) & mask) {
                if (keys[s] == k) {
                    return vals[s];
                }
            }
            return missing;
        }

        void put(long k, long v) {
            if (size * 2 >= keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int s = slot(k, mask);
            while (used[s]) {
                if (keys[s] == k) {
                    vals[s] = v;
                    return;
                }
                s = (s + 1) & mask;
            }
            used[s] = true;
            keys[s] = k;
            vals[s] = v;
            size++;
        }

        private void grow() {
            long[] ok = keys;
            long[] ov = vals;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            vals = new long[ok.length * 2];
            used = new boolean[ok.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ou[i]) {
                    put(ok[i], ov[i]);
                }
            }
        }
    }

    /** long to object, open addressing; {@link #get} returns null for an absent key. */
    static final class Objects<V> {
        private long[] keys = new long[256];
        private Object[] vals = new Object[256];
        private int size;

        @SuppressWarnings("unchecked")
        V get(long k) {
            int mask = keys.length - 1;
            for (int s = slot(k, mask); vals[s] != null; s = (s + 1) & mask) {
                if (keys[s] == k) {
                    return (V) vals[s];
                }
            }
            return null;
        }

        void put(long k, V v) {
            if (size * 2 >= keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int s = slot(k, mask);
            while (vals[s] != null) {
                if (keys[s] == k) {
                    vals[s] = v;
                    return;
                }
                s = (s + 1) & mask;
            }
            keys[s] = k;
            vals[s] = v;
            size++;
        }

        @SuppressWarnings("unchecked")
        private void grow() {
            long[] ok = keys;
            Object[] ov = vals;
            keys = new long[ok.length * 2];
            vals = new Object[ok.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ov[i] != null) {
                    put(ok[i], (V) ov[i]);
                }
            }
        }
    }

    /**
     * A direct-mapped cache of objects that are refilled in place: {@link #slot} says where key {@code k} lives;
     * {@link #has} whether that slot holds it now. A miss reuses the slot's object, so nothing is allocated once warm.
     */
    static final class Slots<V> {
        final long[] keys;
        final Object[] vals;
        private final boolean[] used;

        Slots(int size) {
            keys = new long[size];
            vals = new Object[size];
            used = new boolean[size];
            Arrays.fill(keys, 0);
        }

        int slot(long k) {
            return LongCaches.slot(k, keys.length - 1);
        }

        boolean has(int s, long k) {
            return used[s] && keys[s] == k;
        }

        @SuppressWarnings("unchecked")
        V at(int s) {
            return (V) vals[s];
        }

        void set(int s, long k, V v) {
            keys[s] = k;
            vals[s] = v;
            used[s] = true;
        }
    }

    private LongCaches() {
    }
}
