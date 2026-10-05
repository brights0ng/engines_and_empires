package dev.brights0ng.enginesandempires.oregen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Measurements on generated bodies, for tests that check a shape looks like what it claims to be. */
final class BodyMetrics {

    /** How a body's ore is spread: standard deviations along its three principal axes (largest first), and the directions of the longest and shortest. */
    record Spread(double longest, double middle, double shortest, double[] longestAxis, double[] shortestAxis) {
    }

    static List<int[]> oreCells(DepositBody body) {
        List<int[]> cells = new ArrayList<>();
        body.forEach((dx, dy, dz, kind) -> {
            if (kind == DepositBody.ORE || kind == DepositBody.RICH) {
                cells.add(new int[]{dx, dy, dz});
            }
        });
        return cells;
    }

    static Spread spread(DepositBody body) {
        List<int[]> cells = oreCells(body);
        double[] mean = new double[3];
        for (int[] c : cells) {
            for (int i = 0; i < 3; i++) {
                mean[i] += c[i];
            }
        }
        for (int i = 0; i < 3; i++) {
            mean[i] /= cells.size();
        }
        double[][] cov = new double[3][3];
        for (int[] c : cells) {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    cov[i][j] += (c[i] - mean[i]) * (c[j] - mean[j]);
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                cov[i][j] /= cells.size();
            }
        }
        double[][] vectors = new double[3][3];
        double[] values = jacobi(cov, vectors);
        int[] order = {0, 1, 2};
        for (int a = 0; a < 3; a++) {
            for (int b = a + 1; b < 3; b++) {
                if (values[order[b]] > values[order[a]]) {
                    int t = order[a];
                    order[a] = order[b];
                    order[b] = t;
                }
            }
        }
        double[] longest = {vectors[0][order[0]], vectors[1][order[0]], vectors[2][order[0]]};
        double[] shortest = {vectors[0][order[2]], vectors[1][order[2]], vectors[2][order[2]]};
        return new Spread(Math.sqrt(Math.max(0, values[order[0]])), Math.sqrt(Math.max(0, values[order[1]])),
                Math.sqrt(Math.max(0, values[order[2]])), longest, shortest);
    }

    /** Eigenvalues of a symmetric 3x3 matrix by Jacobi rotations; eigenvectors are the columns of {@code vectors}. */
    private static double[] jacobi(double[][] m, double[][] vectors) {
        double[][] a = new double[3][3];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(m[i], 0, a[i], 0, 3);
            vectors[i][i] = 1.0;
        }
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            if (off < 1.0e-12) {
                break;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1.0e-15) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                    if (theta == 0.0) {
                        t = 1.0;
                    }
                    double c = 1.0 / Math.sqrt(t * t + 1.0);
                    double s = t * c;
                    for (int k = 0; k < 3; k++) {
                        double akp = a[k][p];
                        double akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p][k];
                        double aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = vectors[k][p];
                        double vkq = vectors[k][q];
                        vectors[k][p] = c * vkp - s * vkq;
                        vectors[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        return new double[]{a[0][0], a[1][1], a[2][2]};
    }

    /** How many separate 6-connected clumps the ore blocks form. */
    static int components(DepositBody body) {
        Set<Long> remaining = new HashSet<>();
        for (int[] c : oreCells(body)) {
            remaining.add(key(c[0], c[1], c[2]));
        }
        int count = 0;
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!remaining.isEmpty()) {
            long start = remaining.iterator().next();
            remaining.remove(start);
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                long current = queue.poll();
                int x = (int) (current >> 42) - (1 << 20);
                int y = (int) ((current >> 21) & 0x1FFFFF) - (1 << 20);
                int z = (int) (current & 0x1FFFFF) - (1 << 20);
                for (int[] s : steps) {
                    long next = key(x + s[0], y + s[1], z + s[2]);
                    if (remaining.remove(next)) {
                        queue.add(next);
                    }
                }
            }
            count++;
        }
        return count;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 20)) << 42) | ((long) (y + (1 << 20)) << 21) | (long) (z + (1 << 20));
    }

    private BodyMetrics() {
    }
}
