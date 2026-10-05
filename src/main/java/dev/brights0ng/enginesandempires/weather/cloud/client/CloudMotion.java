package dev.brights0ng.enginesandempires.weather.cloud.client;

/**
 * One cloud's smoothed horizontal motion between Project Atmosphere's once-a-second updates. Pure Java, so it can be
 * tested.
 *
 * <p>PA moves a cloud every tick by that tick's wind, gusts included, so the velocity in one update is just that tick's.
 * Here the velocity is measured from how far the cloud actually moved between two updates. When an update disagrees
 * with where the cloud was being drawn, the difference is eased out linearly over {@link #EASE_TICKS} rather than
 * snapped. A bigger one (over {@link #SMALL_DISTANCE}: PA moving a cluster to the weighted middle of a merge) is eased
 * over {@link #BIG_EASE_TICKS}; only one over {@link #SNAP_DISTANCE} (a reload, a teleport) is taken at once.
 */
final class CloudMotion {

    static final double EASE_TICKS = 20;
    static final double BIG_EASE_TICKS = 100;
    static final double SMALL_DISTANCE = 64;
    static final double SNAP_DISTANCE = 400;

    private double refX;
    private double refZ;
    private long refTick;
    private double vx;
    private double vz;
    private double errX;
    private double errZ;
    private double errStart;
    private double easeTicks = EASE_TICKS;

    /** Starts at PA's first update for the cloud. */
    CloudMotion(double x, double z, long simulationTick, double vx, double vz) {
        this.refX = x;
        this.refZ = z;
        this.refTick = simulationTick;
        this.vx = vx;
        this.vz = vz;
        this.errStart = Double.NEGATIVE_INFINITY;
    }

    /**
     * Takes a PA update: the centre ({@code x}, {@code z}) as of server tick {@code simulationTick}, PA's own velocity
     * (used only when the time since the last update is unusable), and the client time {@code now}.
     */
    void observe(double x, double z, long simulationTick, double paVx, double paVz, double now) {
        if (simulationTick == refTick) {
            return;
        }
        double shownX = x(now);
        double shownZ = z(now);
        long dt = simulationTick - refTick;
        // Where it would be if it had kept its speed. A big miss isn't speed but PA moving it (a merge): keep the
        // speed and glide to the new place, instead of racing off at the jump's apparent speed for a second.
        double missX = x - (refX + vx * dt);
        double missZ = z - (refZ + vz * dt);
        boolean jumped = missX * missX + missZ * missZ > SMALL_DISTANCE * SMALL_DISTANCE;
        if (dt > 0 && dt <= 400 && !jumped) {
            vx = (x - refX) / dt;
            vz = (z - refZ) / dt;
        } else if (!jumped) {
            vx = paVx;
            vz = paVz;
        }
        refX = x;
        refZ = z;
        refTick = simulationTick;
        errX = 0;
        errZ = 0;
        double ex = shownX - x(now);
        double ez = shownZ - z(now);
        double e2 = ex * ex + ez * ez;
        if (e2 < SNAP_DISTANCE * SNAP_DISTANCE) {
            errX = ex;
            errZ = ez;
        }
        easeTicks = e2 > SMALL_DISTANCE * SMALL_DISTANCE ? BIG_EASE_TICKS : EASE_TICKS;
        errStart = now;
    }

    /** Where the centre is drawn at client time {@code t} (ticks, partial allowed). */
    double x(double t) {
        return refX + vx * (t - refTick) + errX * fade(t);
    }

    double z(double t) {
        return refZ + vz * (t - refTick) + errZ * fade(t);
    }

    double vx() {
        return vx;
    }

    double vz() {
        return vz;
    }

    private double fade(double t) {
        return Math.max(0, 1 - (t - errStart) / easeTicks);
    }
}
