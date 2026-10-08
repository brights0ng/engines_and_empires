package dev.brights0ng.enginesandempires.weather.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * A low's fronts, worked out from its state (position, size, life, turning) rather than stepped on their own, so they
 * always have the textbook shape and need no saving. Pure.
 *
 * <p>For a northern-style low (map view, north up; Minecraft +x east, +z south):
 * <ul>
 *   <li>the <b>warm front</b> runs east-southeast from the low;</li>
 *   <li>the <b>cold front</b> runs south-southwest, curling west at its tail, and swings round toward the southeast as
 *       the low ages (cold fronts move faster than warm fronts);</li>
 *   <li>from about 40% of its life the cold front catches the warm front from the centre outward: an <b>occluded
 *       front</b> runs from the low to the "triple point", and the other two start from there;</li>
 *   <li>the fronts grow in over the first 30% of the low's life and weaken as it fills.</li>
 * </ul>
 * Mirrored lows ({@code hemisphere} -1) are the same shape reflected north to south.
 */
public final class FrontGeometry {

    public enum Type { COLD, WARM, OCCLUDED }

    /**
     * One front: a polyline of world (x, z) points from the low outward, and how strong it is (0-1, by the low's
     * strength against its peak).
     */
    public record Front(Type type, List<double[]> points, double strength) {
    }

    static final int POINTS = 7;
    /** The warm front's direction from the low or triple point: radians from east toward south (northern frame). */
    public static final double WARM_ANGLE = Math.toRadians(15);

    /** The cold front's direction at life {@code f}: south-southwest, swinging toward southeast as the low ages. */
    public static double coldAngle(double f) {
        return Math.toRadians(110 - 40 * SimMath.smooth((f - 0.2) / 0.6));
    }

    public static List<Front> of(WeatherSystem low) {
        List<Front> fronts = new ArrayList<>(3);
        if (low.kind != WeatherSystem.Kind.LOW) {
            return fronts;
        }
        double f = low.life();
        double grow = SimMath.smooth(f / 0.3);
        if (grow < 0.05) {
            return fronts;
        }
        double strength = SimMath.clamp01(low.strength() / Math.max(1e-6, low.peak));
        double r = low.radius();
        double warmLen = 1.3 * r * grow;
        double coldLen = 1.7 * r * grow;
        double warmAngle = WARM_ANGLE;
        double coldAngle = coldAngle(f);
        double occ = SimMath.smooth((f - 0.4) / 0.4);
        int hem = low.hemisphere;

        double tx = low.x;
        double tz = low.z;
        if (occ > 0.02) {
            double occLen = occ * 0.6 * Math.min(warmLen, coldLen);
            double mid = (warmAngle + coldAngle) / 2;
            List<double[]> occluded = arc(low.x, low.z, mid - Math.toRadians(20), Math.toRadians(20), occLen, hem);
            double[] end = occluded.get(occluded.size() - 1);
            tx = end[0];
            tz = end[1];
            fronts.add(new Front(Type.OCCLUDED, occluded, strength));
            warmLen *= 1 - 0.5 * occ;
            coldLen *= 1 - 0.4 * occ;
        }
        fronts.add(new Front(Type.WARM, arc(tx, tz, warmAngle, Math.toRadians(-5), warmLen, hem), strength));
        fronts.add(new Front(Type.COLD, arc(tx, tz, coldAngle, Math.toRadians(25), coldLen, hem), strength));
        return fronts;
    }

    /**
     * A gently curving polyline from (x, z): starting at {@code angle} (radians from east toward south in the northern
     * frame), turning by {@code turn} over its {@code length}. Mirrored by {@code hem}.
     */
    static List<double[]> arc(double x, double z, double angle, double turn, double length, int hem) {
        List<double[]> pts = new ArrayList<>(POINTS);
        double px = x;
        double pz = z;
        pts.add(new double[]{px, pz});
        double step = length / (POINTS - 1);
        for (int i = 1; i < POINTS; i++) {
            double a = angle + turn * (i - 0.5) / (POINTS - 1);
            px += Math.cos(a) * step;
            pz += hem * Math.sin(a) * step;
            pts.add(new double[]{px, pz});
        }
        return pts;
    }

    /** The shortest distance from (x, z) to a polyline, and how far along it that is (0-1). {distance, along} */
    public static double[] distance(List<double[]> line, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        double along = 0;
        double total = 0;
        double[] lengths = new double[line.size()];
        for (int i = 1; i < line.size(); i++) {
            total += Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
            lengths[i] = total;
        }
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double len2 = dx * dx + dz * dz;
            double t = len2 <= 0 ? 0 : SimMath.clamp01(((x - a[0]) * dx + (z - a[1]) * dz) / len2);
            double px = a[0] + t * dx - x;
            double pz = a[1] + t * dz - z;
            double d = Math.sqrt(px * px + pz * pz);
            if (d < best) {
                best = d;
                along = total <= 0 ? 0 : (lengths[i - 1] + t * Math.sqrt(len2)) / total;
            }
        }
        return new double[]{best, along};
    }

    private FrontGeometry() {
    }
}
