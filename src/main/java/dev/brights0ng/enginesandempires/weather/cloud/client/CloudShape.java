package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.UUID;

/**
 * One cloud as Project Atmosphere last sent it to this client: our own copy of PA's {@code CloudRegionRenderData},
 * holding only what the cloud renderer and its debug view use.
 *
 * <p>Positions are world blocks. {@code vx, vy, vz} is PA's velocity in blocks per tick: PA's motion controller adds it
 * to the centre once per game tick. {@code simulationTick} is the server game time the centre was taken at, so the
 * centre at time {@code t} is {@code centre + velocity * (t - simulationTick)}.
 *
 * @param id             PA's cluster id (a region can send several clusters), or the region id if there is none
 * @param regionId       PA's region id. A region's clusters are one formation: PA spawns them together and moves them
 *                       as a rigid group, so the renderer meshes them together.
 * @param dimension      the dimension id, e.g. {@code minecraft:overworld}
 * @param radius         horizontal radius, blocks
 * @param baseY          cloud base height
 * @param topY           cloud top height
 * @param density        0-1, before PA's type multiplier and lifecycle
 * @param coverage       0-1, before PA's type multiplier and lifecycle
 * @param edgeSoftness   0-1, how ragged and soft the edge is
 * @param growth         0-1, how far the cloud has formed ({@code CloudLife}, from PA's age and lifetime)
 * @param decay          0-1, how far its body has eroded away ({@code CloudLife})
 * @param typeId         PA's cloud type id
 * @param towerStrength  0-1, how much the middle towers up (cumulus, cumulonimbus)
 * @param anvilStrength  0-1, how far a spreading anvil reaches at the top
 * @param heightSquash   PA's vertical squash for the type (1 = none)
 * @param baseDarkness   0-1, how much darker the base is than the top
 * @param stormTier      PA's storm visual tier name (CLEAR, CLOUDY, RAIN_CORE, THUNDER_CORE, SEVERE_CORE, CYCLONE_CORE)
 * @param stormDarkness  0-1, that tier's darkness
 * @param precipitation  0-1, PA's precipitation core strength (0 = doesn't rain)
 * @param lightning      0-1, PA's lightning influence
 * @param seed           PA's per-cloud seed
 * @param anvilDecay     0-1, how far its anvil has thinned away ({@code CloudLife}; equals {@code decay} for clouds
 *                       without a lingering anvil)
 * @param dispX          how far the cluster is drawn from PA's centre for it, x (blocks): its offset in its original
 *                       group stretched to real width. NaN when unknown (shapes built by hand):
 *                       then it is stretched about its formation's anchor, as before.
 * @param dispZ          the same, z
 * @param spread         how much wider than PA's radius it is drawn ({@code CloudScale.horizontal}, eased when its type
 *                       changes); NaN when unknown (then the type's)
 */
public record CloudShape(UUID id, UUID regionId, String dimension,
                         double cx, double cy, double cz, double vx, double vy, double vz, long simulationTick,
                         float radius, float baseY, float topY,
                         float density, float coverage, float edgeSoftness, float growth, float decay,
                         float densityMultiplier, float coverageMultiplier,
                         String typeId, float towerStrength, float anvilStrength, float heightSquash,
                         float baseDarkness, String stormTier, float stormDarkness,
                         float precipitation, float lightning, int seed, float anvilDecay,
                         double dispX, double dispZ, double spread) {

    /** Without a layout (as PA sends it, or built by hand). */
    public CloudShape(UUID id, UUID regionId, String dimension,
                      double cx, double cy, double cz, double vx, double vy, double vz, long simulationTick,
                      float radius, float baseY, float topY,
                      float density, float coverage, float edgeSoftness, float growth, float decay,
                      float densityMultiplier, float coverageMultiplier,
                      String typeId, float towerStrength, float anvilStrength, float heightSquash,
                      float baseDarkness, String stormTier, float stormDarkness,
                      float precipitation, float lightning, int seed, float anvilDecay) {
        this(id, regionId, dimension, cx, cy, cz, vx, vy, vz, simulationTick, radius, baseY, topY, density, coverage,
                edgeSoftness, growth, decay, densityMultiplier, coverageMultiplier, typeId, towerStrength, anvilStrength,
                heightSquash, baseDarkness, stormTier, stormDarkness, precipitation, lightning, seed, anvilDecay,
                Double.NaN, Double.NaN, Double.NaN);
    }

    /**
     * Without an anvil decay (tests that build shapes by hand): the anvil fades over the last 40% of the decay, as
     * storms did before lifespans had a linger.
     */
    public CloudShape(UUID id, UUID regionId, String dimension,
                      double cx, double cy, double cz, double vx, double vy, double vz, long simulationTick,
                      float radius, float baseY, float topY,
                      float density, float coverage, float edgeSoftness, float growth, float decay,
                      float densityMultiplier, float coverageMultiplier,
                      String typeId, float towerStrength, float anvilStrength, float heightSquash,
                      float baseDarkness, String stormTier, float stormDarkness,
                      float precipitation, float lightning, int seed) {
        this(id, regionId, dimension, cx, cy, cz, vx, vy, vz, simulationTick, radius, baseY, topY, density, coverage,
                edgeSoftness, growth, decay, densityMultiplier, coverageMultiplier, typeId, towerStrength, anvilStrength,
                heightSquash, baseDarkness, stormTier, stormDarkness, precipitation, lightning, seed,
                clamp01((decay - 0.6f) / 0.4f));
    }

    /** The body's lifecycle factor: formed, and not yet eroded. */
    public float lifecycle() {
        return clamp01(growth * (1 - decay));
    }

    /** Whether anything of the cloud is left to draw (its body, or its lingering anvil). */
    public boolean visible() {
        return radius > 0 && coverage * coverageMultiplier * growth * (1 - anvilDecay) >= 0.02f;
    }

    /** Coverage after PA's type multiplier and the body's lifecycle. */
    public float effectiveCoverage() {
        return clamp01(coverage * coverageMultiplier * lifecycle());
    }

    /** Density after PA's type multiplier and lifecycle. */
    public float effectiveDensity() {
        return clamp01(density * densityMultiplier * lifecycle());
    }

    /** Centre x at client game time {@code t} (ticks, partial ticks allowed), moved along PA's velocity. */
    public double xAt(double t) {
        return cx + vx * (t - simulationTick);
    }

    public double yAt(double t) {
        return cy + vy * (t - simulationTick);
    }

    public double zAt(double t) {
        return cz + vz * (t - simulationTick);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    /** This cluster further along its life: decay and anvil decay at least these (a dissolving ghost). */
    public CloudShape withLife(float minDecay, float minAnvilDecay) {
        return new CloudShape(id, regionId, dimension, cx, cy, cz, vx, vy, vz, simulationTick, radius, baseY, topY,
                density, coverage, edgeSoftness, growth, Math.max(decay, minDecay), densityMultiplier,
                coverageMultiplier, typeId, towerStrength, anvilStrength, heightSquash, baseDarkness, stormTier,
                stormDarkness, precipitation, lightning, seed, Math.max(anvilDecay, minAnvilDecay), dispX, dispZ, spread);
    }

    /** This cluster laid out: drawn offset, width scale, and (eased) radius and heights. */
    public CloudShape withLayout(double dispX, double dispZ, double spread, float radius, float baseY, float topY) {
        return new CloudShape(id, regionId, dimension, cx, cy, cz, vx, vy, vz, simulationTick, radius, baseY, topY,
                density, coverage, edgeSoftness, growth, decay, densityMultiplier, coverageMultiplier, typeId,
                towerStrength, anvilStrength, heightSquash, baseDarkness, stormTier, stormDarkness, precipitation,
                lightning, seed, anvilDecay, dispX, dispZ, spread);
    }

    /** Whether it has a layout ({@link #withLayout}). */
    public boolean laidOut() {
        return Double.isFinite(dispX) && Double.isFinite(dispZ);
    }
}
