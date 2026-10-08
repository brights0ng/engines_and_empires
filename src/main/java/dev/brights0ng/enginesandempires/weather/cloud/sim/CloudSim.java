package dev.brights0ng.enginesandempires.weather.cloud.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudDrift;
import dev.brights0ng.enginesandempires.weather.cloud.CloudLife;
import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;

/**
 * The clouds around the players, and the spawner that keeps them matching the atmosphere (phase 4a of
 * {@code claude/weather-backbone-plan.md}). Pure: the atmosphere comes in through {@link Env}. The server runs a
 * {@link #pass} every couple of seconds.
 *
 * <h2>Each pass</h2>
 * <ol>
 *   <li><b>Move:</b> every cloud's velocity is set from the wind at its height (low clouds mostly the wind aloft with
 *       some of the surface wind, middle clouds the wind aloft, high clouds a little faster), carried at the field's
 *       {@value AtmosphereField#ADVECTION} blocks a second per m/s, so clouds move with the air the field moves.</li>
 *   <li><b>Live:</b> a layer cloud whose conditions still hold where it now is has its end pushed on; one that has
 *       drifted out of them starts dissolving (over its type's fade time). A heap cloud keeps its own lifespan, but a
 *       mature one in air that can build bigger clouds may grow into the next type up.</li>
 *   <li><b>Layer clouds</b> are kept on a lattice of slots ({@link Settings#layerSpacing} apart, out to
 *       {@link Settings#layerRadius}): for each deck (low, middle, high) the slot asks the diagnostics which type
 *       belongs there; if none of that deck is near the slot, one may form, the more likely the more cover is wanted
 *       (partial cover gives a broken deck). Inside a sheet the cloud drifting in from upwind fills each slot, so new
 *       clouds only form along the sheet's upwind edge, and old ones dissolve along its downwind edge.</li>
 *   <li><b>Heap clouds</b> are kept per field cell (512 blocks, out to {@link Settings#heapRadius}): new ones form
 *       while the cell has less heap cloud than the diagnostics want, a few per pass, at random spots in the cell.</li>
 *   <li><b>Warm start:</b> an area the spawner hasn't looked at recently (a player joining or travelling fast) gets its
 *       clouds all at once, already at random points in their lives, so the sky is there instead of forming in front
 *       of the player.</li>
 *   <li><b>Far away:</b> clouds that drift past every player's range dissolve and are dropped.</li>
 * </ol>
 */
public final class CloudSim {

    /** What the clouds need from the world. */
    public interface Env {
        /** The air over (x, z) (read at the field cell there). */
        CloudDiagnostics.Air air(double x, double z);

        /** The wind over (x, z): {surfaceX, surfaceZ, aloftX, aloftZ}, m/s. */
        double[] wind(double x, double z);

        /**
         * The wind clouds ride over (x, z): as {@link #wind}, without the short gusts (Bright, 2026-10-07: clouds
         * ignore them; ships keep them). Default: the wind as it is.
         */
        default double[] steadyWind(double x, double z) {
            return wind(x, z);
        }

        CloudDiagnostics.Systems systems();

        /** Minecraft day time (ticks; 0 = 6:00). */
        long dayTime();

        /** -1 midwinter to +1 midsummer. */
        double season();

        /**
         * Rain takes {@code mm} of water out of the air over (x, z) (phase 4b); returns how much it actually took.
         * Default: nothing.
         */
        default double drain(double x, double z, double mm) {
            return 0;
        }
    }

    /**
     * The spawner's numbers.
     *
     * @param layerSpacing   blocks between layer slots
     * @param layerRadius    how far from a player layer clouds are kept, blocks
     * @param heapRadius     how far from a player heap clouds are kept, blocks
     * @param maxPerPlayer   the most clouds per player in the world (spawning stops past it)
     * @param spawnCover     the least cover a layer type needs before one forms
     * @param keepCover      the least cover a layer cloud needs to keep going (lower: no flicker at the edges)
     */
    public record Settings(double layerSpacing, double layerRadius, double heapRadius, int maxPerPlayer,
                           double spawnCover, double keepCover, double heapDensity) {
        /** heapDensity 0.5: Bright, 2026-10-06, "way too many clouds", cumulus count halved. */
        public static final Settings DEFAULT = new Settings(1536, 4608, 3072, 320, 0.15, 0.08, 0.5);

        public Settings(double layerSpacing, double layerRadius, double heapRadius, int maxPerPlayer,
                        double spawnCover, double keepCover) {
            this(layerSpacing, layerRadius, heapRadius, maxPerPlayer, spawnCover, keepCover, 0.5);
        }
    }

    /** A player's position. */
    public record Anchor(double x, double z) {
    }

    /** What one pass did, for debug output. */
    public record Report(int clouds, int spawnedLayer, int spawnedHeap, int dissolved, int evolved, int removed,
                         int diagnoses, long nanos, int raining, double drainedMm) {
    }

    static final int CELL = AtmosphereField.CELL;
    /** Index cell size for finding clouds near a spot, blocks. */
    static final int INDEX = 1024;
    /** An area not looked at for this long gets a warm start, ticks. */
    static final long WARM_AFTER = 1200;
    /** How long a layer cloud is kept going past each time its conditions are confirmed, ticks. */
    static final long HOLD = 3L * CloudLife.MINUTE;
    /** Clouds report their position to clients at least this often, ticks. */
    static final long HEARTBEAT = 1200;
    /** Velocity changes smaller than this aren't worth telling clients about, blocks per tick. */
    static final double VELOCITY_EPSILON = 0.002;

    private final List<SimCloud> clouds = new ArrayList<>();
    /** Spawner cells (512 blocks) and when they were last looked at. */
    private final Map<Long, Long> seen = new HashMap<>();
    /** Diagnoses of field cells this pass. */
    private final Map<Long, CloudDiagnostics.Need> needs = new HashMap<>();
    private Map<Long, List<SimCloud>> index = new HashMap<>();
    private int diagnoses;
    /** Game time of the last pass (for how much rain fell since), or MIN_VALUE before the first. */
    private long lastPass = Long.MIN_VALUE;
    /** The clouds that formed or grew in the last pass (for the audit). */
    private final List<SimCloud> changedThisPass = new ArrayList<>();

    /** The clouds that formed (or grew into a new type) in the last pass. */
    public List<SimCloud> changedThisPass() {
        return changedThisPass;
    }

    public List<SimCloud> clouds() {
        return clouds;
    }

    public void add(SimCloud c) {
        clouds.add(c);
    }

    /** Removes every cloud; the next pass starts the sky afresh (warm). */
    public void clear() {
        clouds.clear();
        seen.clear();
    }

    /** Whether the spawner makes clouds from the weather (debug: off leaves only debug-spawned clouds). */
    private boolean natural = true;

    public boolean natural() {
        return natural;
    }

    /**
     * Turns natural spawning on or off (debug, not saved: a restart turns it back on). Off removes every cloud the
     * weather made at once, keeping debug-spawned ones; on lets the next pass fill the sky again, already formed.
     * Returns how many clouds were removed.
     */
    public int setNatural(boolean on) {
        natural = on;
        seen.clear();
        if (on) {
            return 0;
        }
        int before = clouds.size();
        clouds.removeIf(c -> !c.manual);
        return before - clouds.size();
    }

    /** How long debug-spawned clouds last before they start to fade: a full day, so they can be studied. */
    static final long DEBUG_HOLD = 24000;

    /** The diagnosis at (x, z) this pass (per field cell). */
    public CloudDiagnostics.Need need(double x, double z, Env env) {
        int ci = Math.floorDiv((int) Math.floor(x), CELL);
        int ck = Math.floorDiv((int) Math.floor(z), CELL);
        long key = AtmosphereField.key(ci, ck);
        CloudDiagnostics.Need n = needs.get(key);
        if (n == null) {
            double cx = (ci + 0.5) * CELL;
            double cz = (ck + 0.5) * CELL;
            n = CloudDiagnostics.diagnose(cx, cz, env.air(cx, cz), env.systems(), env.dayTime(), env.season());
            needs.put(key, n);
            diagnoses++;
        }
        return n;
    }

    /** One pass at game time {@code now}. */
    public Report pass(long now, List<Anchor> anchors, Env env, Settings settings, SplittableRandom rng) {
        long started = System.nanoTime();
        needs.clear();
        diagnoses = 0;
        changedThisPass.clear();
        int dissolved = 0, evolved = 0, removed = 0;
        int raining = 0;
        double drained = 0;
        long dt = lastPass == Long.MIN_VALUE ? 0 : Math.max(0, Math.min(200, now - lastPass));
        lastPass = now;

        // ---- move and live
        Iterator<SimCloud> it = clouds.iterator();
        while (it.hasNext()) {
            SimCloud c = it.next();
            if (c.over(now)) {
                it.remove();
                removed++;
                continue;
            }
            double cx = c.xAt(now);
            double cz = c.zAt(now);
            double far = nearest(anchors, cx, cz) - (c.type.heap() ? settings.heapRadius() : settings.layerRadius());
            if (!c.manual && (far > 2048 || anchors.isEmpty())) {
                it.remove();
                removed++;
                continue;
            }
            steer(c, now, env.steadyWind(cx, cz));
            CloudDiagnostics.Need need = need(cx, cz, env);
            double strength = rain(c, need, now);
            if (strength > 0) {
                raining++;
                drained += drain(c, strength, now, dt, env);
            }
            if (c.dissolving || c.manual) {
                continue;
            }
            if (far > 512) {
                dissolve(c, now);
                dissolved++;
                continue;
            }
            if (c.type.layer()) {
                double ns = need.cover(CloudType.NIMBOSTRATUS);
                // The rain deck takes over its deck where it's wanted, and the mid levels where one is (2026-10-06).
                boolean displaced = c.type != CloudType.NIMBOSTRATUS
                        && ((c.type.deck == CloudType.NIMBOSTRATUS.deck && ns > settings.spawnCover())
                        || (c.type.deck == CloudType.Deck.MID && ns > 0.5 && near(cx, cz,
                        0.75 * settings.layerSpacing(), o -> o.type == CloudType.NIMBOSTRATUS && !o.dissolving, now)));
                boolean wanted = need.cover(c.type) > settings.keepCover() && !displaced;
                if (!wanted) {
                    dissolve(c, now);
                    dissolved++;
                } else if (c.end - now < c.span().death() + CloudLife.MINUTE) {
                    c.end = now + c.span().death() + HOLD;
                    c.version++;
                }
            } else if (evolve(c, need, now, rng)) {
                c.cause = "grew (" + need.heapSource() + ")";
                changedThisPass.add(c);
                evolved++;
            }
        }

        // ---- spawn
        index = index(now);
        int cap = settings.maxPerPlayer() * Math.max(1, anchors.size());
        int spawnedLayer = 0, spawnedHeap = 0;
        Map<Long, Boolean> warm = new HashMap<>();
        // Natural spawning can be paused for debugging (samples on their own); debug clouds still live and move.
        List<Anchor> spawnAround = natural ? anchors : List.of();
        for (Anchor a : spawnAround) {
            double s = settings.layerSpacing();
            int r = (int) Math.ceil(settings.layerRadius() / s);
            int si = (int) Math.floor(a.x() / s);
            int sk = (int) Math.floor(a.z() / s);
            for (int dk = -r; dk <= r; dk++) {
                for (int di = -r; di <= r; di++) {
                    double cx = (si + di + 0.5) * s;
                    double cz = (sk + dk + 0.5) * s;
                    if (Math.hypot(cx - a.x(), cz - a.z()) > settings.layerRadius() || clouds.size() >= cap) {
                        continue;
                    }
                    spawnedLayer += spawnLayer(cx, cz, isWarm(cx, cz, now, warm), now, env, settings, rng);
                }
            }
        }
        for (Anchor a : spawnAround) {
            int r = (int) Math.ceil(settings.heapRadius() / CELL);
            int ci = Math.floorDiv((int) Math.floor(a.x()), CELL);
            int ck = Math.floorDiv((int) Math.floor(a.z()), CELL);
            // Heap clouds may fill at most 70% of the budget, so a front's decks always have room.
            int heapCap = (int) (0.7 * cap);
            int heaps = heapCount();
            for (int dk = -r; dk <= r; dk++) {
                for (int di = -r; di <= r; di++) {
                    double cx = (ci + di + 0.5) * CELL;
                    double cz = (ck + dk + 0.5) * CELL;
                    if (Math.hypot(cx - a.x(), cz - a.z()) > settings.heapRadius() || clouds.size() >= cap
                            || heaps >= heapCap) {
                        continue;
                    }
                    int n = spawnHeap(cx, cz, isWarm(cx, cz, now, warm), now, env, settings, rng);
                    spawnedHeap += n;
                    heaps += n;
                }
            }
        }
        // Mark everything looked at this pass (after spawning, so a whole new area warm-starts together).
        for (Long key : warm.keySet()) {
            seen.put(key, now);
        }
        if (seen.size() > 20_000) {
            seen.values().removeIf(t -> now - t > WARM_AFTER * 4);
        }
        return new Report(clouds.size(), spawnedLayer, spawnedHeap, dissolved, evolved, removed, diagnoses,
                System.nanoTime() - started, raining, drained);
    }

    // ---- rain -----------------------------------------------------------------------------------------------------

    /**
     * Sets cloud {@code c}'s rain from the air ({@link CloudRain}); tells clients when it changed noticeably. Returns
     * the strength reaching the ground now (0 if none: dry, not yet formed, or all virga).
     */
    static double rain(SimCloud c, CloudDiagnostics.Need need, long now) {
        if (c.forcedRain >= 0) {
            return c.type.rain.peak() * c.forcedRain * c.phase(now).precipitation();
        }
        double precip = CloudRain.precipitation(c, need);
        double peak = c.type.rain.peak();
        double rhNow = 1 - need.lclMetres() / 2500;
        float bottom = CloudRain.rainBottom(c.baseY, peak * precip, rhNow);
        float lightning = (float) CloudRain.lightning(c, precip);
        boolean bottomChanged = Float.isInfinite(bottom) != Float.isInfinite(c.rainBottom)
                || (!Float.isInfinite(bottom) && Math.abs(bottom - c.rainBottom) > 16);
        if (Math.abs(precip - c.precipitation) > CloudRain.RESEND || (precip == 0) != (c.precipitation == 0)
                || bottomChanged) {
            c.precipitation = (float) precip;
            c.lightning = lightning;
            c.rainBottom = bottom;
            c.version++;
        }
        if (!Float.isInfinite(c.rainBottom)) {
            return 0;
        }
        return peak * c.precipitation * c.phase(now).precipitation();
    }

    /** Spacing of the points rain is taken out of the air at, blocks. */
    static final double DRAIN_STEP = 256;

    /**
     * Takes the water cloud {@code c} rained over the last {@code dt} ticks out of the air under its rain cores.
     * Returns how much, mm summed over field cells.
     */
    private static double drain(SimCloud c, double strength, long now, long dt, Env env) {
        if (dt <= 0) {
            return 0;
        }
        CloudType.Rain kind = c.type.rain;
        // mm over a whole field cell if the rain covered it: rate x hours (an in-game hour is 1000 ticks).
        double mm = CloudRain.rateMmPerHour(strength) * dt / 1000.0;
        // A sheet rains evenly; a shaft is heaviest in its middle (its core averages about half its peak).
        double profile = kind.sheet() ? 0.9 : 0.5;
        double cellArea = (double) CELL * CELL;
        double growth = c.phase(now).growth();
        double total = 0;
        double x0 = c.xAt(now);
        double z0 = c.zAt(now);
        for (SimCloud.Dome d : c.domes) {
            double rc = d.radius() * kind.core() * (0.75 + 0.25 * growth);
            double cx = x0 + d.dx();
            double cz = z0 + d.dz();
            if (rc < DRAIN_STEP) {
                total += env.drain(cx, cz, mm * profile * Math.PI * rc * rc / cellArea);
                continue;
            }
            int n = (int) Math.ceil(rc / DRAIN_STEP);
            double point = DRAIN_STEP * DRAIN_STEP / cellArea;
            for (int k = -n; k <= n; k++) {
                for (int i = -n; i <= n; i++) {
                    double px = i * DRAIN_STEP;
                    double pz = k * DRAIN_STEP;
                    if (px * px + pz * pz > rc * rc) {
                        continue;
                    }
                    total += env.drain(cx + px, cz + pz, mm * profile * point);
                }
            }
        }
        return total;
    }

    // ---- motion ---------------------------------------------------------------------------------------------------

    /** The velocity of cloud type {@code t} in wind {@code w} ({surfaceX, surfaceZ, aloftX, aloftZ}), blocks per tick. */
    static double[] velocity(CloudType t, double[] w) {
        double sx, sz;
        switch (t.deck) {
            case LOW -> {
                sx = 0.35 * w[0] + 0.65 * w[2];
                sz = 0.35 * w[1] + 0.65 * w[3];
            }
            case MID -> {
                sx = w[2];
                sz = w[3];
            }
            default -> {
                sx = 1.2 * w[2];
                sz = 1.2 * w[3];
            }
        }
        double k = AtmosphereField.ADVECTION / 20;
        return new double[]{sx * k, sz * k};
    }

    private static void steer(SimCloud c, long now, double[] wind) {
        steer(c, now, wind, false);
    }

    /**
     * Points cloud {@code c} at the wind's velocity: it eases into it over {@link CloudDrift#EASE_TICKS} (a new cloud,
     * {@code fresh}, starts at it). The cloud's motion is only changed when clients are told too (a change worth
     * sending, or the heartbeat): changing it on the server alone let the two drift apart until the next update made
     * the cloud jump (2026-10-07).
     */
    static void steer(SimCloud c, long now, double[] wind, boolean fresh) {
        double[] v = velocity(c.type, wind);
        if (fresh) {
            c.advance(now);
            c.vx = v[0];
            c.vz = v[1];
            c.tvx = v[0];
            c.tvz = v[1];
            c.version++;
            c.lastSent = now;
            return;
        }
        boolean changed = Math.abs(v[0] - c.tvx) > VELOCITY_EPSILON || Math.abs(v[1] - c.tvz) > VELOCITY_EPSILON
                || now - c.lastSent > HEARTBEAT;
        if (!changed) {
            return;
        }
        c.advance(now);
        c.tvx = v[0];
        c.tvz = v[1];
        c.version++;
        c.lastSent = now;
    }

    // ---- life -----------------------------------------------------------------------------------------------------

    /** Starts {@code c} dissolving: it fades over its type's death from now (sooner if already ending). */
    static void dissolve(SimCloud c, long now) {
        c.dissolving = true;
        long end = now + c.span().death() + c.span().linger();
        if (end < c.end) {
            c.end = end;
        }
        c.version++;
    }

    /** A mature heap cloud in air that builds bigger clouds may grow into the next type up. */
    private static boolean evolve(SimCloud c, CloudDiagnostics.Need need, long now, SplittableRandom rng) {
        CloudType next = c.type.grownInto();
        if (next == null || need.heapType() == null || need.heapType().ordinal() < next.ordinal()) {
            return false;
        }
        CloudLife.Phase p = c.phase(now);
        // About one chance in 30 per pass (a minute on average): a cumulus grows on over part of its mature life.
        // Fair-weather cumulus never become congestus (Bright, 2026-10-07: a single one overhead blocked out most of
        // the sky): only a weather system grows one.
        double chance = next.ordinal() >= CloudType.CUMULUS_CONGESTUS.ordinal() && airMass(need.heapSource())
                ? AIRMASS_CONGESTUS_GROWTH : 0.033;
        if (p.growth() < 1 || p.decay() > 0 || rng.nextDouble() > chance) {
            return false;
        }
        CloudType from = c.type;
        long age = now - c.birth;
        c.type = next;
        c.end = c.birth + CloudLife.evolvedLifetime(c.span(), age, c.end - c.birth);
        double baseMetres = (c.baseY - CloudScale.GROUND_Y) / CloudScale.SCALE;
        c.domes = SimCloud.grown(c.domes, from, next);
        c.thickness = SimCloud.heapThickness(CloudScale.thickness(next, baseMetres, rng.nextDouble() * rng.nextDouble()),
                SimCloud.sizeOf(next, c.domes));
        c.version++;
        return true;
    }

    // ---- spawning -------------------------------------------------------------------------------------------------

    private boolean isWarm(double x, double z, long now, Map<Long, Boolean> marked) {
        long key = AtmosphereField.key(Math.floorDiv((int) Math.floor(x), CELL), Math.floorDiv((int) Math.floor(z), CELL));
        Boolean w = marked.get(key);
        if (w == null) {
            Long last = seen.get(key);
            w = last == null || now - last > WARM_AFTER;
            marked.put(key, w);
        }
        return w;
    }

    private int spawnLayer(double cx, double cz, boolean warm, long now, Env env, Settings settings,
                           SplittableRandom rng) {
        int spawned = 0;
        double s = settings.layerSpacing();
        boolean rainDeckHere = near(cx, cz, 0.75 * s, c -> c.type == CloudType.NIMBOSTRATUS && !c.dissolving, now);
        for (CloudType.Deck deck : CloudType.Deck.values()) {
            // Place first, then diagnose where the cloud will actually be (2026-10-06: diagnosed at the slot centre,
            // clouds placed near a front's edge were unwanted where they landed).
            double x = cx + (rng.nextDouble() - 0.5) * 0.4 * s;
            double z = cz + (rng.nextDouble() - 0.5) * 0.4 * s;
            CloudDiagnostics.Need need = need(x, z, env);
            boolean rainDeck = need.cover(CloudType.NIMBOSTRATUS) > settings.spawnCover();
            // The rain deck wins its deck whenever it's wanted (2026-10-06: stratocumulus outranked it at a low's
            // centre and nothing rained).
            CloudType t = rainDeck && deck == CloudType.NIMBOSTRATUS.deck ? CloudType.NIMBOSTRATUS
                    : need.layerIn(deck, settings.spawnCover());
            // A nimbostratus fills the mid levels too; skip them only where one actually is.
            if (t == null || (deck == CloudType.Deck.MID && rainDeckHere)) {
                continue;
            }
            if (near(cx, cz, 0.75 * s, c -> c.type.layer() && c.type.deck == deck && !c.dissolving, now)) {
                continue;
            }
            double cover = need.cover(t);
            if (rng.nextDouble() > (warm ? cover : 0.5 * cover)) {
                continue;
            }
            double hint = need.baseHint()[t.ordinal()];
            double metres = t.baseMin + (t.baseMax - t.baseMin) * SimMathLocal.clamp01(hint + (rng.nextDouble() - 0.5) * 0.15);
            float thickness = (float) CloudScale.thickness(t, metres, rng.nextDouble() * rng.nextDouble());
            UUID id = new UUID(rng.nextLong(), rng.nextLong());
            CloudLife.Span span = CloudLife.span(t, id);
            long birth = warm ? now - span.birth() - (long) (rng.nextDouble() * 2 * CloudLife.MINUTE) : now;
            long end = Math.max(birth + span.birth() + span.death() + HOLD, now + span.death() + HOLD);
            float coverage = (float) (t.look.coverage() * (0.65 + 0.35 * Math.min(1, cover)));
            SimCloud c = new SimCloud(id, t, x, z, 0, 0, now, birth, end, (float) CloudScale.baseY(metres), thickness,
                    coverage, 0, 0, SimCloud.domesFor(t, 0.6 * s, rng), 0, false);
            c.cause = need.sourceOf(t) + (warm ? ", warm start" : "");
            changedThisPass.add(c);
            steer(c, now, env.steadyWind(x, z), true);
            add(c);
            indexAdd(c, now);
            spawned++;
            if (t == CloudType.NIMBOSTRATUS) {
                rainDeckHere = true;
            }
        }
        return spawned;
    }

    private int spawnHeap(double cx, double cz, boolean warm, long now, Env env, Settings settings,
                          SplittableRandom rng) {
        CloudDiagnostics.Need need = need(cx, cz, env);
        CloudType top = need.heapType();
        // The diagnosed cover is how much sky heap clouds would fill; the density scales how many the game shows.
        double cover = need.heapCover() * settings.heapDensity();
        if (top == null || cover < 0.01) {
            return 0;
        }
        double cellArea = (double) CELL * CELL;
        double target = cover * cellArea;
        double existing = heapAreaIn(cx, cz, now, cellArea);
        int spawned = 0;
        for (int tries = 0; tries < (warm ? 8 : 2) && existing < target; tries++) {
            CloudType t = pickHeap(top, need.heapSource(), rng);
            double r = t.countRadiusBlocks();
            double area = Math.PI * r * r;
            double p = Math.min(1, (target - existing) / area);
            if (!warm) {
                p *= 0.3;
            }
            if (rng.nextDouble() > p) {
                break;
            }
            double x = cx + (rng.nextDouble() - 0.5) * CELL;
            double z = cz + (rng.nextDouble() - 0.5) * CELL;
            double metres = Math.max(t.baseMin, Math.min(t.baseMax, need.lclMetres() * (0.95 + 0.1 * rng.nextDouble())));
            // Taller where the air is more unstable than this type needs.
            int ti = t.ordinal() - CloudType.CUMULUS_HUMILIS.ordinal();
            double lo = CloudDiagnostics.HEAP_THRESHOLDS[ti];
            double hi = ti + 1 < CloudDiagnostics.HEAP_THRESHOLDS.length ? CloudDiagnostics.HEAP_THRESHOLDS[ti + 1] : lo + 0.4;
            double tall = SimMathLocal.clamp01((need.instability() - lo) / (hi - lo));
            double size = SimCloud.heapSize(t, rng);
            float thickness = SimCloud.heapThickness(
                    CloudScale.thickness(t, metres, SimMathLocal.clamp01(0.7 * tall + 0.3 * rng.nextDouble())), size);
            UUID id = new UUID(rng.nextLong(), rng.nextLong());
            CloudLife.Span span = CloudLife.span(t, id);
            long birth = warm ? now - (long) (rng.nextDouble() * 0.7 * span.storm()) : now;
            SimCloud c = new SimCloud(id, t, x, z, 0, 0, now, birth, birth + span.total(),
                    (float) CloudScale.baseY(metres), thickness, t.look.coverage(), 0, 0,
                    SimCloud.domesFor(t, 0, rng, size), 0, false);
            c.cause = need.heapSource() + (warm ? ", warm start" : "");
            changedThisPass.add(c);
            steer(c, now, env.steadyWind(x, z), true);
            add(c);
            indexAdd(c, now);
            existing += Math.min(cellArea, area * 1.2);
            spawned++;
        }
        return spawned;
    }

    /** Spawns a mature cloud of type {@code t} at (x, z) now (debug), with its base from the air there. */
    public SimCloud spawnAt(CloudType t, double x, double z, long now, Env env, SplittableRandom rng) {
        return spawnAt(t, x, z, now, env, rng, t.heap() ? SimCloud.heapSize(t, rng) : 1);
    }

    /**
     * Whether heap clouds from {@code source} are fair-weather convection (the sun, warm water, unstable air) rather
     * than driven by a weather system (a cold front's line, a low's centre, wind up a slope).
     */
    static boolean airMass(String source) {
        return source != null && (source.startsWith(CloudDiagnostics.SUN) || source.startsWith("warm water")
                || source.startsWith("unstable air"));
    }

    /**
     * The share of fair-weather heap clouds that are congestus where the air could build them, and the chance per pass
     * that a fair-weather mediocris grows into one: none (Bright, 2026-10-07; was about 1 in 50). Fair-weather
     * convection tops out at mediocris; congestus and storms come from weather systems.
     */
    static final double AIRMASS_CONGESTUS = 0;
    static final double AIRMASS_CONGESTUS_GROWTH = 0;

    /**
     * Which heap type a new cloud is, where the air builds up to {@code top}: by a weather system, mostly the top type
     * (60%) or the one below; fair-weather convection (Bright, 2026-10-07: many small, no huge ones) mostly humilis and
     * some mediocris, however unstable the air.
     */
    static CloudType pickHeap(CloudType top, String source, SplittableRandom rng) {
        int k = top.ordinal() - CloudType.CUMULUS_HUMILIS.ordinal();
        if (k <= 0) {
            return top;
        }
        double u = rng.nextDouble();
        if (airMass(source) && top.ordinal() >= CloudType.CUMULUS_CONGESTUS.ordinal()) {
            return u < AIRMASS_CONGESTUS ? CloudType.CUMULUS_CONGESTUS
                    : u < 0.34 ? CloudType.CUMULUS_MEDIOCRIS : CloudType.CUMULUS_HUMILIS;
        }
        if (airMass(source) && top == CloudType.CUMULUS_MEDIOCRIS) {
            return u < 0.35 ? CloudType.CUMULUS_MEDIOCRIS : CloudType.CUMULUS_HUMILIS;
        }
        return u < 0.4 ? CloudType.values()[top.ordinal() - 1] : top;
    }

    /** As {@link #spawnAt(CloudType, double, double, long, Env, SplittableRandom)}, a heap cloud of size {@code size}. */
    public SimCloud spawnAt(CloudType t, double x, double z, long now, Env env, SplittableRandom rng, double size) {
        CloudDiagnostics.Need need = need(x, z, env);
        double metres = t.heap() ? Math.max(t.baseMin, Math.min(t.baseMax, need.lclMetres()))
                : t.baseMin + (t.baseMax - t.baseMin) * need.baseHint()[t.ordinal()];
        UUID id = new UUID(rng.nextLong(), rng.nextLong());
        CloudLife.Span span = CloudLife.span(t, id);
        long birth = now - span.birth();
        long end = now + span.death() + span.linger() + DEBUG_HOLD;
        double thick = CloudScale.thickness(t, metres, 0.5);
        SimCloud c = new SimCloud(id, t, x, z, 0, 0, now, birth, end, (float) CloudScale.baseY(metres),
                t.heap() ? SimCloud.heapThickness(thick, size) : (float) thick, t.look.coverage(), 0, 0,
                SimCloud.domesFor(t, 0.6 * Settings.DEFAULT.layerSpacing(), rng, size), 0, false);
        c.manual = true;
        c.cause = "debug spawn";
        steer(c, now, env.steadyWind(x, z), true);
        add(c);
        return c;
    }

    // ---- finding clouds -------------------------------------------------------------------------------------------

    private Map<Long, List<SimCloud>> index(long now) {
        Map<Long, List<SimCloud>> idx = new HashMap<>();
        for (SimCloud c : clouds) {
            put(idx, c, now);
        }
        return idx;
    }

    private void indexAdd(SimCloud c, long now) {
        put(index, c, now);
    }

    private static void put(Map<Long, List<SimCloud>> idx, SimCloud c, long now) {
        long key = AtmosphereField.key(Math.floorDiv((int) Math.floor(c.xAt(now)), INDEX),
                Math.floorDiv((int) Math.floor(c.zAt(now)), INDEX));
        idx.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
    }

    private boolean near(double x, double z, double radius, java.util.function.Predicate<SimCloud> filter, long now) {
        int r = (int) Math.ceil(radius / INDEX);
        int ix = Math.floorDiv((int) Math.floor(x), INDEX);
        int iz = Math.floorDiv((int) Math.floor(z), INDEX);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                List<SimCloud> list = index.get(AtmosphereField.key(ix + dx, iz + dz));
                if (list == null) {
                    continue;
                }
                for (SimCloud c : list) {
                    if (filter.test(c) && Math.hypot(c.xAt(now) - x, c.zAt(now) - z) < radius) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** How much of the 512-block cell centred at (cx, cz) heap clouds cover, blocks^2 (roughly). */
    private double heapAreaIn(double cx, double cz, long now, double cellArea) {
        double area = 0;
        int r = 2;
        int ix = Math.floorDiv((int) Math.floor(cx), INDEX);
        int iz = Math.floorDiv((int) Math.floor(cz), INDEX);
        double half = CELL / 2.0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                List<SimCloud> list = index.get(AtmosphereField.key(ix + dx, iz + dz));
                if (list == null) {
                    continue;
                }
                for (SimCloud c : list) {
                    if (!c.type.heap() || c.dissolving) {
                        continue;
                    }
                    // The type's typical radius, not this cloud's: sizes vary (many small, few big) while the count
                    // stays what the cover asks for (Bright, 2026-10-06: keep today's count).
                    double radius = c.type.countRadiusBlocks();
                    double x = c.xAt(now);
                    double z = c.zAt(now);
                    double d = Math.hypot(x - cx, z - cz);
                    if (d < radius - half) {
                        area += cellArea;
                    } else if (Math.abs(x - cx) <= half && Math.abs(z - cz) <= half) {
                        area += Math.min(cellArea, Math.PI * radius * radius * 1.2);
                    }
                }
            }
        }
        return Math.min(area, cellArea);
    }

    private static double nearest(List<Anchor> anchors, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (Anchor a : anchors) {
            best = Math.min(best, Math.hypot(a.x() - x, a.z() - z));
        }
        return best;
    }

    private int heapCount() {
        int n = 0;
        for (SimCloud c : clouds) {
            if (c.type.heap()) {
                n++;
            }
        }
        return n;
    }

    /** Small helpers (kept here so the class reads on its own). */
    private static final class SimMathLocal {
        static double clamp01(double v) {
            return v < 0 ? 0 : Math.min(1, v);
        }
    }
}
