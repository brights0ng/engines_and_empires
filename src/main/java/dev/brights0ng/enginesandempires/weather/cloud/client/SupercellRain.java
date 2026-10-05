package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.List;

/**
 * Where a supercell's rain falls, from its layout ({@link Supercell}) at the design proportions (no client tuning, the
 * design's variety), so the server and every client agree. Pure Java, used by the rain query on both sides.
 *
 * <p>All positions are anchor-local x and z (relative to the formation's anchor cluster's centre), like
 * {@link Supercell}'s.
 *
 * @param ux       the updraft's centre
 * @param uz       the updraft's centre
 * @param ru       the updraft's radius
 * @param ffX      the forward flank's centre
 * @param ffZ      the forward flank's centre
 * @param ffAlong  the forward flank's half-length, along the drift
 * @param ffAcross its half-width across it
 * @param dx       the drift direction (unit)
 * @param dz       the drift direction
 * @param rx       right of the drift (unit)
 * @param rz       right of the drift
 */
public record SupercellRain(double ux, double uz, double ru, double ffX, double ffZ, double ffAlong, double ffAcross,
                            double dx, double dz, double rx, double rz) {

    /** The rain layout of the supercell made of clusters {@code visible}, drifting along (dx, dz). */
    public static SupercellRain of(List<CloudShape> visible, CloudShape anchor, double dx, double dz) {
        Supercell s = new Supercell(visible, anchor, dx, dz,
                Supercell.Look.of(anchor.regionId(), 1, Supercell.Tuning.DESIGN));
        return new SupercellRain(s.ux, s.uz, s.ru, s.ffX, s.ffZ, s.ffAlong, s.ffAcross, s.dx, s.dz, s.rx, s.rz);
    }

    /** Whether formation {@code visible} is drawn as a supercell. */
    public static boolean is(List<CloudShape> visible) {
        return Supercell.isSupercell(visible);
    }

    /**
     * How hard it rains at anchor-local (x, z), 0-1, before the storm's lifecycle: heavy under the forward flank, a
     * lighter rear flank wrapping round behind the updraft, and dry under the updraft's rain-free base.
     */
    public double rain(double x, double z) {
        double du = Math.hypot(x - ux, z - uz);
        double u = (x - ffX) * dx + (z - ffZ) * dz;
        double w = (x - ffX) * rx + (z - ffZ) * rz;
        double q = Math.sqrt((u / ffAlong) * (u / ffAlong) + (w / ffAcross) * (w / ffAcross));
        double forward = 1 - smooth(0.4, 1, q);
        double ahead = (x - ux) * dx + (z - uz) * dz;
        double rear = 0.35 * (1 - smooth(1.3 * ru, 2.2 * ru, du)) * (1 - smooth(0, ru, ahead));
        double dry = smooth(0.75 * ru, 1.05 * ru, du);
        return Math.max(forward, rear) * dry;
    }

    /** How far from the anchor rain can fall (blocks). */
    public double reach() {
        return Math.max(Math.hypot(ffX, ffZ) + Math.max(ffAlong, ffAcross), Math.hypot(ux, uz) + 2.2 * ru);
    }

    static double smooth(double e0, double e1, double v) {
        double t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
