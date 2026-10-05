package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.StormVariety;

/**
 * A supercell's structure, built from the whole formation instead of one dome per PA cluster. PA's clusters for a
 * supercell are lobes it scatters to fill out the storm's volume; here they only give the storm's size, place,
 * heights, drift and lifecycle. Pure Java, used by {@link CloudField} on the mesher thread.
 *
 * <p>Proportions are real ones at the pack's scale ({@code CloudScale}): the heights arrive already scaled (a base a
 * few hundred blocks up, a top 3,000-4,200 blocks up), and the parts are sized from the storm's height, so the updraft
 * is a tower, not a block.
 *
 * <h2>Parts</h2>
 * Positions are anchor-local x and z, world y. "Downwind" is the formation's drift direction; "right" is to the right
 * of it.
 * <ul>
 *   <li><b>Updraft:</b> one broad tower from the base to the anvil (radius about 0.15 x the storm's height), a little
 *       upwind of the storm's centre. It leans downwind more and more with height (wind shear grows aloft; the top
 *       is about a quarter of the height downwind of the base). It is widest at its rotating base (whose rain-free
 *       underside is flat), narrows to a waist in the middle and flares out into the anvil, with large slow bulges
 *       along it and helical striations from the storm's rotation, which turn slowly.</li>
 *   <li><b>Overshooting top:</b> a dome above the anvil over the top of the updraft.</li>
 *   <li><b>Anvil:</b> flat on top at the storm's top, centred over the top of the updraft. It reaches far downwind
 *       (about 2 x the storm's height) and widens as it goes. It is thickest near the updraft and thins downwind; its
 *       thickness also varies with slow large-scale noise. Downwind its edge is sharp: a bevelled knife edge. Upwind
 *       it overhangs the updraft by about a third of the storm's height as the <b>backshear</b>: broader, a little
 *       thinner, with a rounded edge.</li>
 *   <li><b>Mammatus:</b> pouches hanging from the anvil's underside, downwind of the updraft.</li>
 *   <li><b>Forward flank:</b> the rain area, from just downwind of the updraft for about half the anvil's length, a
 *       little to its left, under the anvil: a broad, dark mass, narrowing toward its far end, from a base ragged
 *       upward (never below the main base) up into the anvil.</li>
 *   <li><b>Shelf cloud:</b> on the forward flank's leading end (its gust front), an arc wider than the forward flank
 *       there: a flat-bottomed wedge at the base whose top steps up in tiers from a thin nose at the front into the
 *       forward flank.</li>
 *   <li><b>Flanking line:</b> a row of cumulus towers trailing from the updraft to the right-rear of the drift (as in
 *       Northern Hemisphere supercells), stepping down in height away from it, on a shared flat-based ridge with
 *       fairly crisp sides. Each tower's height breathes slowly.</li>
 *   <li><b>Wall cloud:</b> a lowered patch under the updraft's base, on its downwind side.</li>
 * </ul>
 *
 * <p>Every part's size can be scaled from the client config ({@link CloudTuning}), for experimenting.
 *
 * <h2>Lifecycle</h2>
 * From the clusters' average growth, decay and anvil decay ({@code CloudLife}): the updraft grows first, the anvil
 * spreads once it is up, and the forward flank and then the shelf come with the mature storm. When the storm decays,
 * the updraft and flanking line collapse first, then the rest of the body; the anvil is left on its own (an orphan
 * anvil) and only thins away with the anvil decay, over the storm's linger. {@link CloudField} adds the erosion that
 * frays and dissolves each part.
 */
final class Supercell {

    /** Per-column cache layout (see {@link #column}). */
    static final int SX = 0, SZ = 1, OVER = 2, ANVIL_H = 3, ANVIL_BOTTOM = 4, ANVIL_TOP = 5, WALL = 6, RIDGE = 7,
            FF_H = 8, FF_BOTTOM = 9, FF_TOP = 10, SHELF_H = 11, SHELF_BOTTOM = 12, SHELF_TOP = 13, WX = 14, WZ = 15,
            TOWERS = 16;

    static final int TOWER_COUNT = 5;
    /** The most the anvil's top bulges up, as a fraction of the storm's height (its underside's mammatus go further). */
    static final double TOP_BULGE = 0.03;
    /** Turrets (bubbles) per flanking tower, for its cauliflower top. */
    static final int TURRETS = 14;

    final double rf;
    final double dx;
    final double dz;
    /** Right of the drift: (-dz, dx). */
    final double rx;
    final double rz;

    // updraft
    final double ux;
    final double uz;
    final double ru;
    final double yb;
    final double ya;
    final double h;
    final double lean;
    /** How far the updraft's top is displaced downwind from its base, blocks (the lean curves: more with height). */
    final double leanTop;
    final double striation;
    final double bulges;
    final double baseFlare;
    final double waist;
    final double updraftLife;
    final double updraftTop;
    // overshooting top
    final double overR;
    final double overH;
    // anvil (centred over the top of the updraft)
    final double ax;
    final double az;
    final double anvilDown;
    final double anvilUp;
    final double anvilWidth;
    /** The anvil's half-width upwind of its centre, over the backshear. */
    final double anvilWidthUp;
    final double anvilSpread;
    final double anvilThick;
    final double anvilLife;
    final double mammatusScale;
    final double mammatusDrop;
    // forward flank and shelf cloud
    final double ffX;
    final double ffZ;
    final double ffAlong;
    final double ffAcross;
    final double ffLife;
    final double shelfReach;
    final double shelfHalfWidth;
    final double shelfDepth;
    final double shelfLife;
    // flanking line
    final double flankBase;
    final double flankRidgeWidth;
    final double flankLife;
    final double[] towerX = new double[TOWER_COUNT];
    final double[] towerZ = new double[TOWER_COUNT];
    final double[] towerR = new double[TOWER_COUNT];
    final double[] towerH = new double[TOWER_COUNT];
    /** Per tower and turret: angle round the tower, how far out (of the dome's radius there), height (of the dome), size
     * (of the tower's radius) and boiling phase. */
    final double[][] turretAngleCos = new double[TOWER_COUNT][TURRETS];
    final double[][] turretAngleSin = new double[TOWER_COUNT][TURRETS];
    final double[][] turretOut = new double[TOWER_COUNT][TURRETS];
    final double[][] turretHeight = new double[TOWER_COUNT][TURRETS];
    final double[][] turretSize = new double[TOWER_COUNT][TURRETS];
    final double[][] turretPhase = new double[TOWER_COUNT][TURRETS];
    /** The highest the flanking line can reach. */
    final double flankTop;
    final double ridgeX0, ridgeZ0, ridgeX1, ridgeZ1;
    // wall cloud
    final double wallX;
    final double wallZ;
    final double wallR;
    final double wallDrop;

    final int seed;
    /** This storm's look: the design defaults, the client config's multipliers, and its own variety. */
    final Look look;
    /** Shelf tier height multiplier. */
    final double shelfTall;

    /** Tower heights at {@link #heightsTime}, recomputed when the time changes (one build uses one time). */
    private final double[] towerHeightNow = new double[TOWER_COUNT];
    private double heightsTime = Double.NaN;

    /** Whether a formation is drawn as a supercell. */
    static boolean isSupercell(List<CloudShape> visible) {
        for (CloudShape c : visible) {
            if (c.typeId() != null && c.typeId().contains("supercell")) {
                return true;
            }
        }
        return false;
    }

    /**
     * How one storm looks, as multipliers on the base proportions in the constructor: the design defaults (Bright's
     * tuning, 2026-10-03), times the client config's multipliers ({@link CloudTuning}), times this storm's variety.
     *
     * <h2>Variety</h2>
     * Real supercells vary a lot, mostly with their environment ({@link StormVariety}): where they sit between low and
     * high precipitation, how sheared the wind is, how unstable the air is. Each element's multiplier is
     * {@code exp(amount * (its response to those three) + amount * jitter)}, with a small independent jitter
     * (standard deviation 0.12) per element. In log space the variation is centred on zero, so the median storm is
     * the default one, and related parts move together the way they do in real storms:
     * <ul>
     *   <li>Precipitation: HP storms have a broader tower and base, a much bigger forward flank and shelf, a bigger
     *       wall cloud, a less pinched waist, fainter striations and a darker look; LP storms the reverse.</li>
     *   <li>Shear: more lean, a longer anvil and flanking line, the flanking line swept further back.</li>
     *   <li>Instability: a wider, bulgier tower, a taller overshoot, a thicker, wider anvil reaching further upwind,
     *       taller flanking towers, more mammatus.</li>
     * </ul>
     * {@code amount} is {@link CloudTuning#variety} (1 by default, 0 for every storm the same).
     */
    static final class Look {
        // Design defaults (Bright's tuning; 1 = the base proportions).
        static final double UPDRAFT_WIDTH = 3.0, LEAN = 2.0, BASE_FLARE = 1.2, WAIST = 1.2, OVERSHOOT = 2.0,
                ANVIL_LENGTH = 0.5, ANVIL_THICKNESS = 2.0, BACKSHEAR = 2.0, FF_LENGTH = 0.75, FF_WIDTH = 0.5,
                SHELF_REACH = 1.4;

        double updraftWidth, lean, baseFlare, waist, bulges, striation, overshoot;
        double anvilLength, anvilWidth, anvilThickness, backshear, mammatus;
        double ffLength, ffWidth, ffOffset, shelfReach, shelfWidth, shelfHeight;
        double flankLength, flankHeight, flankAngle, wall;
        /** Multiplies the storm's darkness. */
        double darkness;
        StormVariety variety;

        static Look of(UUID regionId, double amount) {
            return of(regionId, amount, Tuning.client());
        }

        /** This storm's look with tuning multipliers {@code t} ({@link Tuning#DESIGN} for the design itself). */
        static Look of(UUID regionId, double amount, Tuning t) {
            StormVariety v = regionId == null ? StormVariety.AVERAGE : StormVariety.of(regionId);
            SplittableRandom rng = regionId == null ? new SplittableRandom(1) : StormVariety.random(regionId, 0x100C);
            double a = Math.max(0, amount);
            double p = v.precipitation(), s = v.shear(), i = v.instability();
            Look k = new Look();
            k.variety = v;
            k.updraftWidth = UPDRAFT_WIDTH * t.updraftWidth() * vary(rng, a, 0.25 * i + 0.2 * p);
            k.lean = LEAN * t.updraftLean() * vary(rng, a, 0.45 * s);
            k.baseFlare = BASE_FLARE * t.updraftBaseFlare() * vary(rng, a, 0.25 * p);
            k.waist = WAIST * t.updraftWaist() * vary(rng, a, -0.35 * p);
            k.bulges = t.updraftBulges() * vary(rng, a, 0.35 * i);
            k.striation = vary(rng, a, -0.6 * p);
            k.overshoot = OVERSHOOT * t.overshootHeight() * vary(rng, a, 0.5 * i);
            k.anvilLength = ANVIL_LENGTH * t.anvilLength() * vary(rng, a, 0.4 * s);
            k.anvilWidth = t.anvilWidth() * vary(rng, a, 0.2 * s + 0.2 * i);
            k.anvilThickness = ANVIL_THICKNESS * t.anvilThickness() * vary(rng, a, 0.25 * i + 0.15 * p);
            k.backshear = BACKSHEAR * t.backshearReach() * vary(rng, a, 0.45 * i + 0.15 * s);
            k.mammatus = t.mammatusSize() * vary(rng, a, 0.35 * i + 0.15 * p);
            k.ffLength = FF_LENGTH * t.forwardFlankLength() * vary(rng, a, 0.5 * p);
            k.ffWidth = FF_WIDTH * t.forwardFlankWidth() * vary(rng, a, 0.55 * p);
            k.ffOffset = vary(rng, a, 0) * vary(rng, a, 0);
            k.shelfReach = SHELF_REACH * t.shelfReach() * vary(rng, a, 0.5 * p);
            k.shelfWidth = t.shelfWidth() * vary(rng, a, 0.35 * p);
            k.shelfHeight = t.shelfHeight() * vary(rng, a, 0.45 * p);
            k.flankLength = t.flankingLineLength() * vary(rng, a, 0.35 * s);
            k.flankHeight = t.flankingTowerHeight() * vary(rng, a, 0.3 * i);
            k.flankAngle = 25 + a * (10 * s + 6 * normal(rng));
            k.wall = t.wallCloudSize() * vary(rng, a, 0.35 * p);
            k.darkness = Math.exp(a * (0.25 * p + 0.05 * normal(rng)));
            return k;
        }

        /** exp(amount * (response + jitter)), jitter normal with standard deviation 0.12. */
        private static double vary(SplittableRandom rng, double amount, double response) {
            return Math.exp(amount * (response + 0.12 * normal(rng)));
        }

        private static double normal(SplittableRandom rng) {
            double u1 = Math.max(1e-12, rng.nextDouble());
            return Math.max(-2.5, Math.min(2.5, Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * rng.nextDouble())));
        }
    }

    /**
     * The client config's per-element multipliers ({@link CloudTuning}), as a value, so a storm can also be built at
     * the design itself ({@link #DESIGN}): the rain query does, so where it rains is the same for everyone.
     */
    record Tuning(double updraftWidth, double updraftLean, double updraftBaseFlare, double updraftWaist,
                  double updraftBulges, double overshootHeight, double anvilLength, double anvilWidth,
                  double anvilThickness, double backshearReach, double mammatusSize, double forwardFlankLength,
                  double forwardFlankWidth, double shelfReach, double shelfWidth, double shelfHeight,
                  double flankingLineLength, double flankingTowerHeight, double wallCloudSize) {

        static final Tuning DESIGN = new Tuning(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);

        static Tuning client() {
            return new Tuning(CloudTuning.updraftWidth, CloudTuning.updraftLean, CloudTuning.updraftBaseFlare,
                    CloudTuning.updraftWaist, CloudTuning.updraftBulges, CloudTuning.overshootHeight,
                    CloudTuning.anvilLength, CloudTuning.anvilWidth, CloudTuning.anvilThickness,
                    CloudTuning.backshearReach, CloudTuning.mammatusSize, CloudTuning.forwardFlankLength,
                    CloudTuning.forwardFlankWidth, CloudTuning.shelfReach, CloudTuning.shelfWidth, CloudTuning.shelfHeight,
                    CloudTuning.flankingLineLength, CloudTuning.flankingTowerHeight, CloudTuning.wallCloudSize);
        }
    }

    Supercell(List<CloudShape> visible, CloudShape anchor, double dx, double dz) {
        this(visible, anchor, dx, dz, Look.of(anchor.regionId(), CloudTuning.variety));
    }

    Supercell(List<CloudShape> visible, CloudShape anchor, double dx, double dz, Look look) {
        this.dx = dx;
        this.dz = dz;
        this.rx = -dz;
        this.rz = dx;
        this.seed = anchor.seed();

        // Size and place: the clusters' centroid (weighted by area) and how far they reach from it.
        double wSum = 0, cx = 0, cz = 0, growth = 0, decay = 0, anvilDecay = 0;
        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (CloudShape c : visible) {
            double w = c.radius() * (double) c.radius();
            wSum += w;
            cx += (c.cx() - anchor.cx()) * w;
            cz += (c.cz() - anchor.cz()) * w;
            growth += c.growth();
            decay += c.decay();
            anvilDecay += c.anvilDecay();
            low = Math.min(low, c.baseY());
            high = Math.max(high, c.topY());
        }
        cx /= wSum;
        cz /= wSum;
        growth /= visible.size();
        decay /= visible.size();
        anvilDecay /= visible.size();
        double reach = 0;
        for (CloudShape c : visible) {
            double ox = c.cx() - anchor.cx() - cx;
            double oz = c.cz() - anchor.cz() - cz;
            reach = Math.max(reach, Math.sqrt(ox * ox + oz * oz) + c.radius());
        }
        double size = Math.max(200, Math.min(3000, reach));

        this.yb = low;
        this.ya = Math.max(high, low + 60);
        this.h = ya - yb;
        // The updraft is sized from the height (a real one is about a third as wide as it is tall); PA's footprint
        // only nudges it.
        this.look = look;
        Look k = look;
        this.ru = Math.max(40, 0.15 * h * (0.85 + 0.3 * clamp01(size / 1500)) * k.updraftWidth);
        this.rf = ru * 1.3;
        this.lean = 0.25 * k.lean;
        this.leanTop = lean * h;
        this.striation = Math.min(0.2, 0.07 * k.striation);
        this.bulges = 0.12 * k.bulges;
        this.baseFlare = 0.35 * k.baseFlare;
        this.waist = Math.min(0.6, 0.2 * k.waist);

        this.updraftLife = clamp01(growth * 1.25) * Math.pow(1 - clamp01(decay), 1.5);
        // An orphan anvil's edges draw in and it thins; CloudField's erosion finishes it.
        this.anvilLife = clamp01((growth - 0.3) / 0.45) * (1 - 0.5 * smooth(anvilDecay));
        this.flankLife = clamp01(growth * 1.4) * (1 - clamp01(decay));
        this.ffLife = clamp01((growth - 0.35) / 0.4) * (1 - clamp01((decay - 0.5) / 0.5));
        this.shelfLife = clamp01((growth - 0.5) / 0.4) * (1 - clamp01((decay - 0.6) / 0.4));

        // Updraft: a little upwind of the centre, so the anvil streams over the rest of the storm.
        this.ux = cx - dx * 0.18 * size;
        this.uz = cz - dz * 0.18 * size;
        this.updraftTop = yb + h * (0.25 + 0.75 * updraftLife);

        this.overR = 0.6 * ru * updraftLife;
        this.overH = 0.08 * h * updraftLife * k.overshoot;

        this.ax = ux + dx * leanTop;
        this.az = uz + dz * leanTop;
        this.anvilDown = 2.2 * h * k.anvilLength;
        // The updraft's top flares to about 1.25 ru; the backshear overhangs it by about a third of the storm's
        // height (1,000-1,500 blocks).
        // A supercell's anvil always streams mainly downwind (it needs strong shear to exist): measured from the
        // tower's base, the backshear reaches at most 70% as far upwind as the anvil does downwind.
        this.anvilUp = Math.min(1.25 * ru + 0.33 * h * k.backshear, leanTop + 0.7 * (leanTop + anvilDown));
        this.anvilWidth = 1.3 * ru * k.anvilWidth;
        this.anvilWidthUp = anvilWidth * 1.4;
        this.anvilSpread = 0.32 * k.anvilWidth;
        this.anvilThick = 0.3 * h * k.anvilThickness;
        this.mammatusScale = Math.max(18, Math.min(110, 0.16 * ru)) * k.mammatus;
        this.mammatusDrop = Math.min(0.04 * h, 1.2 * mammatusScale);

        // Forward flank: from just downwind of the updraft, about half the anvil's length, a little to the left of
        // the updraft (left = -right), narrowing toward its far end.
        this.ffAlong = 0.25 * anvilDown * k.ffLength;
        this.ffAcross = 1.4 * ru * k.ffWidth;
        this.ffX = ux + dx * (0.6 * ru + ffAlong) - rx * 0.35 * ru * k.ffOffset;
        this.ffZ = uz + dz * (0.6 * ru + ffAlong) - rz * 0.35 * ru * k.ffOffset;
        // Shelf cloud: an arc ahead of the forward flank's leading end (by about 0.8 ru), wider than it.
        this.shelfReach = ffAlong + 0.8 * ru * k.shelfReach;
        this.shelfHalfWidth = ffAcross * 1.1 * k.shelfWidth;
        this.shelfTall = k.shelfHeight;
        // The shelf runs from its leading edge all the way back into the forward flank: real shelf clouds are the
        // storm's own base, pushed forward by the outflow, never a separate cloud.
        this.shelfDepth = (shelfReach - ffAlong) + 0.9 * ru;

        // Flanking line: to the right-rear of the drift, 25 degrees off straight behind.
        double backX = -dx, backZ = -dz;
        double c25 = Math.cos(Math.toRadians(k.flankAngle)), s25 = Math.sin(Math.toRadians(k.flankAngle));
        double fx = backX * c25 + rx * s25;
        double fz = backZ * c25 + rz * s25;
        SplittableRandom rng = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL);
        for (int i = 0; i < TOWER_COUNT; i++) {
            double along = ru * (1.0 + 0.75 * i * k.flankLength + 0.1 * (rng.nextDouble() - 0.5));
            double side = ru * 0.24 * (rng.nextDouble() - 0.5);
            towerX[i] = ux + fx * along - fz * side;
            towerZ[i] = uz + fz * along + fx * side;
            towerR[i] = ru * (0.5 - 0.06 * i);
            towerH[i] = h * (0.55 - 0.09 * i) * (0.9 + 0.2 * rng.nextDouble()) * k.flankHeight;
            for (int j = 0; j < TURRETS; j++) {
                // Bubbles over the upper part of the dome, more of them near the top, a few big ones crowning it.
                double a = rng.nextDouble() * Math.PI * 2;
                turretAngleCos[i][j] = Math.cos(a);
                turretAngleSin[i][j] = Math.sin(a);
                double up = Math.sqrt(rng.nextDouble());
                turretHeight[i][j] = 0.35 + 0.6 * up;
                turretOut[i][j] = (0.55 + 0.35 * rng.nextDouble()) * (1 - 0.6 * up * up);
                turretSize[i][j] = 0.2 + 0.2 * rng.nextDouble() + 0.08 * up;
                turretPhase[i][j] = rng.nextDouble() * Math.PI * 2;
            }
        }
        double tallest = 0;
        for (int i = 0; i < TOWER_COUNT; i++) {
            tallest = Math.max(tallest, towerH[i] * 1.12 + towerR[i] * 0.5);
        }
        this.flankTop = yb + 0.015 * h + tallest + 0.02 * h;
        this.flankBase = yb + 0.015 * h;
        this.flankRidgeWidth = 0.42 * ru;
        this.ridgeX0 = ux + fx * 0.6 * ru;
        this.ridgeZ0 = uz + fz * 0.6 * ru;
        this.ridgeX1 = towerX[TOWER_COUNT - 1];
        this.ridgeZ1 = towerZ[TOWER_COUNT - 1];

        this.wallX = ux + dx * 0.2 * ru;
        this.wallZ = uz + dz * 0.2 * ru;
        this.wallR = 0.4 * ru * k.wall;
        this.wallDrop = Math.min(0.07 * h, 0.4 * wallR) * updraftLife;
    }

    /** Lowest and highest y the storm can reach (before noise). */
    double lowest() {
        return yb - Math.max(wallDrop, 0.01 * h);
    }

    double highest() {
        return ya + Math.max(overH, TOP_BULGE * h) + 0.02 * h;
    }

    // ---- per column ----------------------------------------------------------------------------------------------

    int cacheSize() {
        return TOWERS + TOWER_COUNT;
    }

    /**
     * Fills {@code c} for the column at warped (xw, zw); (x, z) unwarped, for noise that should not move with the warp.
     */
    void column(double[] c, double xw, double zw, double x, double z, double time) {
        c[SX] = xw - ux;
        c[SZ] = zw - uz;
        c[WX] = xw;
        c[WZ] = zw;
        double ox = xw - ax;
        double oz = zw - az;
        c[OVER] = Math.sqrt(ox * ox + oz * oz);

        // Anvil: wind coordinates around its centre.
        double px = xw - ax, pz = zw - az;
        double u = px * dx + pz * dz;
        double w = px * rx + pz * rz;
        double reach = u >= 0 ? anvilDown : anvilUp;
        double width = u >= 0 ? anvilWidth + anvilSpread * u : anvilWidth + (anvilWidthUp - anvilWidth)
                * Math.min(1, -u / (1.25 * ru));
        double q = Math.sqrt((u / reach) * (u / reach) + (w / width) * (w / width));
        double edgeDist = (1 - q) * Math.min(reach, width);
        double fibre = CloudNoise.gradient3(u / (rf * 0.9), w / (rf * 0.15), time / CloudField.FIBRE_PERIOD_TICKS,
                seed + 404);
        // The backshear's edge is rounded, not fibrous.
        double fibreAmp = u < 0 ? 0.02 : 0.06;
        c[ANVIL_H] = edgeDist / rf * 2.2 + fibre * fibreAmp - (1 - anvilLife) * 0.8;
        double along = clamp01(u / anvilDown);
        double thickNoise = CloudNoise.gradient3(x / (ru * 1.2), z / (ru * 1.2), time / 3000, seed + 505);
        double qc = Math.min(q, 1);
        // Thickness across the anvil: a wedge thinning to a razor edge at the outline. Upwind it turns into the
        // backshear's rounded nose, blended in gradually so the two halves meet without a step at the updraft.
        double wedge = Math.pow(1 - qc, 0.65);
        double nose = 0.75 * Math.sqrt(Math.max(0, 1 - qc * qc));
        double back = u < 0 ? smooth(-u / (0.5 * anvilUp)) : 0;
        double profile = (1 - 0.7 * along) * (wedge + (nose - wedge) * back);
        double thick = anvilThick * profile * (1 + 0.35 * thickNoise) * (0.4 + 0.6 * anvilLife);
        double drop = 0;
        if (along > 0.08 && along < 0.7 && q < 0.8) {
            double window = Math.sin(Math.PI * (along - 0.08) / 0.62) * (1 - q / 0.8);
            double pouch = CloudNoise.gradient3(x / mammatusScale, z / mammatusScale, time / 1800, seed + 606);
            if (pouch > 0) {
                drop = mammatusDrop * window * Math.pow(pouch, 1.5) * anvilLife;
            }
        }
        // The top surface bulges up a little too (much less than the underside), slowly, where the anvil is thick;
        // the razor edge stays thin.
        double lump = CloudNoise.gradient3(x / (ru * 0.6), z / (ru * 0.6), time / 2400, seed + 909);
        double bulge = TOP_BULGE * h * Math.pow(Math.max(0, lump), 1.2) * profile * anvilLife;
        c[ANVIL_TOP] = ya + 0.01 * h * thickNoise + bulge;
        c[ANVIL_BOTTOM] = c[ANVIL_TOP] - thick - drop;

        // Forward flank: elliptical footprint in wind coordinates around its centre, narrowing toward its far end.
        double fu = (xw - ffX) * dx + (zw - ffZ) * dz;
        double fw = (xw - ffX) * rx + (zw - ffZ) * rz;
        double ffT = clamp01((fu + ffAlong) / (2 * ffAlong));
        double across = ffAcross * (1 - 0.3 * ffT);
        double fq = Math.sqrt((fu / ffAlong) * (fu / ffAlong) + (fw / across) * (fw / across));
        double ragged = CloudNoise.gradient3(x / (0.3 * ru), z / (0.3 * ru), time / 1500, seed + 707);
        c[FF_H] = (1 - fq) * across / rf * 1.5 + ragged * 0.08 - (1 - ffLife) * 0.8;
        // Its base is ragged upward only: nothing hangs below the storm's base but the wall cloud.
        c[FF_BOTTOM] = yb + h * (0.01 + 0.025 * ffT) + 0.02 * h * Math.max(0, ragged);
        // Up into the anvil where there is one; elsewhere a mid-level top.
        double intoAnvil = clamp01((c[ANVIL_H] + 0.3) / 0.3);
        c[FF_TOP] = (yb + 0.4 * h) * (1 - intoAnvil) + (c[ANVIL_BOTTOM] + 0.03 * h) * intoAnvil;

        // Shelf cloud: fd is how far behind its leading edge (an arc ahead of the forward flank) this column is.
        double arc = 1 - (fw / shelfHalfWidth) * (fw / shelfHalfWidth);
        if (arc > 0 && shelfLife > 0.01) {
            double fd = shelfReach * Math.sqrt(arc) - fu;
            double side = (shelfHalfWidth - Math.abs(fw)) / rf * 3;
            c[SHELF_H] = Math.min(Math.min(fd / rf * 6 + 0.04, (shelfDepth * 1.3 - fd) / rf * 3), side);
            double t = clamp01(fd / shelfDepth);
            // Three tiers: the top steps up toward the storm.
            double tiers = t * 3;
            double step = Math.floor(tiers);
            double frac = tiers - step;
            double terrace = (step + frac * frac * (3 - 2 * frac)) / 3;
            double bottom = yb + 0.004 * h * Math.max(0, ragged);
            c[SHELF_BOTTOM] = bottom;
            c[SHELF_TOP] = bottom + h * (0.015 + 0.09 * (0.35 * t + 0.65 * terrace)) * (0.5 + 0.5 * shelfLife)
                    * shelfTall;
        } else {
            c[SHELF_H] = -10;
            c[SHELF_BOTTOM] = yb;
            c[SHELF_TOP] = yb;
        }

        double wx = xw - wallX, wz = zw - wallZ;
        c[WALL] = Math.sqrt(wx * wx + wz * wz);

        // Ridge under the flanking line: distance to its segment, narrowing away from the updraft.
        double sx = ridgeX1 - ridgeX0, sz = ridgeZ1 - ridgeZ0;
        double len2 = sx * sx + sz * sz;
        double tr = len2 > 0 ? clamp01(((xw - ridgeX0) * sx + (zw - ridgeZ0) * sz) / len2) : 0;
        double ex = xw - (ridgeX0 + sx * tr), ez = zw - (ridgeZ0 + sz * tr);
        c[RIDGE] = (flankRidgeWidth * (1 - 0.45 * tr) * (0.4 + 0.6 * flankLife) - Math.sqrt(ex * ex + ez * ez)) / rf;

        for (int i = 0; i < TOWER_COUNT; i++) {
            double tx = xw - towerX[i], tz = zw - towerZ[i];
            c[TOWERS + i] = Math.sqrt(tx * tx + tz * tz);
        }
        if (time != heightsTime) {
            heightsTime = time;
            for (int i = 0; i < TOWER_COUNT; i++) {
                towerHeightNow[i] = towerH[i] * (1 + 0.12 * CloudNoise.gradient3(i * 3.7, time / 2400, 0.5, seed + 71))
                        * (0.3 + 0.7 * flankLife);
            }
        }
    }

    /**
     * Where in a column the storm can be, in warped height: writes the lowest and highest y to {@code out}. Returns
     * false if nowhere. {@code slack} is how far below zero a part's horizontal field may be and still count.
     */
    boolean range(double[] c, double slack, double pad, double[] out) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        double du = Math.sqrt(c[SX] * c[SX] + c[SZ] * c[SZ]) - leanTop;
        if ((ru * maxWidth() - du) / rf >= -slack || (overR - c[OVER]) / rf >= -slack) {
            lo = Math.min(lo, yb);
            hi = Math.max(hi, ya + overH);
        }
        if (c[ANVIL_H] >= -slack) {
            lo = Math.min(lo, c[ANVIL_BOTTOM]);
            hi = Math.max(hi, c[ANVIL_TOP]);
        }
        if (c[FF_H] >= -slack) {
            lo = Math.min(lo, c[FF_BOTTOM]);
            hi = Math.max(hi, c[FF_TOP]);
        }
        if (c[SHELF_H] >= -slack) {
            lo = Math.min(lo, c[SHELF_BOTTOM]);
            hi = Math.max(hi, c[SHELF_TOP]);
        }
        boolean flank = c[RIDGE] >= -slack;
        for (int i = 0; i < TOWER_COUNT && !flank; i++) {
            flank = (towerR[i] - c[TOWERS + i]) / rf >= -slack;
        }
        if (flank) {
            lo = Math.min(lo, flankBase);
            hi = Math.max(hi, flankTop);
        }
        if ((wallR - c[WALL]) / rf >= -slack) {
            lo = Math.min(lo, yb - wallDrop);
            hi = Math.max(hi, yb + 0.04 * h);
        }
        if (lo > hi) {
            return false;
        }
        out[0] = lo - pad;
        out[1] = hi + pad;
        return true;
    }

    /** Horizontal bounds (min x, max x, min z, max z), padded by {@code pad}. */
    double[] bounds(double pad) {
        double[] b = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
        include(b, ux + dx * leanTop * 0.5, uz + dz * leanTop * 0.5, ru * maxWidth() + leanTop * 0.5 + pad);
        double wDown = anvilWidth + anvilSpread * anvilDown;
        double[][] corners = {{-anvilUp, -anvilWidthUp}, {-anvilUp, anvilWidthUp}, {anvilDown, -wDown},
                {anvilDown, wDown}};
        for (double[] uw : corners) {
            include(b, ax + uw[0] * dx + uw[1] * rx, az + uw[0] * dz + uw[1] * rz, pad);
        }
        double[][] ff = {{-ffAlong, -shelfHalfWidth}, {-ffAlong, shelfHalfWidth}, {shelfReach, -shelfHalfWidth},
                {shelfReach, shelfHalfWidth}};
        for (double[] uw : ff) {
            include(b, ffX + uw[0] * dx + uw[1] * rx, ffZ + uw[0] * dz + uw[1] * rz, pad);
        }
        for (int i = 0; i < TOWER_COUNT; i++) {
            include(b, towerX[i], towerZ[i], towerR[i] * 1.5 + pad);
        }
        include(b, wallX, wallZ, wallR + pad);
        return b;
    }

    private static void include(double[] b, double x, double z, double r) {
        b[0] = Math.min(b[0], x - r);
        b[1] = Math.max(b[1], x + r);
        b[2] = Math.min(b[2], z - r);
        b[3] = Math.max(b[3], z + r);
    }

    /**
     * Whether the box (warped coordinates: anchor-local x and z, world y) overlaps the parts with small details: the
     * flanking towers and their bubbles, and the shelf cloud's tiers.
     */
    boolean hasFineDetail(double x0, double x1, double y0, double y1, double z0, double z1) {
        if (y0 <= flankTop && y1 >= flankBase - 0.02 * h) {
            for (int i = 0; i < TOWER_COUNT; i++) {
                double r = towerR[i] * 1.6;
                if (x0 <= towerX[i] + r && x1 >= towerX[i] - r && z0 <= towerZ[i] + r && z1 >= towerZ[i] - r) {
                    return true;
                }
            }
        }
        if (shelfLife > 0.01 && y0 <= yb + 0.15 * h * shelfTall && y1 >= yb - 0.02 * h) {
            double[] b = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
            double back = shelfReach - shelfDepth * 1.3;
            double[][] corners = {{back, -shelfHalfWidth}, {back, shelfHalfWidth}, {shelfReach, -shelfHalfWidth},
                    {shelfReach, shelfHalfWidth}};
            for (double[] uw : corners) {
                include(b, ffX + uw[0] * dx + uw[1] * rx, ffZ + uw[0] * dz + uw[1] * rz, 0);
            }
            return x0 <= b[1] && x1 >= b[0] && z0 <= b[3] && z1 >= b[2];
        }
        return false;
    }

    // ---- density -------------------------------------------------------------------------------------------------

    /**
     * The updraft's radius at height fraction {@code v} (0 base, 1 top), in updraft radii, before its bulges and
     * striations: wide at the rotating base, narrowing to a waist in the middle, flaring out into the anvil.
     */
    double widthAt(double v) {
        double base = 1 + baseFlare;
        double mid = 1 - waist;
        if (v < 0.2) {
            return base + (1.0 - base) * smooth(v / 0.2);
        }
        if (v < 0.55) {
            return 1.0 + (mid - 1.0) * smooth((v - 0.2) / 0.35);
        }
        return mid + (1.25 - mid) * smooth((v - 0.55) / 0.45);
    }

    /** The widest the updraft can get, in updraft radii, bulges and striations included. */
    double maxWidth() {
        return Math.max(1 + baseFlare, 1.25) * (1 + Math.abs(bulges)) * (1 + striation) + 0.05;
    }

    /** How far downwind the updraft's axis is at height fraction {@code v}: more and more with height. */
    double leanAt(double v) {
        return leanTop * Math.pow(v, 1.6);
    }

    /** A cumulus dome's radius at height fraction {@code vd} of its height, in its radius: flat-based, rounded top. */
    private static double domeProfile(double vd) {
        if (vd < 0.2) {
            return 0.85 + 0.75 * Math.max(vd, -1);
        }
        double q = (vd - 0.2) / 0.8;
        return Math.sqrt(Math.max(0, 1 - q * q));
    }

    /**
     * The storm's lower body: updraft, flanking line, ridge, wall cloud, forward flank and shelf cloud, at warped
     * height {@code y}.
     */
    double body(double[] c, double y, double time) {
        double rise = y - yb;
        double f = -10;
        if (updraftLife > 0.01) {
            double v = clamp01(rise / h);
            double lr = leanAt(v);
            double ox = c[SX] - lr * dx;
            double oz = c[SZ] - lr * dz;
            double et = Math.sqrt(ox * ox + oz * oz);
            double r = ru * widthAt(v) * (0.55 + 0.45 * updraftLife);
            if (et < r * 1.5) {
                double angle = Math.atan2(oz, ox);
                // Rotation: a two-start helix of bands up the sides, slowly turning.
                double phase = 2 * Math.PI * rise / (0.08 * h) + 2 * angle + time * (2 * Math.PI / 900);
                // Large, slow bulges, different at each height and side.
                double bulge = CloudNoise.gradient3(Math.cos(angle) * 1.3, rise / (0.15 * h),
                        Math.sin(angle) * 1.3 + time / 6000, seed + 808);
                r *= (1 + striation * Math.sin(phase)) * (1 + bulges * bulge);
            }
            f = Math.min((r - et) / rf, Math.min((updraftTop - y) / rf * 2.5, rise / rf * 3));
        }
        double flank = -10;
        double fRise = y - flankBase;
        for (int i = 0; i < TOWER_COUNT; i++) {
            double domeH = towerHeightNow[i];
            if (domeH < 4 || c[TOWERS + i] > towerR[i] * 1.5) {
                continue;
            }
            double grown = 0.5 + 0.5 * flankLife;
            double radius = towerR[i] * grown;
            // The tower's body: a dome, a little slimmer than the tower, under its bubbles. Capped at its top: above
            // a dome its radius is zero, which alone would leave the field flat (not falling) over the axis.
            double ft = Math.min((domeProfile(fRise / domeH) * radius * 0.8 - c[TOWERS + i]) / rf,
                    Math.min(fRise / rf * 3, (domeH - fRise) / rf * 3));
            // Cauliflower top: bubbles hugging the dome's upper surface, joined with a tight blend so the creases
            // between them stay. Each one boils slowly up and down.
            for (int j = 0; j < TURRETS; j++) {
                double eta = turretHeight[i][j] + 0.04 * Math.sin(time * (2 * Math.PI / 2400) + turretPhase[i][j]);
                double out = domeProfile(eta) * radius * 0.8 * turretOut[i][j];
                double tx = c[WX] - (towerX[i] + turretAngleCos[i][j] * out);
                double tz = c[WZ] - (towerZ[i] + turretAngleSin[i][j] * out);
                double ty = y - (flankBase + eta * domeH);
                double r = turretSize[i][j] * radius;
                double d2 = tx * tx + tz * tz + ty * ty;
                if (d2 < r * r * 2.25) {
                    ft = CloudField.smoothMax(ft, (r - Math.sqrt(d2)) / rf, 0.03);
                }
            }
            flank = CloudField.smoothMax(flank, Math.min(ft, fRise / rf * 3), 0.1);
        }
        double ridge = Math.min(c[RIDGE], Math.min(fRise / rf * 3, (flankBase + 0.06 * h - y) / rf * 3));
        double wall = -10;
        if (wallDrop > 1) {
            wall = Math.min((wallR - c[WALL]) / rf, Math.min((y - (yb - wallDrop)) / rf * 3, (yb + 0.04 * h - y) / rf * 3));
        }
        double ff = -10;
        if (ffLife > 0.01 && c[FF_H] > -1) {
            ff = Math.min(c[FF_H], Math.min((y - c[FF_BOTTOM]) / rf * 3, (c[FF_TOP] - y) / rf * 3));
        }
        double shelf = -10;
        if (c[SHELF_H] > -1) {
            shelf = Math.min(c[SHELF_H], Math.min((y - c[SHELF_BOTTOM]) / rf * 6, (c[SHELF_TOP] - y) / rf * 6));
        }
        double core = CloudField.smoothMax(CloudField.smoothMax(f, flank, 0.12), CloudField.smoothMax(ridge, wall, 0.08),
                0.1);
        return CloudField.smoothMax(core, CloudField.smoothMax(ff, shelf, 0.08), 0.12);
    }

    /** The anvil and overshooting top, at warped height {@code y}. */
    double top(double[] c, double y) {
        double anvil = Math.min(c[ANVIL_H], Math.min((c[ANVIL_TOP] - y) / rf * 3, (y - c[ANVIL_BOTTOM]) / rf * 3));
        if (overH < 1) {
            return anvil;
        }
        double vy = (y - ya) * overR / overH;
        double over = (overR - Math.sqrt(c[OVER] * c[OVER] + vy * vy)) / rf;
        return CloudField.smoothMax(anvil, over, 0.15);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private static double smooth(double t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }
}
