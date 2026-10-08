package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudDrift;

/**
 * One cloud (one dome of a formation) at a moment: what the renderer, the rain model and the fog read. Built from the
 * simulation's clouds ({@code weather/cloud/sim/SimCloud#shapes}) on the server, and from the synced copies on the
 * client, so both sides agree.
 *
 * <p>Positions are world blocks. {@code vx, vz} is the velocity in blocks per tick at {@code simulationTick}, the game
 * time the centre was taken at; from there it eases into {@code tvx, tvz} ({@link CloudDrift}), so the centre at time
 * {@code t} is {@link #xAt}.
 * Radii, heights and offsets are as drawn (real sizes at x0.2): nothing stretches them further.
 *
 * @param id             this dome's id
 * @param regionId       its formation's id: a formation's domes are one cloud, spawned, moved and meshed together
 * @param dimension      the dimension id, e.g. {@code minecraft:overworld}
 * @param radius         horizontal radius, blocks
 * @param baseY          cloud base height
 * @param topY           cloud top height (as grown so far)
 * @param density        0-1
 * @param coverage       0-1, how solid it is (lower: broken, ragged)
 * @param edgeSoftness   0-1, how ragged and soft the edge is
 * @param growth         0-1, how far the cloud has formed ({@code CloudLife})
 * @param decay          0-1, how far its body has eroded away ({@code CloudLife})
 * @param anvilDecay     0-1, how far its anvil has thinned away ({@code CloudLife}; equals {@code decay} for clouds
 *                       without a lingering anvil)
 * @param typeId         its {@code CloudType} id
 * @param towerStrength  0-1, how much the middle towers up (cumulus, cumulonimbus)
 * @param anvilStrength  0-1, how far a spreading anvil reaches at the top
 * @param baseDarkness   0-1, how much darker the base is than the top
 * @param stormDarkness  0-1, how dark the whole cloud is (storms)
 * @param precipitation  0-1, how hard the simulation has it raining (0 = dry)
 * @param lightning      0-1, how much lightning it makes
 * @param seed           per-dome seed for its shape
 * @param rainBottom     world y below which its rain has evaporated (virga); negative infinity when it reaches the
 *                       ground
 * @param tvx            the velocity it is easing into, blocks per tick
 * @param tvz            the velocity it is easing into, blocks per tick
 */
public record CloudShape(UUID id, UUID regionId, String dimension,
                         double cx, double cz, double vx, double vz, long simulationTick,
                         float radius, float baseY, float topY,
                         float density, float coverage, float edgeSoftness,
                         float growth, float decay, float anvilDecay,
                         String typeId, float towerStrength, float anvilStrength,
                         float baseDarkness, float stormDarkness,
                         float precipitation, float lightning, int seed, float rainBottom, double tvx, double tvz) {

    /** Moving steadily (no change of velocity under way). */
    public CloudShape(UUID id, UUID regionId, String dimension,
                      double cx, double cz, double vx, double vz, long simulationTick,
                      float radius, float baseY, float topY,
                      float density, float coverage, float edgeSoftness,
                      float growth, float decay, float anvilDecay,
                      String typeId, float towerStrength, float anvilStrength,
                      float baseDarkness, float stormDarkness,
                      float precipitation, float lightning, int seed, float rainBottom) {
        this(id, regionId, dimension, cx, cz, vx, vz, simulationTick, radius, baseY, topY, density, coverage,
                edgeSoftness, growth, decay, anvilDecay, typeId, towerStrength, anvilStrength, baseDarkness,
                stormDarkness, precipitation, lightning, seed, rainBottom, vx, vz);
    }

    /** With rain that reaches the ground (no virga). */
    public CloudShape(UUID id, UUID regionId, String dimension,
                      double cx, double cz, double vx, double vz, long simulationTick,
                      float radius, float baseY, float topY,
                      float density, float coverage, float edgeSoftness,
                      float growth, float decay, float anvilDecay,
                      String typeId, float towerStrength, float anvilStrength,
                      float baseDarkness, float stormDarkness,
                      float precipitation, float lightning, int seed) {
        this(id, regionId, dimension, cx, cz, vx, vz, simulationTick, radius, baseY, topY, density, coverage,
                edgeSoftness, growth, decay, anvilDecay, typeId, towerStrength, anvilStrength, baseDarkness,
                stormDarkness, precipitation, lightning, seed, Float.NEGATIVE_INFINITY);
    }

    /** The body's lifecycle factor: formed, and not yet eroded. */
    public float lifecycle() {
        return clamp01(growth * (1 - decay));
    }

    /** Whether anything of the cloud is left to draw (its body, or its lingering anvil). */
    public boolean visible() {
        return radius > 0 && coverage * growth * (1 - anvilDecay) >= 0.02f;
    }

    /** Coverage after the body's lifecycle. */
    public float effectiveCoverage() {
        return clamp01(coverage * lifecycle());
    }

    /** Density after the lifecycle. */
    public float effectiveDensity() {
        return clamp01(density * lifecycle());
    }

    /** Centre x at client game time {@code t} (ticks, partial ticks allowed), moved along its (easing) velocity. */
    public double xAt(double t) {
        return cx + CloudDrift.offset(vx, tvx, t - simulationTick);
    }

    public double zAt(double t) {
        return cz + CloudDrift.offset(vz, tvz, t - simulationTick);
    }

    /** Velocity at game time {@code t}, blocks per tick. */
    public double vxAt(double t) {
        return CloudDrift.velocity(vx, tvx, t - simulationTick);
    }

    public double vzAt(double t) {
        return CloudDrift.velocity(vz, tvz, t - simulationTick);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(1, v);
    }
}
