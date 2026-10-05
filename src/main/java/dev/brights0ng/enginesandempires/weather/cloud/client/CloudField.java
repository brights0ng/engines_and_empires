package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;

/**
 * A cloud formation as a 3D density field: positive inside the cloud, negative outside, in units of the formation's
 * largest radius ({@link #rf}). Pure Java, so it runs on the mesher thread and in tests.
 *
 * <h2>Envelope</h2>
 * <ul>
 *   <li><b>Members.</b> Each PA cluster is a dome: a mostly flat base, widest a little above it, rounding off toward
 *       its top. Tower strength adds a narrower column up to the full top, leaning downwind. Each cluster varies from
 *       its seed: height +/-15%, a stretched and turned footprint, its tower off-centre.</li>
 *   <li><b>Smooth union.</b> Members are blended with a smooth maximum, so where they meet the surface rounds into a
 *       fillet instead of a crease.</li>
 *   <li><b>Anvil.</b> One per formation, if any member has anvil strength: flat on top at the formation's top,
 *       centred over the tallest tower, reaching a little upwind and far downwind (the direction the formation drifts),
 *       widening and thinning downwind. It is blended onto the towers with a wide smooth maximum, so the neck flares
 *       into it. The tallest tower gets an overshooting top above the anvil.</li>
 *   <li><b>Supercells</b> are built differently: as one structured storm ({@link Supercell}) from the whole
 *       formation, instead of a dome per cluster.</li>
 * </ul>
 *
 * <h2>Churn (noise)</h2>
 * <ul>
 *   <li><b>Warp</b>, large and slow: the whole envelope is pushed around horizontally by noise about 70% of the
 *       formation's size, changing over {@link #WARP_PERIOD_TICKS}. Lobes swell and recede.</li>
 *   <li><b>Billows</b>, mid-sized and 3D: two layers drifting in different directions, so features morph rather than
 *       slide. They rise over time ({@link #riseRate}), faster in towered clouds: turrets boil upward.</li>
 *   <li><b>Detail</b>, small: only at voxel sizes of 4 or less, where it can be seen.</li>
 *   <li><b>Fibres</b> on the anvil: noise stretched along the wind, for ragged, streaky edges.</li>
 * </ul>
 *
 * <h2>Birth and death</h2>
 * From the formation's lifecycle ({@code CloudLife}: growth, decay, anvil decay, averaged over its clusters), as
 * {@linkplain #erosion erosion}: an amount taken off the density everywhere, so thin parts go first and the noise decides
 * where the cloud breaks up. No extra passes, so it costs nothing beyond slightly busier sections.
 * <ul>
 *   <li><b>Birth:</b> erosion strongest at the top and the churn stronger, so the flat base appears first as scattered
 *       fragments, which join, and the cloud then builds upward (its tops also rise, {@code CloudScale}) and a little
 *       outward.</li>
 *   <li><b>Death:</b> erosion growing (slowly, then faster) and stronger toward the top, and the churn stronger: the
 *       edges fray, holes open, the tops soften and sink first, and the cloud evaporates from the outside in.</li>
 *   <li><b>Anvils</b> have their own erosion, from the anvil decay: a storm's body dies first and its anvil thins away
 *       afterwards (the linger).</li>
 * </ul>
 * Time is game time in ticks, so every player sees the same clouds.
 */
final class CloudField {

    static final double WARP_PERIOD_TICKS = 6000;
    static final double BILLOW_PERIOD_TICKS = 1200;
    static final double FIBRE_PERIOD_TICKS = 2400;

    /** Noise strengths, in units of {@link #rf} (times {@link CloudTuning#noiseStrength}). */
    static final double BILLOW_AMP = 0.13;
    static final double FIBRE_AMP = 0.22;
    static final double WARP_FRACTION = 0.28;

    /**
     * Erosion at the end of a death, before the height weighting (in units of {@link #rf}). It grows as
     * {@code decay^3.4}: most of a cloud's volume is near its surface, so even a little erosion takes a lot away, and
     * a gentle start keeps a dying cloud around for most of its death.
     */
    static final double DEATH_EROSION = 0.6;
    static final double DEATH_POWER = 3.4;
    /** Extra erosion over the last 15% of a death, so the last wisps are gone by its end instead of popping. */
    static final double DEATH_FINISH = 0.7;
    /**
     * Erosion at the very start of a birth, shrinking as {@code (1 - growth)^2}. Strong enough that a cloud starts from
     * nothing (Bright, 2026-10-04: the first build of a big cloud was already a large chunk of it): the first fragments
     * appear a little into the birth and the cloud catches up by its end.
     */
    static final double BIRTH_EROSION = 0.75;
    /** Erosion of a fully thinned anvil, growing as {@code anvilDecay^3}: a supercell's and an ordinary one's. */
    static final double STORM_ANVIL_EROSION = 1.0;
    static final double ANVIL_EROSION = 0.35;
    /**
     * A supercell's anvil while the storm forms: erosion this strong (enough to hide it entirely) until the tower is
     * well along, shrinking as {@code (1 - (growth - 0.35) / 0.55)^2}, so the anvil spreads only once the tower has
     * built, as in ordinary storms. (It used to be there from the start: about half the storm in its first build.)
     */
    static final double STORM_ANVIL_BIRTH = 1.2;
    /** A cloud's water content when it starts forming, as a share of its full content (it builds over the birth). */
    static final double FORMING_WATER = 0.25;

    /**
     * How much the billows and detail can raise a point's density, at most. The warp and the fibres are already in a
     * point's envelope value, so a point whose envelope is below minus this can't be inside: its noise is skipped.
     */
    final double noiseReach;

    final List<Member> members;
    /** {@link #members} as an array, for the hot loops. */
    private final Member[] memberArray;
    final double rf;
    final double driftX;
    final double driftZ;
    final double baseY;
    final double topY;
    final double maxEdge;
    final float baseDarkness;
    final float stormDarkness;
    /**
     * How much liquid water the cloud holds, g/m^3 (real-world values by type: fair cumulus about 0.4, storms 1-2),
     * for its shading ({@link CloudVoxelizer#brightness}).
     */
    final double water;
    final double riseRate;
    final double warpLength;
    final double billowScale;
    /** Ticks for the billows to change completely: longer for bigger ones. */
    final double billowPeriod;
    final double billowAmp;
    final int seed;
    final Anvil anvil;
    /** The storm structure, for a supercell; null otherwise (then the members and anvil are used). */
    final Supercell storm;
    /** The body's erosion at its base and how much more there is at its top (see {@link #erosion}). */
    final double erosionBase;
    final double erosionTop;
    /** The anvil's erosion. */
    final double anvilErosion;

    /** One PA cluster, with its own variation, positioned relative to the formation's anchor. */
    static final class Member {
        double cx;
        double cz;
        double r;
        double yb;
        double h;
        double aspect;
        double cos;
        double sin;
        double tower;
        double towerOffX;
        double towerOffZ;
        double lean;
        double coverage;
        double overshoot;

        double top() {
            return yb + h;
        }
    }

    /** The formation's anvil. */
    static final class Anvil {
        double ux;
        double uz;
        double top;
        double thick;
        double downwind;
        double upwind;
        double width;
        double life;
    }

    private CloudField(List<Member> members, double rf, double driftX, double driftZ, double baseY, double topY,
                       double maxEdge, float baseDarkness, float stormDarkness, double water, double riseRate, int seed,
                       Anvil anvil, Supercell storm, double growth, double decay, double anvilDecay,
                       double anvilErosionScale) {
        this.members = members;
        this.memberArray = members.toArray(new Member[0]);
        this.rf = rf;
        this.driftX = driftX;
        this.driftZ = driftZ;
        this.baseY = baseY;
        this.topY = topY;
        this.maxEdge = maxEdge;
        this.baseDarkness = baseDarkness;
        this.stormDarkness = stormDarkness;
        this.water = water;
        this.riseRate = riseRate;
        this.warpLength = WARP_FRACTION * rf;
        // The churn is sized from the cloud (about a third of its largest radius), and slower when bigger.
        this.billowScale = Math.max(rf * 0.35, 18) * Math.max(0.05, CloudTuning.noiseScale);
        this.billowPeriod = BILLOW_PERIOD_TICKS * Math.sqrt(Math.max(1, billowScale / 40));
        // Forming and dying clouds churn more: the noise breaks them into fragments and frays their edges.
        double unformed = 1 - clamp01(growth);
        double dying = clamp01(decay);
        this.billowAmp = BILLOW_AMP * CloudTuning.noiseStrength * (1 + 0.9 * unformed + 1.0 * dying);
        this.seed = seed;
        this.anvil = anvil;
        this.storm = storm;
        this.noiseReach = billowAmp * (0.8 + 0.5 * maxEdge) + 0.01;
        double finish = clamp01((dying - 0.85) / 0.15);
        double death = DEATH_EROSION * Math.pow(dying, DEATH_POWER) + DEATH_FINISH * finish * finish;
        double birth = BIRTH_EROSION * unformed * unformed;
        // Death: 0.75 at the base to 1.35 at the top; birth: 0.4 at the base to 1.6 at the top.
        this.erosionBase = 0.75 * death + 0.4 * birth;
        this.erosionTop = 0.6 * death + 1.2 * birth;
        double ad = clamp01(anvilDecay);
        double anvilUnformed = storm == null ? 0 : 1 - clamp01((growth - 0.35) / 0.55);
        this.anvilErosion = Math.max(anvilErosionScale * ad * ad * ad,
                STORM_ANVIL_BIRTH * anvilUnformed * anvilUnformed);
    }

    /** Builds the field of formation {@code f}, or null if nothing in it is visible. */
    static CloudField of(CloudFormation f) {
        CloudShape anchor = f.anchor();
        List<CloudShape> visible = new ArrayList<>();
        for (CloudShape m : f.members()) {
            if (m.visible()) {
                visible.add(m);
            }
        }
        if (visible.isEmpty()) {
            return null;
        }

        double vx = 0, vz = 0, rf = 1, maxTower = 0, maxAnvil = 0, maxEdge = 0, water = 0;
        double growth = 0, decay = 0, anvilDecay = 0;
        float baseDark = 0, stormDark = 0;
        for (CloudShape c : visible) {
            vx += c.vx();
            vz += c.vz();
            growth += c.growth();
            decay += c.decay();
            anvilDecay += c.anvilDecay();
            rf = Math.max(rf, c.radius() * CloudFormation.spread(c));
            maxTower = Math.max(maxTower, clamp01(c.towerStrength()));
            maxAnvil = Math.max(maxAnvil, clamp01(c.anvilStrength()));
            maxEdge = Math.max(maxEdge, clamp01(c.edgeSoftness()));
            baseDark = Math.max(baseDark, (float) clamp01(c.baseDarkness()));
            stormDark = Math.max(stormDark, (float) clamp01(c.stormDarkness()));
            // Denser clouds, and ones PA has raining, hold more water.
            water = Math.max(water, typeWater(c.typeId()) * (0.6 + 0.8 * clamp01(c.density() * c.densityMultiplier()))
                    * (1 + 0.5 * clamp01(c.precipitation())));
        }
        growth /= visible.size();
        decay /= visible.size();
        anvilDecay /= visible.size();
        // A developing cloud hasn't condensed its full water yet: its water content builds as it forms, so a young
        // tower is lighter than the storm it becomes, on top of being thinner.
        water *= FORMING_WATER + (1 - FORMING_WATER) * clamp01(growth);
        double vl = Math.sqrt(vx * vx + vz * vz);
        double dx = vl > 1e-6 ? vx / vl : 1;
        double dz = vl > 1e-6 ? vz / vl : 0;

        if (Supercell.isSupercell(visible)) {
            Supercell s = new Supercell(visible, anchor, dx, dz);
            // High-precipitation storms look darker, low-precipitation ones lighter.
            baseDark = (float) Math.min(1, baseDark * s.look.darkness);
            stormDark = (float) Math.min(1, stormDark * s.look.darkness);
            return new CloudField(List.of(), s.rf, dx, dz, s.lowest(), s.highest(), maxEdge, baseDark, stormDark,
                    water * s.look.darkness, 0.05, anchor.seed(), null, s, growth, decay, anvilDecay,
                    STORM_ANVIL_EROSION);
        }

        List<Member> members = new ArrayList<>(visible.size());
        Member updraft = null;
        double baseY = Double.MAX_VALUE;
        double topY = -Double.MAX_VALUE;
        // Real widths (CloudScale): PA's radii and the clusters' spacing, stretched (CloudFormation.offsetX).
        for (CloudShape c : visible) {
            SplittableRandom rng = new SplittableRandom(c.seed() * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
            Member m = new Member();
            m.cx = f.offsetX(c);
            m.cz = f.offsetZ(c);
            // A forming cloud spreads a little as it grows; a dying one keeps its footprint (it erodes instead).
            m.r = Math.max(c.radius() * CloudFormation.spread(c) * (0.75 + 0.25 * clamp01(c.growth())), 4);
            m.yb = c.baseY();
            double hScale = 0.85 + 0.3 * rng.nextDouble();
            // A dying cloud's tops sink as they evaporate.
            double density = clamp01(c.density() * c.densityMultiplier());
            m.h = Math.max(c.topY() - c.baseY(), 4) * (0.6 + 0.4 * density) * hScale * (1 - 0.3 * clamp01(c.decay()));
            m.aspect = 0.8 + 0.45 * rng.nextDouble();
            double theta = rng.nextDouble() * Math.PI * 2;
            m.cos = Math.cos(theta);
            m.sin = Math.sin(theta);
            m.tower = clamp01(c.towerStrength());
            double offAngle = rng.nextDouble() * Math.PI * 2;
            double off = m.r * 0.18 * rng.nextDouble();
            m.towerOffX = Math.cos(offAngle) * off + dx * m.r * 0.08;
            m.towerOffZ = Math.sin(offAngle) * off + dz * m.r * 0.08;
            m.lean = 0.15 * m.tower;
            // Coverage without the lifecycle: birth and death are the erosion's job.
            m.coverage = clamp01(c.coverage() * c.coverageMultiplier());
            members.add(m);
            baseY = Math.min(baseY, m.yb);
            topY = Math.max(topY, m.top());
            if (updraft == null || m.tower * m.h > updraft.tower * updraft.h) {
                updraft = m;
            }
        }

        Anvil anvil = null;
        if (maxAnvil > 0.05 && updraft != null) {
            anvil = new Anvil();
            updraft.overshoot = updraft.h * 0.07 * maxAnvil;
            anvil.ux = updraft.cx + updraft.towerOffX + updraft.lean * dx * updraft.h;
            anvil.uz = updraft.cz + updraft.towerOffZ + updraft.lean * dz * updraft.h;
            anvil.top = topY;
            anvil.thick = (topY - baseY) * (0.10 + 0.10 * maxAnvil);
            anvil.downwind = updraft.r * (1.3 + 1.8 * maxAnvil);
            anvil.upwind = updraft.r * (0.45 + 0.35 * maxAnvil);
            anvil.width = updraft.r * (0.5 + 0.45 * maxAnvil);
            // The anvil spreads once the tower has grown; it thins away through its own erosion.
            anvil.life = clamp01((growth - 0.3) / 0.5);
            topY += updraft.overshoot;
        }

        double rise = 0.01 + 0.04 * maxTower;
        return new CloudField(members, rf, dx, dz, baseY, topY, maxEdge, baseDark, stormDark, water, rise,
                anchor.seed(), anvil, null, growth, decay, anvilDecay, ANVIL_EROSION);
    }

    /**
     * A cloud type's liquid water content, g/m^3: typical real-world values (fair-weather cumulus 0.2-0.5, towering
     * cumulus up to about 1, cumulonimbus 1-2, supercells more; layer clouds 0.1-0.5; cirrus is thin ice).
     */
    static double typeWater(String typeId) {
        if (typeId == null) {
            return 0.4;
        }
        String id = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        return switch (id) {
            case "vapor_cluster" -> 0.1;
            case "cumulus_humilis" -> 0.25;
            case "cumulus_mediocris" -> 0.4;
            case "cumulus_congestus" -> 0.7;
            case "cumulonimbus_calvus" -> 1.2;
            case "cumulonimbus_capillatus" -> 1.5;
            case "supercell" -> 2.0;
            case "stratus_nebulosus" -> 0.25;
            case "stratocumulus" -> 0.3;
            case "nimbostratus" -> 0.5;
            case "cirrus" -> 0.02;
            default -> 0.4;
        };
    }

    // ---- cloud above a point, for shading ------------------------------------------------------------------------

    /**
     * How much cloud lies above each height in one column: the envelope (no billows) sampled every {@code step} blocks
     * from the top of the column's range down, and the depth of cloud above each sample summed. Gaps (clear air between
     * a storm's anvil and a lower tower, say) don't count. Anchor-local x and z, world y.
     */
    static final class Profile {
        final double top;
        final double step;
        final float[] depth;

        Profile(double top, double step, float[] depth) {
            this.top = top;
            this.step = step;
            this.depth = depth;
        }

        /** Blocks of cloud above height {@code y}. */
        double depthAbove(double y) {
            if (depth.length == 0 || y >= top) {
                return 0;
            }
            double f = (top - y) / step;
            int i = (int) f;
            if (i >= depth.length - 1) {
                return depth[depth.length - 1];
            }
            return depth[i] + (depth[i + 1] - depth[i]) * (f - i);
        }
    }

    static final Profile EMPTY_PROFILE = new Profile(0, 1, new float[0]);

    /** The cloud above every height of column (x, z) at {@code time}. */
    Profile profile(double x, double z, double time, double step, Column c) {
        column(x, z, time, c);
        double[] range = new double[2];
        if (!columnRange(c, range)) {
            return EMPTY_PROFILE;
        }
        int n = (int) Math.ceil((range[1] - range[0]) / step) + 1;
        float[] depth = new float[n];
        boolean was = envelope(c, range[1]) > 0;
        for (int i = 1; i < n; i++) {
            boolean in = envelope(c, range[1] - i * step) > 0;
            // Trapezoid: half a step for each end inside.
            depth[i] = depth[i - 1] + (float) (step * ((was ? 0.5 : 0) + (in ? 0.5 : 0)));
            was = in;
        }
        return new Profile(range[1], step, depth);
    }

    // ---- bounds --------------------------------------------------------------------------------------------------

    /** Horizontal and vertical bounds (anchor-local x, z; world y) that contain the whole cloud, noise included. */
    double[] bounds() {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double pad = warpLength + noiseReach * rf + rf * 0.05;
        if (storm != null) {
            double[] b = storm.bounds(pad);
            return new double[]{b[0], b[1], baseY - rf * 0.06, topY + rf * 0.08, b[2], b[3]};
        }
        for (Member m : members) {
            double reach = m.r * 1.25 + Math.hypot(m.towerOffX, m.towerOffZ) + m.lean * m.h + pad;
            minX = Math.min(minX, m.cx - reach);
            maxX = Math.max(maxX, m.cx + reach);
            minZ = Math.min(minZ, m.cz - reach);
            maxZ = Math.max(maxZ, m.cz + reach);
        }
        if (anvil != null) {
            // The anvil's outline in wind coordinates (u downwind, w across), widened by the fibres, then turned.
            double fibre = 1 + FIBRE_AMP * 2.4;
            double up = anvil.upwind * fibre + pad;
            double down = anvil.downwind * fibre + pad;
            double wUp = anvil.width * fibre + pad;
            double wDown = (anvil.width + 0.3 * anvil.downwind) * fibre + pad;
            double[][] corners = {{-up, -wUp}, {-up, wUp}, {down, -wDown}, {down, wDown}};
            for (double[] uw : corners) {
                double x = anvil.ux + uw[0] * driftX - uw[1] * driftZ;
                double z = anvil.uz + uw[0] * driftZ + uw[1] * driftX;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        }
        return new double[]{minX, maxX, baseY - rf * 0.06, topY + rf * 0.08, minZ, maxZ};
    }

    // ---- per column ----------------------------------------------------------------------------------------------

    /**
     * Everything about a point that only depends on its x and z, worked out once per column: the warp (it is 2D plus
     * time, so the same all the way up), the anvil fibres, and each member's and the anvil's horizontal distances at
     * the warped position. Reused between columns to avoid allocation.
     */
    final class Column {
        double wx;
        double wy;
        double wz;
        double fibre;
        /** Per member: elliptical horizontal distance to its centre (blocks). */
        final double[] e = new double[members.size()];
        /** Per member: squared distance to its tower's base axis, and that offset dotted with the drift. */
        final double[] towerSq = new double[members.size()];
        final double[] towerDot = new double[members.size()];
        /** The members that can matter anywhere in this column (the rest are too far away horizontally). */
        final int[] active = new int[members.size()];
        int activeCount;
        /** The anvil's horizontal part (fibres included) and its thickness here. */
        double anvilHorizontal;
        double anvilThick;
        /** The supercell's per-column values (see {@link Supercell#column}), when {@link #storm} is set. */
        final double[] storm = new double[CloudField.this.storm == null ? 0 : CloudField.this.storm.cacheSize()];
        /** The time this column was filled for. */
        double time;
    }

    Column newColumn() {
        return new Column();
    }

    /** Fills {@code c} for the column at anchor-local (x, z) at {@code time}. */
    void column(double x, double z, double time, Column c) {
        double ws = rf * 0.7;
        double tw = time / WARP_PERIOD_TICKS;
        c.wx = CloudNoise.gradient3(x / ws, z / ws, tw, seed + 101) * warpLength;
        c.wy = CloudNoise.gradient3(x / ws, z / ws, tw, seed + 202) * rf * 0.04;
        c.wz = CloudNoise.gradient3(x / ws, z / ws, tw, seed + 303) * warpLength;
        double xw = x + c.wx;
        double zw = z + c.wz;
        c.time = time;
        if (storm != null) {
            c.activeCount = 0;
            storm.column(c.storm, xw, zw, x, z, time);
            return;
        }
        c.activeCount = 0;
        double cutoff = -(noiseReach + 0.04 * memberArray.length + 0.2);
        for (int k = 0; k < memberArray.length; k++) {
            Member m = memberArray[k];
            double dx = xw - m.cx;
            double dz = zw - m.cz;
            double rx = dx * m.cos + dz * m.sin;
            double rz = -dx * m.sin + dz * m.cos;
            c.e[k] = Math.sqrt((rx / m.aspect) * (rx / m.aspect) + (rz * m.aspect) * (rz * m.aspect));
            double tx = dx - m.towerOffX;
            double tz = dz - m.towerOffZ;
            c.towerSq[k] = tx * tx + tz * tz;
            c.towerDot[k] = tx * driftX + tz * driftZ;
            // The most this member can reach here, at any height: its widest dome, or its tower leant over.
            double f = (m.r - c.e[k]) / rf;
            if (m.tower > 0.05) {
                double et = Math.sqrt(c.towerSq[k]) - m.lean * m.h * 1.2;
                f = Math.max(f, (m.r * (0.28 + 0.22 * m.tower) - et) / rf);
            }
            if (f - (1 - m.coverage) * 0.3 >= cutoff) {
                c.active[c.activeCount++] = k;
            }
        }
        if (anvil != null) {
            Anvil a = anvil;
            double fu = x - a.ux;
            double fz = z - a.uz;
            c.fibre = CloudNoise.fbm3((fu * driftX + fz * driftZ) / (rf * 0.9), (-fu * driftZ + fz * driftX) / (rf * 0.12),
                    time / FIBRE_PERIOD_TICKS, seed + 404, 2);
            double ux = xw - a.ux;
            double uz = zw - a.uz;
            double u = ux * driftX + uz * driftZ;
            double w = -ux * driftZ + uz * driftX;
            double reach = u > 0 ? a.downwind : a.upwind;
            double width = a.width + 0.3 * Math.max(u, 0);
            double q = Math.sqrt((u / reach) * (u / reach) + (w / width) * (w / width));
            double along = Math.max(0, Math.min(1, u / a.downwind));
            c.anvilThick = a.thick * (1 - 0.6 * along) * (1 - 0.35 * Math.min(q, 1));
            c.anvilHorizontal = (1 - q) * 0.5 - (1 - a.life) * 0.8 + c.fibre * FIBRE_AMP * Math.min(q + 0.2, 1.2);
        }
    }

    /**
     * Where in column {@code c} the cloud can be: {@code out[0]} and {@code out[1]} are the lowest and highest
     * (unwarped) y worth evaluating. Returns false if nowhere: the envelope can't come within {@link #noiseReach} of
     * the surface anywhere in the column.
     */
    boolean columnRange(Column c, double[] out) {
        double slack = 0.04 * members.size() + 0.07;
        double pad = rf * 0.14 + 8;
        if (storm != null) {
            if (!storm.range(c.storm, noiseReach + 0.15, pad, out)) {
                return false;
            }
            out[0] -= c.wy;
            out[1] -= c.wy;
            return true;
        }
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int k = 0; k < members.size(); k++) {
            Member m = members.get(k);
            double f = (m.r - c.e[k]) / rf;
            if (m.tower > 0.05) {
                double et = Math.sqrt(c.towerSq[k]) - m.lean * m.h * 1.2;
                double rt = m.r * (0.28 + 0.22 * m.tower);
                f = Math.max(f, (rt - et) / rf);
            }
            if (f - (1 - m.coverage) * 0.3 + slack >= -noiseReach) {
                lo = Math.min(lo, m.yb - pad);
                hi = Math.max(hi, m.top() + m.overshoot + pad);
            }
        }
        if (anvil != null && c.anvilHorizontal + slack >= -noiseReach) {
            lo = Math.min(lo, anvil.top - anvil.thick - pad);
            hi = Math.max(hi, anvil.top + pad);
        }
        if (lo > hi) {
            return false;
        }
        // The bounds above are in warped height; a point at y is evaluated at y + wy.
        out[0] = lo - c.wy;
        out[1] = hi - c.wy;
        return true;
    }

    // ---- density -------------------------------------------------------------------------------------------------

    /** The steepest the envelope gets, per block (the base and tops change 3 units per {@link #rf} blocks). */
    double maxSlope() {
        return 3.2 / rf + erosionTop / Math.max(1, topY - baseY);
    }

    /**
     * How much the body's density is lowered at height {@code y} (warped) by birth and death: {@link #erosionBase} at
     * the base, rising linearly by {@link #erosionTop} to the top.
     */
    double erosion(double y) {
        if (erosionBase <= 0 && erosionTop <= 0) {
            return 0;
        }
        double hf = clamp01((y - baseY) / Math.max(1, topY - baseY));
        return erosionBase + erosionTop * hf;
    }

    /**
     * How far apart (blocks) the full density is sampled before being interpolated to the voxels: 8 for small
     * clouds, up to 32 for storms, whose smallest features (the churn) are hundreds of blocks across.
     */
    int latticeStep() {
        return Integer.highestOneBit((int) Math.max(8, Math.min(32, billowScale / 6)));
    }

    /**
     * Whether the box (anchor-local x and z, world y) holds small details that need the finer lattice: a supercell's
     * flanking towers (bubbles) and shelf cloud (tiers). Other clouds don't need it; their lattice already follows
     * their size.
     */
    boolean needsFineLattice(double x0, double x1, double y0, double y1, double z0, double z1) {
        if (storm == null) {
            return false;
        }
        // The warp moves the details around by up to its length.
        double m = warpLength + rf * 0.05;
        return storm.hasFineDetail(x0 - m, x1 + m, y0 - m, y1 + m, z0 - m, z1 + m);
    }

    /** The envelope (warp and fibres, no billows) at height {@code y} in column {@code c}. */
    double envelope(Column c, double y) {
        double yw = y + c.wy;
        double eb = erosion(yw);
        if (storm != null) {
            return smoothMax(storm.body(c.storm, yw, c.time) - eb, storm.top(c.storm, yw) - anvilErosion, 0.3);
        }
        double dm = membersAt(c, yw) - eb;
        return anvil == null ? dm : Math.max(dm, anvilAt(c, yw) - anvilErosion);
    }

    /**
     * The density at (x, y, z), anchor-local x and z, world y, at {@code time}; {@code c} is this x and z's column.
     * ({@code detail} is unused: the churn is sized from the cloud, with no small-scale layer.)
     */
    double density(Column c, double x, double y, double z, double time, boolean detail) {
        double yw = y + c.wy;
        double eb = erosion(yw);
        double dm;
        double da;
        double base;
        if (storm != null) {
            dm = storm.body(c.storm, yw, time) - eb;
            da = storm.top(c.storm, yw) - anvilErosion;
            base = smoothMax(dm, da, 0.3);
        } else {
            dm = membersAt(c, yw) - eb;
            da = anvil == null ? -10 : anvilAt(c, yw) - anvilErosion;
            base = Math.max(dm, da);
        }
        if (base < -noiseReach || base > noiseReach + 0.07) {
            // Too far outside or inside for the billows to change which side it is on.
            return base;
        }
        double edge = 0.8 + 0.5 * maxEdge;
        double ms = billowScale;
        double tb = time / billowPeriod;
        double yr = (y - riseRate * time) / ms;
        double a = CloudNoise.gradient3(x / ms + 0.31 * tb, yr, z / ms - 0.17 * tb, seed + 11);
        // A second layer at half the size, drifting another way, so features morph rather than slide.
        double b = CloudNoise.gradient3(x / ms * 2 - 0.23 * tb + 5.2, yr * 2, z / ms * 2 + 0.29 * tb - 3.1,
                seed + 23);
        double n = billowAmp * (0.65 * a + 0.35 * b) * edge;
        if (storm != null && n > 0) {
            // Near a storm's base the churn only carves: nothing but the wall cloud hangs below the base.
            n *= Math.max(0, Math.min(1, (yw - storm.yb) / (0.04 * storm.h)));
        }
        return storm != null ? smoothMax(dm + n, da + 0.3 * n, 0.3) : smoothMax(dm + n, da + 0.45 * n, 0.25);
    }

    /** The members' smoothly blended envelope at warped height {@code yw}. */
    private double membersAt(Column c, double yw) {
        double d = -10;
        for (int q = 0; q < c.activeCount; q++) {
            int k = c.active[q];
            d = smoothMax(d, member(memberArray[k], c, k, yw), 0.15);
        }
        return d;
    }

    private double member(Member m, Column c, int k, double y) {
        double rise = y - m.yb;
        double fBase = rise / rf * 3;
        double domeH = m.h * (1 - 0.45 * m.tower);
        double vd = rise / domeH;
        double prof;
        if (vd < 0.2) {
            prof = 0.85 + 0.75 * Math.max(vd, -1);
        } else {
            double q = (vd - 0.2) / 0.8;
            prof = Math.sqrt(Math.max(0, 1 - q * q));
        }
        double f = (prof * m.r - c.e[k]) / rf;
        // Capped at the dome's top: above it the radius is zero, which alone would leave the field flat over the centre.
        f = Math.min(f, (domeH - rise) / rf * 3);
        if (m.tower > 0.05) {
            // Distance to the leaning tower axis: |offset - lean * rise * drift|.
            double lr = m.lean * rise;
            double et = Math.sqrt(Math.max(0, c.towerSq[k] - 2 * lr * c.towerDot[k] + lr * lr));
            double v = Math.max(0, Math.min(1.2, rise / m.h));
            double rt = m.r * (0.28 + 0.22 * m.tower) * (1 - 0.25 * v);
            double top = m.top() + m.overshoot;
            double ft = Math.min((rt - et) / rf, (top - y) / rf * 2.5);
            f = smoothMax(f, ft, 0.12);
        }
        return Math.min(f, fBase) - (1 - m.coverage) * 0.3;
    }

    /** The anvil at warped height {@code yw}. */
    private double anvilAt(Column c, double yw) {
        double top = (anvil.top - yw) / rf * 3;
        double bottom = (yw - (anvil.top - c.anvilThick)) / rf * 3;
        return Math.min(c.anvilHorizontal, Math.min(top, bottom));
    }

    /** A smooth maximum: like max(a, b), but rounded over a width of {@code k} where they are close. */
    static double smoothMax(double a, double b, double k) {
        double h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.max(a, b) + h * h * k * 0.25;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }
}
