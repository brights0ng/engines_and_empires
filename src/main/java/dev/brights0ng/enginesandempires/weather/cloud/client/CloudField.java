package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/**
 * A cloud formation as a 3D density field: positive inside the cloud, negative outside, in units of the formation's
 * largest radius ({@link #rf}). Pure Java, so it runs on the mesher thread and in tests.
 *
 * <h2>Envelope</h2>
 * <ul>
 *   <li><b>Members.</b> A layer cloud's cluster is a dome: a mostly flat base, widest a little above it, rounding off
 *       toward its top, with an optional narrower column. Each cluster varies from its seed: height +/-15%, a
 *       stretched and turned footprint.</li>
 *   <li><b>Heap clouds</b> (2026-10-06, Bright: "bubbles make the shape") are made of round bubbles only
 *       ({@link Cumulus}): a base layer, stacked clusters up to a cauliflower head (towers are wide stacks, never a
 *       column), turrets on them and, near the camera, puffs on the turrets. They boil by their bubbles swelling and
 *       shrinking; the warp and billows are cut to a nudge ({@link #HEAP_WARP}, {@link #HEAP_BILLOWS}). The base is
 *       cut flat and sharp, a real condensation level.</li>
 *   <li><b>Smooth union.</b> Members are blended with a smooth maximum, so where they meet the surface rounds into a
 *       fillet instead of a crease.</li>
 *   <li><b>Anvil.</b> One per formation, if any member has anvil strength: flat on top at the formation's top,
 *       centred over the tallest tower, reaching a little upwind and far downwind (the direction the formation drifts),
 *       widening and thinning downwind. It is blended onto the towers with a wide smooth maximum, so the neck flares
 *       into it. The tallest tower gets an overshooting top above the anvil.</li>
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
    /** How steep a heap cloud's base is (density units per {@link #rf} blocks; other clouds 3). */
    static final double HEAP_BASE_SHARPNESS = 6;
    /**
     * Heap clouds: the warp and billows as a share of other clouds' (Bright, 2026-10-06: the bubbles make the shape;
     * noise only nudges it).
     */
    static final double HEAP_WARP = 0.04;
    /** Half the old churn (Bright, 2026-10-07: churn on all clouds, medium), at about a turret's size. */
    static final double HEAP_BILLOWS = 0.5;
    /**
     * Layer sheets' churn as a share of the old (2026-10-07 evening): at full strength it moved a sheet's surface by a
     * third of its thickness and cratered a nimbostratus's top; their relief does the shaping now.
     */
    static final double LAYER_BILLOWS = 0.5;
    static final double HEAP_BILLOW_SCALE = 0.15;

    /**
     * Erosion at the end of a death, before the height weighting (in units of {@link #rf}). It grows as
     * {@code decay^3.4}: most of a cloud's volume is near its surface, so even a little erosion takes a lot away, and
     * a gentle start keeps a dying cloud around for most of its death.
     */
    static final double DEATH_EROSION = 0.6;
    static final double DEATH_POWER = 3.4;
    /** Extra erosion over the last 15% of a death, so the last wisps are gone by its end instead of popping. */
    static final double DEATH_FINISH = 0.7;
    /** Heap clouds: the even erosion at the end of a death (they mostly die by their bubbles, see Cumulus). */
    static final double HEAP_DEATH_EROSION = 0.03;
    /**
     * Erosion at the very start of a birth, shrinking as {@code (1 - growth)^2}. Strong enough that a cloud starts from
     * nothing (Bright, 2026-10-04: the first build of a big cloud was already a large chunk of it): the first fragments
     * appear a little into the birth and the cloud catches up by its end.
     */
    static final double BIRTH_EROSION = 0.75;
    /** Erosion of a fully thinned anvil, growing as {@code anvilDecay^3}. */
    static final double ANVIL_EROSION = 0.35;
    /** A cloud's water content when it starts forming, as a share of its full content (it builds over the birth). */
    static final double FORMING_WATER = 0.25;

    /**
     * Nothing of any cloud is ever below this, world y (Bright, 2026-10-10: no part of a cloud, not only its base).
     * The vertical warp (up to 4% of the cloud's radius, ~50 blocks on a big storm), a layer sheet's relief (a
     * nimbostratus base undulates by 3% of its ~1,000-block thickness), the churn and the voxel lattice could each carry
     * a cloud well below its base: {@link #density} is cut off here, and the voxelizer drops every voxel whose bottom is
     * lower (CloudVoxelizer.sample, grid).
     */
    static final double FLOOR_Y = CloudScale.MIN_BASE_Y;
    /** How fast the density falls below {@link #FLOOR_Y}, density units per block: a hard cut. */
    static final double FLOOR_SHARPNESS = 1.0;

    /** {@code d} at world height {@code y}, cut off below {@link #FLOOR_Y}. */
    static double floored(double y, double d) {
        return Math.min(d, (y - FLOOR_Y) * FLOOR_SHARPNESS);
    }

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
    /** How far through its death the formation is (0-1). */
    final double dying;
    final int seed;
    final Anvil anvil;
    /** The body's erosion at its base and how much more there is at its top (see {@link #erosion}). */
    final double erosionBase;
    final double erosionTop;
    /** The anvil's erosion. */
    final double anvilErosion;
    /** Whether any member is a heap cloud (bubbles, flat base, puffs). */
    final boolean heap;
    /** Whether any member is a layer cloud (a sheet: slabs with their own relief). */
    final boolean layered;
    /** Bubbles over all members, for the columns' scratch arrays. */
    final int totalBubbles;
    /**
     * Where the light comes from for this build (unit vector toward the sun, or the moon at night) and how strongly
     * it shapes the cloud (1 by day, less by moonlight, 0 flat): see {@link #withLight}. Defaults: a mid-morning sun.
     */
    double lightX = 0.42;
    double lightY = 0.78;
    double lightZ = 0.46;
    double lightStrength = 1;
    /**
     * The other clouds' shadows on this one ({@link CloudShadows}): the picture to ask, where this formation's anchor is
     * in the world (the field is anchor-local), and this formation's own domes (left out). Default: none.
     */
    CloudShadows shadows = CloudShadows.EMPTY;
    double originX;
    double originZ;
    java.util.Set<java.util.UUID> own = java.util.Set.of();

    /** One dome, with its own variation, positioned relative to the formation's anchor. */
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
        /** Heap clouds: whether this is one, its bubbles, and them at the last time asked for. */
        boolean heap;
        /** Layer clouds (stratus, stratocumulus, nimbostratus, ...): drawn as a slab, merging into a sheet. */
        boolean layer;
        /** Layer clouds: which relief its top and base get (see {@link CloudField#layerRelief}). */
        int layerKind;
        int layerSeed;
        Cumulus cu;
        volatile Cumulus.Anim anim;
        int b0;
        /** Heap clouds: how far through its death this member is (0-1); its bubbles evaporate in turn. */
        double dying;
        /** Heap clouds: how far through its birth this member is (0-1); its bubbles form in turn. */
        double growth = 1;

        double top() {
            return yb + h;
        }

        /** The dome's height under the tower (layer clouds). */
        double domeHeight() {
            return h * (1 - 0.45 * tower);
        }

        /** The bubbles at game time {@code time}. */
        Cumulus.Anim anim(double time) {
            Cumulus.Anim a = anim;
            if (a == null || a.time() != time) {
                a = cu.animate(time, growth, dying);
                anim = a;
            }
            return a;
        }

        /**
         * Each bubble's blend width (density units) for the bubbles {@code a}, and how far out it can reach (blocks,
         * before the column's margin), worked out once per moment.
         */
        volatile BlendWidths blend;

        double[] blendWidths(Cumulus.Anim a, double rf) {
            return blendFor(a, rf).widths;
        }

        BlendWidths blendFor(Cumulus.Anim a, double rf) {
            BlendWidths w = blend;
            if (w == null || w.anim != a) {
                double[] out = new double[cu.n];
                double[] reach = new double[cu.n];
                for (int b = 0; b < cu.n; b++) {
                    // As heapMember computed it per sample before 2026-10-08 (same operations, same order).
                    double width = blendShare(cu.level[b]) * a.r()[b] / rf * CloudTuning.bubbleSmoothing;
                    out[b] = Math.max(1e-4, width);
                    // As column() computed it per column (the margin is added there).
                    reach[b] = a.r()[b] * (1 + blendShare(cu.level[b]) * CloudTuning.bubbleSmoothing);
                }
                w = new BlendWidths(a, out, reach);
                blend = w;
            }
            return w;
        }
    }

    /** A member's bubble blend widths and reaches for one moment of its bubbles. */
    record BlendWidths(Cumulus.Anim anim, double[] widths, double[] reach) {
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
                       Anvil anvil, double growth, double decay, double anvilDecay, double anvilErosionScale,
                       boolean heap) {
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
        this.heap = heap;
        this.warpLength = (heap ? HEAP_WARP : WARP_FRACTION) * rf;
        // The churn is sized from the cloud (about a third of its largest radius), and slower when bigger.
        this.billowScale = (heap ? Math.max(rf * HEAP_BILLOW_SCALE, 12) : Math.max(rf * 0.35, 18))
                * Math.max(0.05, CloudTuning.noiseScale);
        this.billowPeriod = BILLOW_PERIOD_TICKS * Math.sqrt(Math.max(1, billowScale / 40));
        // Forming and dying clouds churn more: the noise breaks them into fragments and frays their edges.
        double unformed = 1 - clamp01(growth);
        double dying = clamp01(decay);
        // A dying heap cloud frays: its churn grows to three times as it goes (Bright, 2026-10-07).
        this.billowAmp = BILLOW_AMP * CloudTuning.noiseStrength * (1 + 0.9 * unformed + (heap ? 3.0 : 1.0) * dying)
                * (heap ? HEAP_BILLOWS : layerBillows(members));
        this.dying = dying;
        this.seed = seed;
        this.anvil = anvil;
        int total = 0;
        for (Member m : members) {
            m.b0 = total;
            total += m.cu == null ? 0 : m.cu.n;
        }
        this.totalBubbles = total;
        boolean anyLayer = false;
        for (Member m : members) {
            anyLayer |= m.layer;
        }
        this.layered = anyLayer;
        this.noiseReach = billowAmp * (0.8 + 0.5 * maxEdge) + 0.01;
        double finish = clamp01((dying - 0.85) / 0.15);
        // Heap clouds die by their bubbles evaporating in turn (Cumulus): only a light, even erosion on top, which
        // with the stronger churn frays their edges. Other clouds erode away.
        double death = heap ? HEAP_DEATH_EROSION * dying
                : DEATH_EROSION * Math.pow(dying, DEATH_POWER) + DEATH_FINISH * finish * finish;
        // Heap clouds form by their bubbles (Cumulus): only a light erosion on top while they do.
        double birth = heap ? HEAP_DEATH_EROSION * unformed : BIRTH_EROSION * unformed * unformed;
        // Death: 0.75 at the base to 1.35 at the top; birth: 0.4 at the base to 1.6 at the top.
        this.erosionBase = 0.75 * death + 0.4 * birth;
        this.erosionTop = 0.6 * death + 1.2 * birth;
        double ad = clamp01(anvilDecay);
        this.anvilErosion = anvilErosionScale * ad * ad * ad;
    }

    /**
     * Layer sheets' churn: {@link #LAYER_BILLOWS}, and a quarter of the old for altostratus, whose thin wave troughs it
     * would hole everywhere (they open in patches instead); 1 for other clouds.
     */
    private static double layerBillows(List<Member> members) {
        double f = 1;
        for (Member m : members) {
            if (m.layer) {
                f = Math.min(f, m.layerKind == LAYER_ALTOSTRATUS ? 0.25 : LAYER_BILLOWS);
            }
        }
        return f;
    }

    /** Sets the light for this build: toward (x, y, z) (normalised here) at {@code strength}; returns this. */
    CloudField withLight(double x, double y, double z, double strength) {
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l > 1e-9) {
            lightX = x / l;
            lightY = y / l;
            lightZ = z / l;
        }
        lightStrength = Math.max(0, Math.min(1, strength));
        return this;
    }

    /**
     * Sets the other clouds' shadows for this build: {@code shadows}, with formation {@code f}'s anchor where it is at
     * {@code time} and its domes left out; returns this.
     */
    CloudField withShadows(CloudShadows shadows, CloudFormation f, double time) {
        this.shadows = shadows;
        this.originX = f.anchor().xAt(time);
        this.originZ = f.anchor().zAt(time);
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        for (CloudShape m : f.members()) {
            ids.add(m.id());
        }
        this.own = ids;
        return this;
    }

    /** {sky, sun} light let through by the other clouds above anchor-local (x, y, z). */
    double[] shadowAt(double x, double y, double z) {
        return shadows.light(x + originX, y, z + originZ, own);
    }

    /**
     * Blocks of cloud between (x, y, z) and the light, toward (lx, ly, lz) (a unit vector), at {@code time}: the chords
     * the ray cuts through the heap members' body bubbles and turrets (the puffs are too small to matter), times
     * {@link #CHORD_OVERLAP} for the bubbles overlapping. Smooth, independent of the voxel size and the same in every
     * section, so neighbouring sections light alike.
     */
    double lightDepth(double x, double y, double z, double lx, double ly, double lz, double time) {
        double total = 0;
        double dy = ly / Cumulus.SQUASH;
        double a = lx * lx + dy * dy + lz * lz;
        for (Member m : memberArray) {
            if (!m.heap) {
                continue;
            }
            Cumulus.Anim an = m.anim(time);
            for (int b = 0; b < m.cu.withoutPuffs; b++) {
                double ox = x - an.x()[b];
                double oy = (y - an.y()[b]) / Cumulus.SQUASH;
                double oz = z - an.z()[b];
                double r = an.r()[b];
                double half = ox * lx + oy * dy + oz * lz;
                double c = ox * ox + oy * oy + oz * oz - r * r;
                double disc = half * half - a * c;
                if (disc <= 0) {
                    continue;
                }
                double sq = Math.sqrt(disc);
                double t2 = (-half + sq) / a;
                if (t2 <= 0) {
                    continue;
                }
                double t1 = Math.max(0, (-half - sq) / a);
                total += t2 - t1;
            }
        }
        return total * CHORD_OVERLAP;
    }

    /** The share of summed bubble chords that is real cloud (the bubbles overlap). */
    static final double CHORD_OVERLAP = 0.55;

    /** Builds the field of formation {@code f}, or null if nothing in it is visible. */
    static CloudField of(CloudFormation f) {
        CloudShape anchor = f.anchor();
        List<CloudShape> visible = new ArrayList<>();
        for (CloudShape m : f.members()) {
            // (Cirrostratus and cirrus are see-through veils drawn by their own shader: CloudVeils.)
            if (m.visible() && !CloudVeils.veil(m.typeId())) {
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
            rf = Math.max(rf, c.radius());
            maxTower = Math.max(maxTower, clamp01(c.towerStrength()));
            maxAnvil = Math.max(maxAnvil, clamp01(c.anvilStrength()));
            maxEdge = Math.max(maxEdge, clamp01(c.edgeSoftness()));
            baseDark = Math.max(baseDark, (float) clamp01(c.baseDarkness()));
            stormDark = Math.max(stormDark, (float) clamp01(c.stormDarkness()));
            // Denser clouds, and raining ones, hold more water.
            water = Math.max(water, typeWater(c.typeId()) * (0.6 + 0.8 * clamp01(c.density()))
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

        List<Member> members = new ArrayList<>(visible.size());
        Member updraft = null;
        double baseY = Double.MAX_VALUE;
        double topY = -Double.MAX_VALUE;
        boolean anyHeap = false;
        for (CloudShape c : visible) {
            SplittableRandom rng = new SplittableRandom(c.seed() * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
            Member m = new Member();
            m.cx = f.offsetX(c);
            m.cz = f.offsetZ(c);
            CloudType type = CloudType.of(c.typeId());
            boolean heapType = type != null && type.heap();
            // A forming layer cloud spreads a little as it grows; a dying one keeps its footprint (it erodes instead).
            // Heap clouds are laid out at their full size from the start and form bubble by bubble (Cumulus), so
            // their layout never changes as they grow (Bright, 2026-10-07).
            m.r = Math.max(c.radius() * (heapType ? 1 : 0.75 + 0.25 * clamp01(c.growth())), 4);
            m.yb = c.baseY();
            double hScale = 0.85 + 0.3 * rng.nextDouble();
            double density = clamp01(c.density());
            double thickness = c.topY() - c.baseY();
            if (heapType && type.grows) {
                // The full thickness, from the grown-so-far one (CloudScale.heights).
                thickness /= CloudScale.FORMING_THICKNESS + (1 - CloudScale.FORMING_THICKNESS) * clamp01(c.growth());
            }
            // A dying layer cloud's tops sink as they evaporate. (Heap clouds keep their bubbles' layout through their
            // death, or it would reshape partway through: their bubbles sink and shrink instead, in Cumulus.)
            m.h = Math.max(thickness, 4) * (0.6 + 0.4 * density) * hScale
                    * (heapType ? 1 : 1 - 0.3 * clamp01(c.decay()));
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
            m.coverage = clamp01(c.coverage());
            m.layer = type != null && type.layer();
            if (m.layer) {
                // Drawn a little wider than the simulation's footprint, so a deck's neighbouring slots join up.
                m.r *= LAYER_REACH;
                m.layerKind = type == CloudType.STRATUS ? LAYER_STRATUS : type == CloudType.STRATOCUMULUS
                        ? LAYER_STRATOCUMULUS : type == CloudType.NIMBOSTRATUS ? LAYER_NIMBOSTRATUS
                        : type == CloudType.ALTOSTRATUS ? LAYER_ALTOSTRATUS : LAYER_OTHER;
                m.layerSeed = c.seed();
            }
            if (heapType) {
                m.heap = true;
                anyHeap = true;
                m.dying = clamp01(c.decay());
                m.growth = clamp01(c.growth());
                double richness = switch (type) {
                    case CUMULUS_HUMILIS -> 0;
                    case CUMULUS_MEDIOCRIS -> 0.35;
                    case CUMULUS_CONGESTUS -> 0.7;
                    default -> 1;
                };
                m.cu = Cumulus.of(m.cx, m.cz, m.yb, m.r, m.h, m.tower, m.aspect, m.cos, m.sin, dx, dz, m.lean,
                        richness, rng);
            }
            members.add(m);
            baseY = Math.min(baseY, m.yb);
            topY = Math.max(topY, m.heap ? m.cu.top : m.top());
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
                anchor.seed(), anvil, growth, decay, anvilDecay, ANVIL_EROSION, anyHeap);
    }



    /** A cloud type's liquid water content, g/m^3 ({@link CloudType#water}; 0.4 for unknown types). */
    static double typeWater(String typeId) {
        CloudType t = CloudType.of(typeId);
        return t == null ? 0.4 : t.water;
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
        if (layered) {
            // Layer sheets (2026-10-07 evening): the churn moves their surface by a good share of their thickness, so
            // the cloud above is measured with it, and where the surface crosses between samples, in proportion
            // (whole steps in or out made a thick deck's top look buried and its shading blotchy).
            double was = density(c, x, range[1], z, time, false);
            for (int i = 1; i < n; i++) {
                double v = density(c, x, range[1] - i * step, z, time, false);
                double inside;
                if (was > 0 && v > 0) {
                    inside = 1;
                } else if (was <= 0 && v <= 0) {
                    inside = 0;
                } else {
                    inside = Math.max(was, v) / Math.abs(was - v);
                }
                depth[i] = depth[i - 1] + (float) (step * inside);
                was = v;
            }
            return new Profile(range[1], step, depth);
        }
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
        for (Member m : members) {
            double reach = (m.heap ? m.cu.reach : m.r * 1.25 + Math.hypot(m.towerOffX, m.towerOffZ) + m.lean * m.h)
                    + pad;
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
        return new double[]{minX, maxX, Math.max(FLOOR_Y, baseY - rf * 0.06), topY + rf * 0.08, minZ, maxZ};
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
        /** Per bubble (all members, from each member's {@code b0}): squared horizontal distance to its centre. */
        final double[] bh = new double[totalBubbles];
        /** The bubbles near enough to this column to matter, per member: {@code bIdx[b0 .. b0 + bCount[k])}. */
        final int[] bIdx = new int[totalBubbles];
        final int[] bCount = new int[members.size()];
        /** The members that can matter anywhere in this column (the rest are too far away horizontally). */
        final int[] active = new int[members.size()];
        int activeCount;
        /** The anvil's horizontal part (fibres included) and its thickness here. */
        double anvilHorizontal;
        double anvilThick;
        /**
         * Per layer member: its top and base here as shares of its thickness above its base height (the base can go
         * below 0, bulging down), and the height (same units) where they meet at the sheet's rim.
         */
        final double[] layerTop = new double[members.size()];
        final double[] layerBase = new double[members.size()];
        final double[] layerMid = new double[members.size()];
        /**
         * Per layer member: whether its relief here has been worked out yet. It is worked out the first time the
         * member is evaluated in this column, not when the column is filled (2026-10-08: a deck of 83 domes worked
         * out the puffs of nearly all of them in every column, most of which could never change the surface there).
         */
        final boolean[] reliefDone = new boolean[members.size()];
        /** The warped position the column was filled at (anchor-local), for working out reliefs later. */
        double xw;
        double zw;
        /** The time this column was filled for. */
        double time;
    }

    Column newColumn() {
        return new Column();
    }

    /** Fills {@code c} for the column at anchor-local (x, z) at {@code time}. */
    void column(double x, double z, double time, Column c) {
        column(x, z, time, c, 0);
    }

    /**
     * As {@link #column(double, double, double, Column)}, keeping heap bubbles up to {@code extra} blocks further away
     * than the surface needs: for sampling the envelope a little outside the cloud (its slope, for shading).
     */
    void column(double x, double z, double time, Column c, double extra) {
        double ws = rf * 0.7;
        double tw = time / WARP_PERIOD_TICKS;
        c.wx = CloudNoise.gradient3(x / ws, z / ws, tw, seed + 101) * warpLength;
        // (A layer sheet's base stays nearly level: its vertical warp is a fraction of a heap cloud's.)
        c.wy = CloudNoise.gradient3(x / ws, z / ws, tw, seed + 202) * rf * 0.04 * (layered ? 0.15 : 1);
        c.wz = CloudNoise.gradient3(x / ws, z / ws, tw, seed + 303) * warpLength;
        double xw = x + c.wx;
        double zw = z + c.wz;
        c.xw = xw;
        c.zw = zw;
        c.time = time;
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
            if (m.heap) {
                // Only bubbles that reach this column (plus what the noise and erosion can't change) matter.
                Cumulus.Anim a = m.anim(time);
                double[] reachOf = m.blendFor(a, rf).reach;
                int nbHere = 0;
                int limit = m.cu.n;
                double margin = (noiseReach + 0.02) * rf + extra;
                for (int b = 0; b < limit; b++) {
                    double bdx = xw - a.x()[b];
                    double bdz = zw - a.z()[b];
                    double d2 = bdx * bdx + bdz * bdz;
                    // A bubble changes the blend out to its blend width beyond its edge: dropping it any closer leaves
                    // a step in the field where it stops being counted, which the shading shows as vertical streaks.
                    double reach = reachOf[b] + margin;
                    if (debugReference) {
                        reach = a.r()[b] * (1 + blendShare(m.cu.level[b]) * CloudTuning.bubbleSmoothing) + margin;
                    }
                    if (d2 < reach * reach) {
                        c.bh[m.b0 + b] = d2;
                        c.bIdx[m.b0 + nbHere++] = b;
                    }
                }
                c.bCount[k] = nbHere;
                if (nbHere > 0) {
                    c.active[c.activeCount++] = k;
                }
                continue;
            }
            // The most this member can reach here, at any height: its widest dome, or its tower leant over.
            double f = (m.r - c.e[k]) / rf;
            if (m.tower > 0.05) {
                double et = Math.sqrt(c.towerSq[k]) - m.lean * m.h * 1.2;
                f = Math.max(f, (m.r * (0.28 + 0.22 * m.tower) - et) / rf);
            }
            if (f - (1 - m.coverage) * 0.3 >= cutoff) {
                c.active[c.activeCount++] = k;
                if (m.layer) {
                    // Its relief, the costly part, waits until the member is evaluated here (layerMember).
                    c.reliefDone[k] = false;
                    if (debugReference) {
                        layerRelief(m, dx, dz, c, k);
                        c.reliefDone[k] = true;
                    }
                }
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
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int k = 0; k < members.size(); k++) {
            Member m = members.get(k);
            if (m.heap) {
                if (c.bCount[k] > 0) {
                    lo = Math.min(lo, m.yb - pad);
                    hi = Math.max(hi, m.cu.top + pad);
                }
                continue;
            }
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
        // Never below the floor: nothing of a cloud is there (FLOOR_Y).
        out[0] = Math.max(lo - c.wy, FLOOR_Y);
        out[1] = hi - c.wy;
        return out[1] >= out[0];
    }

    // ---- density -------------------------------------------------------------------------------------------------

    /** The steepest the envelope gets, per block (the base and tops change 3 units per {@link #rf} blocks). */
    double maxSlope() {
        double slope = (heap ? HEAP_BASE_SHARPNESS + 0.2 : 3.2) / rf;
        for (Member m : memberArray) {
            if (m.layer) {
                // A layer slab's top and base change by its own thickness, not the formation's size; plus its relief's
                // slopes (the puffs' sides, worked out from layerRelief) and its rim (at most 0.92 / (edge x r)).
                double relief = switch (m.layerKind) {
                    case LAYER_STRATOCUMULUS -> 1.9;
                    case LAYER_NIMBOSTRATUS -> 0.8;
                    case LAYER_ALTOSTRATUS -> 2.0;
                    case LAYER_STRATUS -> 0.25;
                    default -> 0;
                };
                slope = Math.max(slope, LAYER_SHARPNESS * 0.15 / Math.max(m.h, 8) + 1.0 / (LAYER_EDGE * m.r)
                        + relief / Math.max(m.h, 8));
            }
        }
        return slope + erosionTop / Math.max(1, topY - baseY);
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
        if (heap) {
            // Sized by the bubbles: their turrets are about a twentieth of the cloud across.
            return Integer.highestOneBit((int) Math.max(4, Math.min(16, rf * 0.06)));
        }
        return Integer.highestOneBit((int) Math.max(8, Math.min(32, billowScale / 6)));
    }

    /** Whether the box holds small details that need a finer lattice: no cloud type has any now (the lattice follows size). */
    boolean needsFineLattice(double x0, double x1, double y0, double y1, double z0, double z1) {
        return false;
    }

    /** The envelope (warp and fibres, no billows) at height {@code y} in column {@code c}. */
    double envelope(Column c, double y) {
        double yw = y + c.wy;
        double eb = erosion(yw);
        double dm = membersAt(c, yw) - eb;
        return anvil == null ? dm : Math.max(dm, anvilAt(c, yw) - anvilErosion);
    }

    /**
     * The density at (x, y, z), anchor-local x and z, world y, at {@code time}; {@code c} is this x and z's column.
     * ({@code detail} is unused: the churn is sized from the cloud, with no small-scale layer.)
     */
    double density(Column c, double x, double y, double z, double time, boolean detail) {
        return density(c, x, y, z, time, detail, false);
    }

    /**
     * As {@link #density(Column, double, double, double, double, boolean)}; {@code smooth} adds the churn everywhere,
     * not only near the surface, so the field has no steps (for the shading's surface directions: the step where the
     * churn is left out made a layer sheet's shading look like fur).
     */
    double density(Column c, double x, double y, double z, double time, boolean detail, boolean smooth) {
        double yw = y + c.wy;
        double eb = erosion(yw);
        double dm = membersAt(c, yw) - eb;
        double da = anvil == null ? -10 : anvilAt(c, yw) - anvilErosion;
        double base = Math.max(dm, da);
        if (!smooth && (base < -noiseReach || base > noiseReach + 0.07)) {
            // Too far outside or inside for the billows to change which side it is on.
            return floored(y, base);
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
        if (heap) {
            // A cumulus's base is flat: the churn fades out toward it (less so as it dies and goes ragged).
            double rise = (y - baseY) / (0.12 * (topY - baseY) + 6);
            double floor = 0.2 + 0.6 * dying;
            double flat = floor + (1 - floor) * smooth(rise);
            n *= flat;
        }
        return floored(y, smoothMax(dm + n, da + 0.45 * n, 0.25));
    }

    /** The members' smoothly blended envelope at warped height {@code yw}. */
    private double membersAt(Column c, double yw) {
        double d = -10;
        for (int q = 0; q < c.activeCount; q++) {
            int k = c.active[q];
            Member m = memberArray[k];
            // A layer member is never above its rim term (layerMember): if that is a whole blend width below what is
            // already here, the smooth maximum would return it unchanged, so its relief needn't be worked out.
            if (m.layer && !debugReference && (m.r - c.e[k]) / rf - (1 - m.coverage) * 0.3 <= d - 0.15) {
                continue;
            }
            d = smoothMax(d, member(m, c, k, yw), 0.15);
        }
        return d;
    }

    private double member(Member m, Column c, int k, double y) {
        double rise = y - m.yb;
        if (m.heap) {
            return heapMember(m, c, k, y, rise);
        }
        if (m.layer) {
            return layerMember(m, c, k, rise);
        }
        double fBase = rise / rf * 3;
        double domeH = m.domeHeight();
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

    /**
     * A heap member: its bubbles blended with a smooth maximum as wide as a third of each bubble (body), a fifth
     * (turrets) or a seventh (puffs), so neighbours merge with a rounded crease rather than a seam or a blur, cut flat at
     * the base. Distances in density units ({@link #rf}).
     */
    private double heapMember(Member m, Column c, int k, double y, double rise) {
        Cumulus.Anim a = m.anim(c.time);
        if (debugReference) {
            return heapMemberReference(m, a, c, k, y, rise);
        }
        double[] widths = m.blendWidths(a, rf);
        double[] ay = a.y();
        double[] ar = a.r();
        int b0 = m.b0;
        // The flat base: the result is never above this, and the blend below only ever rises, so once it reaches
        // the base's value that is the answer whatever the remaining bubbles do (2026-10-08: most of a storm's
        // shading samples are on its broad base, deep inside its bubbles).
        double baseCut = rise / rf * HEAP_BASE_SHARPNESS;
        double f = -10;
        for (int q = 0; q < c.bCount[k]; q++) {
            int b = c.bIdx[b0 + q];
            double r = ar[b];
            double dy = (y - ay[b]) / Cumulus.SQUASH;
            double d2 = c.bh[b0 + b] + dy * dy;
            // Wider blends fill the creases between bubbles (Bright, 2026-10-07: "a mild smoothing").
            double w = widths[b];
            // A bubble a whole blend width or more below what is already here can't change it: the smooth maximum
            // returns the larger value exactly. Telling so needs no square root (2026-10-08: this loop was most of a
            // storm's build time, and most bubbles near a column are far below the surface being shaded).
            double t = r - (f - w) * rf;
            if (t <= 0 || d2 >= t * t) {
                continue;
            }
            double fb = (r - Math.sqrt(d2)) / rf;
            f = smoothMax(f, fb, w);
            if (f >= baseCut) {
                return baseCut;
            }
        }
        return Math.min(f, baseCut);
    }

    /**
     * Tests only: the field as it was worked out before the 2026-10-08 speed-ups (every bubble, every member's relief
     * up front, puffs hashed inline), to check the fast paths give exactly the same numbers.
     */
    static volatile boolean debugReference;

    private double heapMemberReference(Member m, Cumulus.Anim a, Column c, int k, double y, double rise) {
        Cumulus cu = m.cu;
        double f = -10;
        for (int q = 0; q < c.bCount[k]; q++) {
            int b = c.bIdx[m.b0 + q];
            double r = a.r()[b];
            double dy = (y - a.y()[b]) / Cumulus.SQUASH;
            double fb = (r - Math.sqrt(c.bh[m.b0 + b] + dy * dy)) / rf;
            int level = cu.level[b];
            double blend = blendShare(level) * r / rf * CloudTuning.bubbleSmoothing;
            f = smoothMax(f, fb, Math.max(1e-4, blend));
        }
        return Math.min(f, rise / rf * HEAP_BASE_SHARPNESS);
    }

    /** A bubble's blend width as a share of its radius, before {@link CloudTuning#bubbleSmoothing}. */
    static double blendShare(int level) {
        return level == Cumulus.BODY ? 0.33 : level == Cumulus.TURRET ? 0.2 : 0.14;
    }

    /** How far in from a layer dome's edge its slab reaches its full thickness, as a share of its radius. */
    static final double LAYER_EDGE = 0.3;
    /** Layer domes are drawn this much wider than the simulation's footprint (and touch at that size). */
    public static final double LAYER_REACH = 1.3;
    /** A layer slab's top and base: how sharp they are (density units per {@link #rf} blocks). */
    static final double LAYER_SHARPNESS = 3;

    /**
     * A layer member (2026-10-07, Bright: layer clouds are continuous sheets): a slab of its thickness over its
     * footprint with its own relief ({@link #layerRelief}), so neighbouring domes and neighbouring clouds of the deck
     * merge into one sheet. Over the outer {@link #LAYER_EDGE} of its radius its top comes down and its base comes up
     * to meet at a rounded rim (Bright, 2026-10-07 evening: no "floating icebergs", so no wall at the edge). The churn
     * noise frays the edge and opens holes where the cover is thin.
     */
    private double layerMember(Member m, Column c, int k, double rise) {
        if (!c.reliefDone[k]) {
            // (The same offsets column() had: warped position minus the member's centre.)
            layerRelief(m, c.xw - m.cx, c.zw - m.cz, c, k);
            c.reliefDone[k] = true;
        }
        double horiz = (m.r - c.e[k]) / rf;
        double in = clamp01((m.r - c.e[k]) / (LAYER_EDGE * m.r));
        // Rounded: full thickness inside, closing over the rim (a slope there, not a wall).
        double rim = 1 - (1 - in) * (1 - in);
        double mid = c.layerMid[k];
        double top = m.h * (mid + (c.layerTop[k] - mid) * rim);
        double base = m.h * (mid + (c.layerBase[k] - mid) * rim);
        double sharp = LAYER_SHARPNESS * 0.15 / Math.max(m.h, 8);
        double f = Math.min(horiz, Math.min((top - rise) * sharp, (rise - base) * sharp));
        return f - (1 - m.coverage) * 0.3;
    }

    static final int LAYER_STRATUS = 0;
    static final int LAYER_STRATOCUMULUS = 1;
    static final int LAYER_NIMBOSTRATUS = 2;
    static final int LAYER_OTHER = 3;
    static final int LAYER_ALTOSTRATUS = 4;
    /**
     * Stratocumulus elements: their spacing as a multiple of the deck's thickness, and how much longer along the wind
     * (2026-10-07 evening: closer and rounder, 2.2 and 1.8 before, for a puffier look).
     */
    static final double SC_CELL = 1.5;
    static final double SC_ROLL = 1.4;
    /**
     * Altostratus waves (2026-10-07 evening, Bright: parallel waves, the thinnest parts light, sometimes sky between):
     * their wavelength as a multiple of the deck's thickness (real undulatus 0.5-2 km; about 300 blocks), and the
     * scale of the patches where the deck is a plain veil instead or opens up between its waves (in wavelengths).
     */
    static final double ALTO_WAVE = 1.6;
    static final double ALTO_PATCH = 10;

    /**
     * A layer member's relief at (dx, dz) from its centre: its top, base and rim height as shares of its thickness,
     * into column {@code c} (2026-10-07 evening, Bright: all of them a little puffier, clouds rather than icebergs).
     * <ul>
     *   <li><b>Stratus:</b> a nearly level sheet with low, broad, soft swells on top (80-100%), the base sagging a
     *       little under them.</li>
     *   <li><b>Stratocumulus:</b> soft rounded puffs, each a lens (domed on top, bulging a little below) that thins to
     *       nothing at its edge, spaced about twice the deck's thickness apart and stretched along the wind into rolls,
     *       merged where they touch, with smaller puffs on them; some missing (gaps).</li>
     *   <li><b>Nimbostratus:</b> a billowy top of broad rounded swells with smaller ones on them (70-100%); a nearly
     *       level base, gently undulating.</li>
     * </ul>
     */
    private void layerRelief(Member m, double dx, double dz, Column c, int k) {
        double h = Math.max(m.h, 8);
        switch (m.layerKind) {
            case LAYER_STRATUS -> {
                double cell = Math.max(64, 2.5 * h);
                double swell = puffs(dx / cell, dz / cell, m.layerSeed, 0.9, 1.1);
                double n = CloudNoise.gradient3(dx / (h * 6), dz / (h * 6), 0.3, m.layerSeed);
                c.layerTop[k] = 0.65 + 0.33 * swell + 0.03 * n;
                c.layerBase[k] = 0.05 - 0.12 * swell;
                c.layerMid[k] = 0.45;
            }
            case LAYER_NIMBOSTRATUS -> {
                double cell = Math.max(64, h);
                double big = puffs(dx / cell, dz / cell, m.layerSeed, 0.95, 1);
                double small = puffs(dx / (cell * 0.35), dz / (cell * 0.35), m.layerSeed + 7, 0.9, 1);
                double n = CloudNoise.gradient3(dx / (h * 1.5), dz / (h * 1.5), 1.7, m.layerSeed + 1);
                c.layerTop[k] = 0.7 + 0.2 * big + 0.08 * small * (0.4 + 0.6 * big);
                c.layerBase[k] = 0.03 * n;
                c.layerMid[k] = 0.4;
            }
            case LAYER_STRATOCUMULUS -> {
                // In the wind's frame, cells stretched along it.
                double u = (dx * driftX + dz * driftZ) / SC_ROLL;
                double v = -dx * driftZ + dz * driftX;
                double cell = Math.max(48, SC_CELL * h);
                double big = puffs(u / cell, v / cell, m.layerSeed, 0.9 * (0.5 + 0.5 * m.coverage), 1.15);
                double small = puffs(u / (cell * 0.5), v / (cell * 0.5), m.layerSeed + 7, 0.85, 1);
                // A lens per puff: top and base meet at its edge (a gap between puffs), so thin edges stay thin.
                c.layerTop[k] = 0.3 + 0.72 * big + 0.3 * small * big;
                c.layerBase[k] = 0.3 - 0.42 * big;
                c.layerMid[k] = 0.3;
            }
            // (Unwarped: the warp would crumple waves a wavelength apart; it still moves the sheet's outline.)
            case LAYER_ALTOSTRATUS -> altostratusRelief(m, dx + m.cx - c.wx, dz + m.cz - c.wz, c, k);
            default -> {
                c.layerTop[k] = 1;
                c.layerBase[k] = 0;
                c.layerMid[k] = 0.5;
            }
        }
    }

    /**
     * Altostratus (2026-10-07 evening, Bright: "more like parallel waves, with the thinnest parts light and sometimes
     * the sky visible between"; mostly so, sometimes the real plain veil): at anchor-local (ax, az), so one wave train
     * runs across the whole sheet whichever cloud a point belongs to.
     * <ul>
     *   <li><b>Waves:</b> rolls across the wind (as billows in wind shear), a wavelength {@link #ALTO_WAVE} thicknesses
     *       apart, bent gently along their length and a little lumpy; each roll rounded above and below, joined to
     *       the next by a thin web, so the thinnest parts look light from below.</li>
     *   <li><b>Openings:</b> in patches (about {@link #ALTO_PATCH} wavelengths across) the troughs thin to nothing and
     *       the sky shows between the waves.</li>
     *   <li><b>Veil:</b> in other patches (about a third of the sky) the waves fade into a plain, nearly level veil.</li>
     * </ul>
     */
    private void altostratusRelief(Member m, double ax, double az, Column c, int k) {
        double h = Math.max(m.h, 8);
        double lambda = Math.max(96, ALTO_WAVE * h);
        double u = ax * driftX + az * driftZ;
        double v = -ax * driftZ + az * driftX;
        double patch = lambda * ALTO_PATCH;
        // Where the deck is wavy (1) or a plain veil (0), and where its troughs open up (1) or stay closed (0).
        double wavy = smooth((CloudNoise.gradient3(ax / patch, az / patch, 0.37, seed + 61) + 0.45) / 0.5);
        double open = smooth((CloudNoise.gradient3(ax / patch, az / patch, 3.1, seed + 67) - 0.05) / 0.45);
        double bend = CloudNoise.gradient3(v / (lambda * 4), u / (lambda * 6), 0.5, seed + 71);
        // How far from the nearest roll's middle, in half wavelengths (0 the middle, 1 halfway to the next).
        double phase = u / lambda + 0.6 * bend;
        double d = Math.abs(phase - Math.floor(phase) - 0.5) * 2;
        // Rounded, with a finite slope at its foot (1 - x^4).
        double x2 = Math.min(1, (d / 0.85) * (d / 0.85));
        double roll = 1 - x2 * x2;
        double lump = CloudNoise.gradient3(u / (lambda * 0.6), v / (lambda * 0.8), 2.3, seed + 73);
        roll *= 0.85 + 0.15 * lump;
        // The deck's thickness here, as a share of the waves' full thickness: a thin web between the rolls, gone where
        // the deck opens up.
        double body = Math.max(roll, 0.22 * (1 - open));
        // A thinner layer than the veil: the waves at most 70% of the deck's thickness.
        double waveTop = 0.45 + 0.4 * body, waveBase = 0.45 - 0.3 * body;
        double n = CloudNoise.gradient3(ax / (h * 5), az / (h * 5), 1.3, seed + 79);
        double veilTop = 0.85 + 0.08 * n, veilBase = 0.05 - 0.03 * n;
        c.layerTop[k] = veilTop + (waveTop - veilTop) * wavy;
        c.layerBase[k] = veilBase + (waveBase - veilBase) * wavy;
        c.layerMid[k] = 0.45;
    }

    /**
     * Round mounds on a jittered grid (cell units), 0 (none) to 1 (a mound's centre): each cell has one with chance
     * {@code fill}, a little off-centre, 0.5-0.7 of a cell in radius (times {@code size}), shaped 1 - d² (rounded on
     * top, a finite slope at its foot); where mounds overlap they are merged with a smooth maximum, so the crease
     * between is rounded.
     */
    static double puffs(double u, double v, int seed, double fill, double size) {
        int i0 = (int) Math.floor(u), j0 = (int) Math.floor(v);
        if (debugReference) {
            double best = -1;
            for (int i = i0 - 1; i <= i0 + 1; i++) {
                for (int j = j0 - 1; j <= j0 + 1; j++) {
                    long hsh = hash(i, j, seed);
                    if (unit(hsh, 1) >= fill) {
                        continue;
                    }
                    double px = i + 0.25 + 0.5 * unit(hsh, 2), py = j + 0.25 + 0.5 * unit(hsh, 3);
                    double r = (0.5 + 0.2 * unit(hsh, 4)) * size;
                    double d2 = ((u - px) * (u - px) + (v - py) * (v - py)) / (r * r);
                    best = smoothMax(best, 1 - d2, PUFF_BLEND);
                }
            }
            return Math.max(0, Math.min(1, best));
        }
        PuffCells cells = PUFF_CELLS.get();
        double best = -1;
        for (int i = i0 - 1; i <= i0 + 1; i++) {
            for (int j = j0 - 1; j <= j0 + 1; j++) {
                int s = cells.slot(i, j, seed);
                if (cells.chance[s] >= fill) {
                    continue;
                }
                double px = cells.px[s], py = cells.py[s];
                double r = cells.radius[s] * size;
                double d2 = ((u - px) * (u - px) + (v - py) * (v - py)) / (r * r);
                best = smoothMax(best, 1 - d2, PUFF_BLEND);
            }
        }
        return Math.max(0, Math.min(1, best));
    }

    /**
     * The mounds of {@link #puffs} by cell, kept per thread (2026-10-08): a layer deck asks for the same cells from
     * hundreds of neighbouring columns, and placing a mound takes five hash mixes, so working each out once took a
     * deck's builds from half puffs to a fraction. Direct-mapped: a cell whose slot is taken is simply worked out
     * again. Holds exactly what was computed inline before.
     */
    static final class PuffCells {
        static final int SIZE = 1 << 12;
        final int[] ci = new int[SIZE];
        final int[] cj = new int[SIZE];
        final int[] cseed = new int[SIZE];
        final boolean[] used = new boolean[SIZE];
        /** Per slot: the draw against the cell's fill, its mound's centre (cell units) and radius before size. */
        final double[] chance = new double[SIZE];
        final double[] px = new double[SIZE];
        final double[] py = new double[SIZE];
        final double[] radius = new double[SIZE];

        int slot(int i, int j, int seed) {
            int h = i * 0x1B873593 ^ j * 0x5BD1E995 ^ seed * 0x27D4EB2F;
            h ^= h >>> 15;
            h *= 0x2C1B3C6D;
            h ^= h >>> 12;
            int s = h & (SIZE - 1);
            if (!used[s] || ci[s] != i || cj[s] != j || cseed[s] != seed) {
                long hsh = hash(i, j, seed);
                chance[s] = unit(hsh, 1);
                px[s] = i + 0.25 + 0.5 * unit(hsh, 2);
                py[s] = j + 0.25 + 0.5 * unit(hsh, 3);
                radius[s] = 0.5 + 0.2 * unit(hsh, 4);
                ci[s] = i;
                cj[s] = j;
                cseed[s] = seed;
                used[s] = true;
            }
            return s;
        }
    }

    private static final ThreadLocal<PuffCells> PUFF_CELLS = ThreadLocal.withInitial(PuffCells::new);

    /** How widely neighbouring puffs are blended (mound heights). */
    static final double PUFF_BLEND = 0.3;

    private static long hash(int i, int j, int seed) {
        long h = i * 0x9E3779B97F4A7C15L ^ j * 0xC2B2AE3D27D4EB4FL ^ seed * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static double unit(long h, int salt) {
        long z = h + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
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

    private static double smooth(double t) {
        double c = clamp01(t);
        return c * c * (3 - 2 * c);
    }
}
