package dev.brights0ng.enginesandempires.weather.cloud.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudDrift;
import dev.brights0ng.enginesandempires.weather.cloud.CloudLife;
import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;

/**
 * One cloud the simulation keeps (a formation of domes, phase 4a of {@code claude/weather-backbone-plan.md}). Pure and
 * shared: the server owns them, and clients keep synced copies; both turn them into {@link CloudShape}s at a given
 * game time with {@link #shapes}, so they agree.
 *
 * <ul>
 *   <li><b>Motion:</b> the anchor was at ({@link #x}, {@link #z}) at {@link #refTick}, moving at ({@link #vx},
 *       {@link #vz}) blocks per tick, and eases into ({@link #tvx}, {@link #tvz}) over {@link CloudDrift#EASE_TICKS}
 *       ({@link CloudDrift}); the spawner sets that target from the wind.</li>
 *   <li><b>Life:</b> born at {@link #birth}, gone at {@link #end} (game ticks); where it is in its life comes from
 *       {@link CloudLife#phase}. A heap cloud's end is its lifespan; a layer cloud's end is pushed on while its
 *       conditions hold and pulled in when they go.</li>
 *   <li><b>Heights:</b> a fixed base and a full thickness; growing types build their top up over their birth
 *       ({@link CloudScale#heights(CloudType, double, double, double)}).</li>
 *   <li><b>{@link #version}</b> goes up whenever something clients need changes (velocity, end, type), which is how the
 *       sync knows what to resend.</li>
 * </ul>
 */
public final class SimCloud {

    /** One dome: offset from the anchor and radius (blocks), its own seed, and its share of the type's tower. */
    public record Dome(float dx, float dz, float radius, int seed, float tower) {
    }

    public final UUID id;
    public CloudType type;
    public double x;
    public double z;
    public double vx;
    public double vz;
    /** The velocity it is easing into, blocks per tick. */
    public double tvx;
    public double tvz;
    public long refTick;
    public long birth;
    public long end;
    public float baseY;
    public float thickness;
    /** 0-1, how solid it is (layer clouds: thinner where their conditions are weak). */
    public float coverage;
    /** 0-1, how hard the simulation has it raining (0 in phase 4a). */
    public float precipitation;
    public float lightning;
    /** World y below which its rain has evaporated (virga); negative infinity when it reaches the ground. */
    public float rainBottom = Float.NEGATIVE_INFINITY;
    public List<Dome> domes;
    public int version;
    /** Whether the spawner has started it dissolving (its conditions have gone, or it has drifted away). */
    public boolean dissolving;
    /** When it last told clients its position, game ticks (server only, not synced or saved). */
    public long lastSent;
    /** Spawned by hand (debug): lives out its set lifetime whatever the conditions (server only, not saved). */
    public boolean manual;
    /** Debug and tests: rains this hard (0-1) whatever the air, reaching the ground; negative for the air's call. */
    public float forcedRain = -1;
    /** What made it (the audit; server only, not saved or synced). */
    public String cause = "loaded";

    private transient CloudLife.Span span;
    private transient CloudType spanType;

    public SimCloud(UUID id, CloudType type, double x, double z, double vx, double vz, long refTick, long birth, long end,
                    float baseY, float thickness, float coverage, float precipitation, float lightning,
                    List<Dome> domes, int version, boolean dissolving) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.z = z;
        this.vx = vx;
        this.vz = vz;
        this.tvx = vx;
        this.tvz = vz;
        this.refTick = refTick;
        this.birth = birth;
        this.end = end;
        this.baseY = baseY;
        this.thickness = thickness;
        this.coverage = coverage;
        this.precipitation = precipitation;
        this.lightning = lightning;
        this.domes = domes;
        this.version = version;
        this.dissolving = dissolving;
    }

    /** Its type's lifespan for its id. */
    public CloudLife.Span span() {
        if (span == null || spanType != type) {
            span = CloudLife.span(type, id);
            spanType = type;
        }
        return span;
    }

    /** Where it is in its life at game time {@code now}. */
    public CloudLife.Phase phase(double now) {
        return CloudLife.phase(span(), now - birth, end - birth);
    }

    public double xAt(double t) {
        return x + CloudDrift.offset(vx, tvx, t - refTick);
    }

    public double zAt(double t) {
        return z + CloudDrift.offset(vz, tvz, t - refTick);
    }

    /** The velocity at {@code t}, blocks per tick. */
    public double vxAt(double t) {
        return CloudDrift.velocity(vx, tvx, t - refTick);
    }

    public double vzAt(double t) {
        return CloudDrift.velocity(vz, tvz, t - refTick);
    }

    /**
     * Moves the reference point to {@code now}: its position and velocity there, still easing toward the same target
     * (from now). Clients must be told ({@link #version}), or they would follow the old curve.
     */
    public void advance(long now) {
        double nx = xAt(now), nz = zAt(now), nvx = vxAt(now), nvz = vzAt(now);
        x = nx;
        z = nz;
        vx = nvx;
        vz = nvz;
        refTick = now;
    }

    /** The widest any part of it reaches from the anchor, blocks. */
    public double reach() {
        double r = 0;
        for (Dome d : domes) {
            r = Math.max(r, Math.hypot(d.dx(), d.dz()) + d.radius());
        }
        return r * (1 + 1.2 * type.look.anvil());
    }

    /** Whether it is gone (past its end) at {@code now}. */
    public boolean over(long now) {
        return now >= end;
    }

    /** Its domes as {@link CloudShape}s in {@code dimension} at game time {@code now} (none once invisible). */
    public List<CloudShape> shapes(String dimension, double now) {
        CloudLife.Phase p = phase(now);
        if (!p.visible()) {
            return List.of();
        }
        CloudScale.Heights h = CloudScale.heights(type, baseY, thickness, p.growth());
        CloudType.Look look = type.look;
        List<CloudShape> out = new ArrayList<>(domes.size());
        for (int i = 0; i < domes.size(); i++) {
            Dome d = domes.get(i);
            // Positions as of the reference tick; the shape moves itself along the velocity from there.
            out.add(new CloudShape(domeId(i), id, dimension, x + d.dx(), z + d.dz(), vx, vz, refTick, d.radius(),
                    (float) h.base(), (float) h.top(), look.density(), coverage, look.edgeSoftness(),
                    (float) p.growth(), (float) p.decay(), (float) p.anvilDecay(), type.id, look.tower() * d.tower(),
                    look.anvil(), look.baseDarkness(), look.stormDarkness(), precipitation, lightning, d.seed(),
                    rainBottom, tvx, tvz));
        }
        return out;
    }

    /** Dome {@code i}'s id: derived from the cloud's, so it stays the same on every side. */
    public UUID domeId(int i) {
        return new UUID(id.getMostSignificantBits() ^ (0x9E3779B97F4A7C15L * (i + 1)), id.getLeastSignificantBits());
    }

    // ---- making domes ---------------------------------------------------------------------------------------------

    /**
     * Domes for a new cloud of type {@code t}: heap clouds are one main tower with up to two smaller turrets beside it
     * (cumulonimbus always two flanking towers); layer clouds a patch of three to five overlapping domes filling about
     * {@code patchRadius} blocks.
     */
    public static List<Dome> domesFor(CloudType t, double patchRadius, SplittableRandom rng) {
        return domesFor(t, patchRadius, rng, t.heap() ? heapSize(t, rng) : 1);
    }

    /**
     * The smallest and largest cumulus, as a share of its type's typical radius (Bright, 2026-10-07: wider both ways;
     * was 0.4-1.8).
     */
    static final double SIZE_MIN = 0.25;
    static final double SIZE_MAX = 2.5;

    /**
     * A new heap cloud's size, as a share of its type's typical radius (2026-10-06, Bright: clouds all looked the same
     * size). Real cumulus fields hold many small clouds and few big ones, roughly a number per size falling as
     * size^-2: drawn from that between {@link #SIZE_MIN} and {@link #SIZE_MAX} (median about 0.45, mean area about
     * 0.63 of the typical cloud's; about 8% above 1.5). Cumulonimbus vary less (0.85-1.2).
     */
    public static double heapSize(CloudType t, SplittableRandom rng) {
        double u = rng.nextDouble();
        if (t == CloudType.CUMULONIMBUS_CALVUS || t == CloudType.CUMULONIMBUS_CAPILLATUS) {
            return 0.85 + 0.35 * u;
        }
        double a = 1 / SIZE_MIN;
        double b = 1 / SIZE_MAX;
        return 1 / (a - u * (a - b));
    }

    /**
     * A heap cloud's thickness for its size {@code size} (see {@link #heapSize}): the type's thickness for the air
     * ({@code typeThickness}, blocks), scaled as size^0.7, so small cumulus are shallow and the big ones of a type
     * stand a little taller for their width than the small ones do.
     */
    public static float heapThickness(double typeThickness, double size) {
        return (float) Math.max(16, typeThickness * Math.pow(Math.max(0.1, size), 0.7));
    }

    /** A heap cloud's size from its domes: the main dome's radius over its type's typical radius. */
    public static double sizeOf(CloudType t, List<Dome> domes) {
        return domes.isEmpty() || !t.heap() ? 1 : domes.getFirst().radius() / t.radiusBlocks();
    }

    /**
     * As {@link #domesFor(CloudType, double, SplittableRandom)}, with a heap cloud's size {@code size} (a share of its
     * type's typical radius; ignored for layer clouds).
     */
    public static List<Dome> domesFor(CloudType t, double patchRadius, SplittableRandom rng, double size) {
        List<Dome> out = new ArrayList<>();
        if (t.heap()) {
            double r = t.radiusBlocks() * size;
            out.add(new Dome(0, 0, (float) r, rng.nextInt(), 1));
            int extra = t == CloudType.CUMULONIMBUS_CALVUS || t == CloudType.CUMULONIMBUS_CAPILLATUS ? 2
                    : rng.nextInt(3);
            double a0 = rng.nextDouble() * Math.PI * 2;
            for (int i = 0; i < extra; i++) {
                double a = a0 + (i + 0.5) * Math.PI * (0.6 + 0.3 * rng.nextDouble());
                double off = r * (0.7 + 0.35 * rng.nextDouble());
                double rr = r * (0.5 + 0.25 * rng.nextDouble());
                out.add(new Dome((float) (Math.cos(a) * off), (float) (Math.sin(a) * off), (float) rr, rng.nextInt(),
                        (float) (0.5 + 0.3 * rng.nextDouble())));
            }
            return out;
        }
        int n = 3 + rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double off = i == 0 ? 0 : patchRadius * (0.25 + 0.3 * rng.nextDouble());
            double rr = patchRadius * (0.55 + 0.2 * rng.nextDouble());
            out.add(new Dome((float) (Math.cos(a) * off), (float) (Math.sin(a) * off), (float) rr, rng.nextInt(), 1));
        }
        return out;
    }

    /** The domes of a cloud that has grown from {@code from} into {@code to}: the same layout, scaled up. */
    public static List<Dome> grown(List<Dome> domes, CloudType from, CloudType to) {
        // At most 2.2x wider at once: a congestus is several times a mediocris's typical width, but one that grows
        // out of a mediocris spreads only so far in a moment.
        double k = Math.min(2.2, to.radiusBlocks() / from.radiusBlocks());
        List<Dome> out = new ArrayList<>(domes.size());
        for (Dome d : domes) {
            out.add(new Dome((float) (d.dx() * k), (float) (d.dz() * k), (float) (d.radius() * k), d.seed(), d.tower()));
        }
        return out;
    }
}
