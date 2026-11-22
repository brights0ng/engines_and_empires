// Written by Claude (M8e4).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

import dev.brights0ng.greedyfluids.region.AirFrame;

/**
 * M8e4: the hottest spots of air in a frame, for {@code /meteodyne airflow hottest}: the hottest air cell, then the
 * hottest at least {@code separation} cells (Chebyshev) from every spot taken before it, and so on, among the
 * {@link #CANDIDATES} hottest cells; with the air's mean and count.
 *
 * @param cells the spots' cells, frame cells, hottest first
 * @param mean  the mean air temperature over the frame, °C (NaN without air)
 * @param airCells the frame's air cells
 */
record AirflowHotSpots(List<int[]> cells, double mean, int airCells) {
    /** The least distance between two spots, blocks (Chebyshev). */
    static final int SEPARATION = 4;
    /** The hottest cells kept as candidates before spots are picked among them. */
    static final int CANDIDATES = 4096;

    static AirflowHotSpots of(AirFrame frame, int n, int separation) {
        PriorityQueue<double[]> top = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0])); // coolest first
        double sum = 0;
        int count = 0;
        for (int y = 0; y < frame.ny(); y++) {
            for (int z = 0; z < frame.nz(); z++) {
                for (int x = 0; x < frame.nx(); x++) {
                    double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
                    if (!frame.isAir(cx, cy, cz)) {
                        continue;
                    }
                    double t = frame.temperature(cx, cy, cz);
                    sum += t;
                    count++;
                    if (top.size() < CANDIDATES) {
                        top.add(new double[] {t, x, y, z});
                    } else if (t > top.peek()[0]) {
                        top.poll();
                        top.add(new double[] {t, x, y, z});
                    }
                }
            }
        }
        List<double[]> hot = new ArrayList<>(top);
        hot.sort((a, b) -> Double.compare(b[0], a[0]));
        List<int[]> spots = new ArrayList<>();
        for (double[] c : hot) {
            if (spots.size() >= n) {
                break;
            }
            int x = (int) c[1], y = (int) c[2], z = (int) c[3];
            boolean apart = true;
            for (int[] s : spots) {
                if (Math.max(Math.abs(s[0] - x), Math.max(Math.abs(s[1] - y), Math.abs(s[2] - z))) < separation) {
                    apart = false;
                    break;
                }
            }
            if (apart) {
                spots.add(new int[] {x, y, z});
            }
        }
        return new AirflowHotSpots(spots, count == 0 ? Double.NaN : sum / count, count);
    }
}
