// Written by Gemini.
package dev.brights0ng.seismicsurveyance.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Computational geometry utilities for 2D map polygon rendering:
 * <ul>
 *   <li>Ear-clipping triangulation for arbitrary simple (convex or concave) 2D polygons.</li>
 *   <li>Continuous, seamless miter ribbon generation for smooth closed polygon outlines without creases or gaps.</li>
 * </ul>
 */
public final class MapPolygonRenderer {

    private MapPolygonRenderer() {
    }

    /**
     * Deduplicates consecutive identical or near-identical points within a tolerance distance.
     */
    public static List<double[]> deduplicate(double[][] points, double tolerance) {
        if (points == null || points.length == 0) {
            return List.of();
        }
        double tolSq = tolerance * tolerance;
        List<double[]> clean = new ArrayList<>(points.length);
        for (double[] p : points) {
            if (clean.isEmpty()) {
                clean.add(p);
            } else {
                double[] last = clean.get(clean.size() - 1);
                double dx = p[0] - last[0];
                double dy = p[1] - last[1];
                if (dx * dx + dy * dy > tolSq) {
                    clean.add(p);
                }
            }
        }
        if (clean.size() > 1) {
            double[] first = clean.get(0);
            double[] last = clean.get(clean.size() - 1);
            double dx = first[0] - last[0];
            double dy = first[1] - last[1];
            if (dx * dx + dy * dy <= tolSq) {
                clean.remove(clean.size() - 1);
            }
        }
        return clean;
    }

    /**
     * Computes the signed area of a 2D polygon.
     * Positive for counter-clockwise in Cartesian coordinates (Y-up),
     * or positive for clockwise in screen coordinates (Y-down).
     */
    public static double signedArea(List<double[]> pts) {
        int n = pts.size();
        if (n < 3) {
            return 0.0;
        }
        double area = 0.0;
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            double[] q = pts.get((i + 1) % n);
            area += p[0] * q[1] - q[0] * p[1];
        }
        return area * 0.5;
    }

    /**
     * Triangulates a simple (convex or concave) closed polygon into a list of non-overlapping
     * interior triangles using the ear-clipping algorithm.
     *
     * @param polygon array of 2D coordinates around the perimeter
     * @return list of non-overlapping interior triangles, each represented as double[3][2]
     */
    public static List<double[][]> triangulate(double[][] polygon) {
        List<double[]> pts = deduplicate(polygon, 1e-6);
        int n = pts.size();
        if (n < 3) {
            return List.of();
        }
        if (n == 3) {
            List<double[][]> single = new ArrayList<>(1);
            single.add(new double[][]{pts.get(0), pts.get(1), pts.get(2)});
            return single;
        }

        // Ensure CCW orientation (signed area > 0)
        double area = signedArea(pts);
        if (Math.abs(area) < 1e-12) {
            return List.of();
        }
        if (area < 0) {
            List<double[]> reversed = new ArrayList<>(n);
            for (int i = n - 1; i >= 0; i--) {
                reversed.add(pts.get(i));
            }
            pts = reversed;
        }

        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = i;
        }
        int remaining = n;
        List<double[][]> triangles = new ArrayList<>(n - 2);

        int count = 0;
        int maxIterations = 3 * n;
        int curr = 0;

        while (remaining > 2 && count < maxIterations) {
            int prev = (curr - 1 + remaining) % remaining;
            int next = (curr + 1) % remaining;

            double[] a = pts.get(v[prev]);
            double[] b = pts.get(v[curr]);
            double[] c = pts.get(v[next]);

            if (isEar(a, b, c, pts, v, remaining, prev, curr, next)) {
                triangles.add(new double[][]{a, b, c});
                for (int i = curr; i < remaining - 1; i++) {
                    v[i] = v[i + 1];
                }
                remaining--;
                curr = curr % remaining;
                count = 0;
            } else {
                curr = (curr + 1) % remaining;
                count++;
            }
        }

        // Degenerate fallback if ear clipping was blocked by collinear vertices
        if (remaining > 2) {
            double[] a = pts.get(v[0]);
            for (int i = 1; i < remaining - 1; i++) {
                triangles.add(new double[][]{a, pts.get(v[i]), pts.get(v[i + 1])});
            }
        }

        return triangles;
    }

    private static boolean isEar(double[] a, double[] b, double[] c, List<double[]> pts,
                                 int[] v, int remaining, int prevIdx, int currIdx, int nextIdx) {
        // Vertex b must be strictly convex in CCW polygon: cross product of (b - a) and (c - b) > 0
        double cp = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
        if (cp <= 1e-11) {
            return false;
        }

        // No other vertex of the remaining polygon may lie strictly inside or on triangle (a, b, c)
        for (int i = 0; i < remaining; i++) {
            if (i == prevIdx || i == currIdx || i == nextIdx) {
                continue;
            }
            double[] p = pts.get(v[i]);
            if (pointInTriangle(p, a, b, c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointInTriangle(double[] p, double[] a, double[] b, double[] c) {
        double cp1 = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
        if (cp1 < -1e-11) return false;

        double cp2 = (c[0] - b[0]) * (p[1] - b[1]) - (c[1] - b[1]) * (p[0] - b[0]);
        if (cp2 < -1e-11) return false;

        double cp3 = (a[0] - c[0]) * (p[1] - c[1]) - (a[1] - c[1]) * (p[0] - c[0]);
        return cp3 >= -1e-11;
    }

    /**
     * Generates a continuous, seamless miter ribbon for a closed polygon outline.
     * Adjacent segment quads share exact corner vertices at each polygon vertex,
     * eliminating gaps, cracks, and double-blending overlap creases in curves.
     *
     * @param polygon array of 2D coordinates around the perimeter
     * @param halfWidth half of the stroke line width
     * @return array of quads, where each quad is a double[4][2] [P_i+m_i, P_{i+1}+m_{i+1}, P_{i+1}-m_{i+1}, P_i-m_i]
     */
    public static double[][][] strokeRibbon(double[][] polygon, double halfWidth) {
        List<double[]> pts = deduplicate(polygon, 1e-6);
        int n = pts.size();
        if (n < 3 || halfWidth <= 0.0) {
            return new double[0][][];
        }

        // Segment unit normals (perpendicular pointing to the left of p1 -> p2)
        double[][] normals = new double[n][2];
        for (int i = 0; i < n; i++) {
            double[] p1 = pts.get(i);
            double[] p2 = pts.get((i + 1) % n);
            double dx = p2[0] - p1[0];
            double dy = p2[1] - p1[1];
            double len = Math.hypot(dx, dy);
            if (len < 1e-9) {
                normals[i] = new double[]{0.0, 1.0};
            } else {
                normals[i] = new double[]{-dy / len, dx / len};
            }
        }

        // Miter offset vector at each vertex
        double[][] miter = new double[n][2];
        double maxMiterLen = halfWidth * 3.0;
        double maxMiterLenSq = maxMiterLen * maxMiterLen;

        for (int i = 0; i < n; i++) {
            int prev = (i - 1 + n) % n;
            double[] nIn = normals[prev];
            double[] nOut = normals[i];

            double dot = nIn[0] * nOut[0] + nIn[1] * nOut[1];
            double denom = 1.0 + dot;

            double nx = nIn[0] + nOut[0];
            double ny = nIn[1] + nOut[1];

            if (denom < 0.2 || Math.hypot(nx, ny) < 1e-6) {
                // Very sharp hairpin turn or parallel reversal: clamp along sum or fallback
                double len = Math.hypot(nx, ny);
                if (len < 1e-6) {
                    miter[i] = new double[]{nIn[0] * halfWidth, nIn[1] * halfWidth};
                } else {
                    double scale = maxMiterLen / len;
                    miter[i] = new double[]{nx * scale, ny * scale};
                }
            } else {
                double scale = halfWidth / denom;
                double mx = nx * scale;
                double my = ny * scale;
                double lenSq = mx * mx + my * my;
                if (lenSq > maxMiterLenSq) {
                    double clampScale = maxMiterLen / Math.sqrt(lenSq);
                    miter[i] = new double[]{mx * clampScale, my * clampScale};
                } else {
                    miter[i] = new double[]{mx, my};
                }
            }
        }

        // Quads for each segment:
        // [ P_i + m_i, P_{i+1} + m_{i+1}, P_{i+1} - m_{i+1}, P_i - m_i ]
        double[][][] quads = new double[n][4][2];
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            double[] p1 = pts.get(i);
            double[] p2 = pts.get(next);
            double[] m1 = miter[i];
            double[] m2 = miter[next];

            quads[i][0] = new double[]{p1[0] + m1[0], p1[1] + m1[1]};
            quads[i][1] = new double[]{p2[0] + m2[0], p2[1] + m2[1]};
            quads[i][2] = new double[]{p2[0] - m2[0], p2[1] - m2[1]};
            quads[i][3] = new double[]{p1[0] - m1[0], p1[1] - m1[1]};
        }

        return quads;
    }
}
