package dev.brights0ng.enginesandempires.weather.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Steps the weather systems (phase 2 of {@code claude/weather-backbone-plan.md}). Pure: the server's
 * {@code WeatherSim} supplies the time, season, player positions and what has been seen before.
 *
 * <h2>Each step</h2>
 * <ol>
 *   <li><b>Move and age.</b> Lows ride the jet at 75% of its steering and drift toward their cold side as they mature;
 *       ordinary highs at 55%; blocking highs barely move. Lows are pushed around highs instead of running into them
 *       (blocking highs strongly, ordinary ones gently; 2026-10-06: a low overtaking a high cancelled out on the map
 *       while its fronts still made rain). Systems at the end of their life go.</li>
 *   <li><b>Forget the far away.</b> Systems more than three spacings beyond every player's zone go.</li>
 *   <li><b>Genesis.</b> Along every storm track crossing a player's zone (plus a margin upstream), wherever two lows are
 *       more than 1.4 spacings apart a new one forms one spacing east of the western one (or at the upstream end), and
 *       a high forms between each pair of lows. No new system forms within {@value #CLEARANCE} of a same-kind
 *       system's radius (the larger of the two), nor within {@value #CLEARANCE} of the other kind's
 *       radius of a low or high already there. A new system in ground already supplied before starts as a young wave;
 *       in fresh ground (a player arriving somewhere new) it starts part way through its life, so weather is already
 *       there.</li>
 * </ol>
 * Genesis is deterministic (hashed from the world seed, the track, the place and the in-game day), so a forecast run
 * agrees with the live one about where and when systems form. What differs (phase 7a, the sources of forecast error):
 * <ul>
 *   <li><b>Drift</b> ({@link Drift}): every system's speed, cross-track drift and depth wander slowly. Live runs step
 *       the drift with random numbers ({@code nudges}); forecast runs ({@code nudges} null) let it relax toward zero,
 *       its expected value.</li>
 *   <li><b>Make-up of systems not yet born</b>: a forecast copy ({@link #forForecast}) rolls the depth, size, life,
 *       track offset and blocking of the systems it sees forming from its own hash, so a storm it expects in a few days
 *       may turn out differently.</li>
 * </ul>
 */
public final class SystemsSim {

    /** A player's position: the centre of a zone kept supplied with weather. */
    public record Anchor(double x, double z) {
    }

    /** Whether weather has been simulated around (x, z) recently. */
    @FunctionalInterface
    public interface Coverage {
        boolean covered(double x, double z);
    }

    public static final int MAX_SYSTEMS = 400;
    static final double GAP = 1.4;
    static final double LOW_STEER = 0.75;
    static final double HIGH_STEER = 0.55;
    static final double BLOCK_STEER = 0.05;
    /** A new high or low keeps at least this many radii (the low's) away from the other kind. */
    static final double CLEARANCE = 1.2;

    private final JetStream jet;
    private final long seed;
    private final List<WeatherSystem> systems;
    private long nextId;
    private Drift.Settings drift = Drift.Settings.DEFAULT;
    /** 0 for the live run; otherwise the salt a forecast rolls new systems' make-up with. */
    private long traitSalt;
    /** A forecast's start (newborn systems' make-up blends toward the forecast's roll from it), or MIN_VALUE: fully. */
    private long forecastStart = Long.MIN_VALUE;

    public SystemsSim(JetStream jet, long seed, List<WeatherSystem> systems, long nextId) {
        this.jet = jet;
        this.seed = seed;
        this.systems = systems;
        this.nextId = nextId;
    }

    public List<WeatherSystem> systems() {
        return systems;
    }

    public long nextId() {
        return nextId;
    }

    public JetStream jet() {
        return jet;
    }

    /** How much systems drift (the server sets it from its config before each step). */
    public void setDrift(Drift.Settings drift) {
        this.drift = drift;
    }

    public Drift.Settings drift() {
        return drift;
    }

    /** A copy to run forward without touching this one. */
    public SystemsSim copy() {
        List<WeatherSystem> c = new ArrayList<>(systems.size());
        for (WeatherSystem s : systems) {
            c.add(s.copy());
        }
        SystemsSim copy = new SystemsSim(jet, seed, c, nextId);
        copy.drift = drift;
        copy.traitSalt = traitSalt;
        copy.forecastStart = forecastStart;
        return copy;
    }

    /**
     * A copy for a forecast: systems already on the map are copied as they are; systems that form during the
     * forecast get their make-up from {@code salt} instead of the live hash (any non-zero value; the forecast service
     * hashes it from the time the forecast is made, so successive forecasts can disagree about far-off storms).
     */
    public SystemsSim forForecast(long salt) {
        SystemsSim copy = copy();
        copy.traitSalt = salt == 0 ? 1 : salt;
        copy.forecastStart = Long.MIN_VALUE;
        return copy;
    }

    /**
     * A copy for a forecast starting at simulation time {@code start}: newborn systems' make-up blends from the live
     * roll (born at the start) to the forecast's own roll (born {@link #REROLL_TICKS} or more after it).
     */
    public SystemsSim forForecast(long salt, long start) {
        SystemsSim copy = forForecast(salt);
        copy.forecastStart = start;
        return copy;
    }

    /**
     * Steps {@code dt} ticks ending at game time {@code time}, in season {@code season} (-1 winter to +1 summer). With
     * no anchors nothing new forms and nothing is forgotten. {@code nudges} is null for a forecast run.
     */
    public void step(long time, long dt, double season, List<Anchor> anchors, Coverage coverage,
                     RandomGenerator nudges) {
        double seconds = time / 20.0;
        SimParams p = jet.params();
        SimParams.Seasonal seasonal = SimParams.Seasonal.of(season, p.blockChance());
        move(seconds, dt, season, nudges);
        systems.removeIf(WeatherSystem::dead);
        if (anchors.isEmpty()) {
            return;
        }
        double spacing = p.spacing() * seasonal.spacing();
        double keep = p.zoneRadius() + 3 * spacing;
        systems.removeIf(s -> anchors.stream().noneMatch(a ->
                Math.abs(s.x - a.x()) <= keep && Math.abs(s.z - a.z()) <= keep));
        for (Anchor a : anchors) {
            genesis(a, time, seconds, season, seasonal, spacing, coverage);
        }
    }

    private void move(double seconds, long dt, double season, RandomGenerator nudges) {
        double driftKeep = Drift.keep(dt, drift.correlationTicks());
        List<WeatherSystem> highs = new ArrayList<>();
        for (WeatherSystem s : systems) {
            if (s.kind == WeatherSystem.Kind.HIGH) {
                highs.add(s);
            }
        }
        for (WeatherSystem s : systems) {
            double[] steer = jet.steering(s.x, s.z, seconds, season);
            double share = s.kind == WeatherSystem.Kind.LOW ? LOW_STEER : s.blocking ? BLOCK_STEER : HIGH_STEER;
            double vx = steer[0] * share;
            double vz = steer[1] * share;
            double speed = Math.hypot(steer[0], steer[1]);
            if (s.kind == WeatherSystem.Kind.LOW) {
                // Maturing lows drift toward their cold side (north in a northern-style zone).
                double drift = s.life() > 0.35 ? 0.15 : 0.05;
                vz -= s.hemisphere * drift * speed;
                for (WeatherSystem b : highs) {
                    double dx = s.x - b.x;
                    double dz = s.z - b.z;
                    double d = Math.hypot(dx, dz);
                    double reach = (b.blocking ? 1.3 : 0.9) * b.radius();
                    if (d < reach && d > 1) {
                        double strength = b.blocking ? 0.8 : 0.5;
                        double push = speed * strength * (1 - d / reach);
                        double keep = b.blocking ? d / reach : 1 - 0.5 * (1 - d / reach);
                        vx = vx * keep + dx / d * push;
                        vz = vz * keep + dz / d * push;
                    }
                }
            }
            if (nudges != null) {
                s.driftSpeed = Drift.step(s.driftSpeed, driftKeep, nudges.nextGaussian());
                s.driftCross = Drift.step(s.driftCross, driftKeep, nudges.nextGaussian());
                s.driftDepth = Drift.step(s.driftDepth, driftKeep, nudges.nextGaussian());
            } else {
                s.driftSpeed *= driftKeep;
                s.driftCross *= driftKeep;
                s.driftDepth *= driftKeep;
            }
            double n = Drift.speedFactor(s.driftSpeed, drift.speed());
            vx *= n;
            vz = vz * n + drift.cross() * speed * s.driftCross;
            s.nudge = Drift.depthFactor(s.driftDepth, drift.depth());
            s.x += vx * dt;
            s.z += vz * dt;
            s.age += dt;
        }
    }

    private void genesis(Anchor a, long time, double seconds, double season, SimParams.Seasonal seasonal,
                         double spacing, Coverage coverage) {
        SimParams p = jet.params();
        double ts = p.trackSpacing();
        int kMin = (int) Math.floor((a.z() - p.zoneRadius()) / ts - 0.25);
        int kMax = (int) Math.ceil((a.z() + p.zoneRadius()) / ts + 0.25);
        double start = a.x() - p.zoneRadius() - 1.5 * spacing;
        double end = a.x() + p.zoneRadius() + 0.5 * spacing;
        for (int k = kMin; k <= kMax; k++) {
            final int track = k;
            List<Double> pos = new ArrayList<>();
            for (WeatherSystem s : systems) {
                if (s.kind == WeatherSystem.Kind.LOW && s.track == track && s.x >= start - spacing
                        && s.x <= end + 1.5 * spacing) {
                    pos.add(s.x);
                }
            }
            pos.sort(Double::compare);
            if (pos.isEmpty()) {
                spawnLow(track, start, time, seconds, season, seasonal, coverage);
                pos.add(start);
            }
            // Upstream end: a new low one spacing west of the westernmost, once there is room.
            while (pos.get(0) - spacing >= start && systems.size() < MAX_SYSTEMS) {
                double at = pos.get(0) - spacing;
                spawnLow(track, at, time, seconds, season, seasonal, coverage);
                pos.add(0, at);
            }
            // Gaps between lows, and the downstream end in fresh ground.
            for (int j = 0; j < pos.size() && systems.size() < MAX_SYSTEMS; j++) {
                double cur = pos.get(j);
                boolean last = j == pos.size() - 1;
                double gap = last ? Double.POSITIVE_INFINITY : pos.get(j + 1) - cur;
                double at = cur + spacing;
                if ((last && at <= end) || (!last && gap > GAP * spacing)) {
                    spawnLow(track, at, time, seconds, season, seasonal, coverage);
                    pos.add(j + 1, at);
                }
            }
            for (int j = 0; j < pos.size() && systems.size() < MAX_SYSTEMS; j++) {
                double mid = j == 0 ? pos.get(0) - spacing / 2 : (pos.get(j - 1) + pos.get(j)) / 2;
                if (mid < start || mid > end) {
                    continue;
                }
                maybeSpawnHigh(track, mid, time, seconds, season, seasonal, spacing, coverage);
            }
        }
    }

    private long dayHash(int track, double x, long time, long salt) {
        double cell = jet.params().spacing() / 4;
        return SimMath.hash(seed, salt, track, (long) Math.floor(x / cell), Math.floorDiv(time, 24000L));
    }

    /** How long after a forecast's start a newborn system's make-up is rolled fully independently, ticks (3 days). */
    static final long REROLL_TICKS = 72_000;

    /**
     * Trait number {@code k} of a system born at {@code time} with live hash {@code h}, 0-1. The live run uses the
     * live roll. A forecast blends toward its own independent roll by how long after the forecast's start the system
     * forms (Claude's call, Bright 2026-10-09: storms forming within hours are mostly right, ones forming three days
     * out are a fresh guess), so it is fairly sure of storms forming soon and unsure of far-off ones.
     */
    private double trait(long h, int k, long time) {
        double live = SimMath.unit(h, k);
        if (traitSalt == 0) {
            return live;
        }
        double w = forecastStart == Long.MIN_VALUE ? 1
                : SimMath.smooth((double) (time - forecastStart) / REROLL_TICKS);
        if (w <= 0) {
            return live;
        }
        return live + w * (SimMath.unit(SimMath.mix(h ^ traitSalt), k) - live);
    }

    private void spawnLow(int track, double x, long time, double seconds, double season, SimParams.Seasonal seasonal,
                          Coverage coverage) {
        long h = dayHash(track, x, time, 0x10EL);
        double ts = jet.params().trackSpacing();
        double z = jet.trackZ(track, x, seconds, season) + (trait(h, 1, time) - 0.5) * 0.16 * ts;
        long lifetime = (long) ((3 + 4 * Math.pow(trait(h, 2, time), 1.3)) * 24000);
        double peak = (14 + 20 * trait(h, 3, time)) * seasonal.depth();
        double radius = 3000 + 3000 * trait(h, 4, time);
        if (crowded(WeatherSystem.Kind.HIGH, x, z, radius) || sameKindNear(WeatherSystem.Kind.LOW, x, z, radius)) {
            return;
        }
        long age = coverage.covered(x, z) ? 0 : (long) ((0.1 + 0.5 * trait(h, 5, time)) * lifetime);
        systems.add(new WeatherSystem(nextId++, WeatherSystem.Kind.LOW, x, z, track, jet.hemisphere(track), age,
                lifetime, peak, radius, false));
    }

    private void maybeSpawnHigh(int track, double x, long time, double seconds, double season,
                                SimParams.Seasonal seasonal, double spacing, Coverage coverage) {
        long h = dayHash(track, x, time, 0x41E5L);
        int hem = jet.hemisphere(track);
        double ts = jet.params().trackSpacing();
        // Usually on the warm side of the track (+z for northern-style); in winter sometimes a cold, polar high.
        boolean cold = season < 0 && trait(h, 1, time) < 0.3 * -season;
        double z = jet.trackZ(track, x, seconds, season) + (cold ? -hem : hem) * (0.08 + 0.06 * trait(h, 2, time)) * ts;
        for (WeatherSystem s : systems) {
            if (s.kind == WeatherSystem.Kind.HIGH && Math.hypot(s.x - x, s.z - z) < 0.45 * spacing) {
                return;
            }
        }
        if (crowded(WeatherSystem.Kind.LOW, x, z, 0)) {
            return;
        }
        boolean blocking = trait(h, 3, time) < seasonal.blockChance();
        long lifetime = (long) ((3 + 5 * trait(h, 4, time)) * 24000 * (blocking ? 1.8 : 1));
        double peak = (6 + 12 * trait(h, 5, time)) * (1 + 0.2 * Math.abs(season)) * (blocking ? 1.4 : 1);
        double radius = (5000 + 4000 * trait(h, 6, time)) * (blocking ? 1.2 : 1);
        if (sameKindNear(WeatherSystem.Kind.HIGH, x, z, radius)) {
            return;
        }
        long age = coverage.covered(x, z) ? 0 : (long) ((0.2 + 0.4 * trait(h, 7, time)) * lifetime);
        systems.add(new WeatherSystem(nextId++, WeatherSystem.Kind.HIGH, x, z, track, hem, age, lifetime, peak, radius,
                blocking));
    }

    /**
     * Whether a system of kind {@code other} is too close to (x, z) for a new one of the opposite kind: within
     * {@link #CLEARANCE} of the low's radius ({@code lowRadius} when the new one is the low).
     */
    boolean crowded(WeatherSystem.Kind other, double x, double z, double lowRadius) {
        for (WeatherSystem s : systems) {
            if (s.kind != other) {
                continue;
            }
            double r = other == WeatherSystem.Kind.LOW ? s.radius() : lowRadius;
            if (Math.hypot(s.x - x, s.z - z) < CLEARANCE * r) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a system of the same kind {@code kind} sits within {@link #CLEARANCE} of the larger of its radius and
     * the new one's {@code radius} (2026-10-06: two highs overlapped in a flight test).
     */
    boolean sameKindNear(WeatherSystem.Kind kind, double x, double z, double radius) {
        for (WeatherSystem s : systems) {
            if (s.kind == kind && Math.hypot(s.x - x, s.z - z) < CLEARANCE * Math.max(s.radius(), radius)) {
                return true;
            }
        }
        return false;
    }
}
