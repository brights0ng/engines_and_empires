package dev.brights0ng.enginesandempires.weather.cloud.client;

import dev.brights0ng.enginesandempires.weather.fog.FogTuning;

/**
 * Whether the camera is inside a cloud, for the in-cloud fog: inside means in a voxel the drawn mesh has solid, so the
 * fog snaps on exactly as the camera passes through the cloud's surface (Bright, 2026-10-04: hard on, hard off).
 *
 * <p>Each formation is tested against the generation it is drawn with ({@link CloudMeshes.Formation#liveFrom}, its
 * field and game time), at that section's voxel size, with {@link CloudVoxelizer#voxelDensity}: the smooth field
 * itself can differ from the voxels by half a voxel, nearly all of a 5-block visibility. Render thread.
 */
public final class CloudInterior {

    /**
     * The cloud the camera is in.
     *
     * @param visibility how far one can see, blocks
     * @param red        the fog colour inside it (0-1, before the sky's light)
     */
    public record Inside(double visibility, float red, float green, float blue, String type) {
    }

    /** The face shades averaged: inside a cloud the light comes from every side. */
    private static final double INTERIOR_SHADE = 0.86;

    /**
     * The cloud around the camera at world (x, y, z), at {@code time} (game ticks plus the partial tick), or null. If
     * clouds overlap, the one hardest to see through.
     */
    public static Inside at(double x, double y, double z, double time) {
        Inside best = null;
        for (CloudMeshes.Formation fe : CloudMeshes.live()) {
            double ax = CloudTracker.drawnX(fe.liveFrame, time);
            double az = CloudTracker.drawnZ(fe.liveFrame, time);
            if (!Double.isFinite(ax) || !Double.isFinite(az)) {
                continue;
            }
            double lx = x - ax;
            double lz = z - az;
            int size = fe.liveSectionSize;
            int sx = Math.floorDiv((int) Math.floor(lx), size);
            int sy = Math.floorDiv((int) Math.floor(y), size);
            int sz = Math.floorDiv((int) Math.floor(lz), size);
            Integer voxel = fe.liveVoxels.get(CloudMeshes.key(sx, sy, sz));
            if (voxel == null) {
                // That section was never planned, or was culled as empty sky: no cloud is drawn there.
                continue;
            }
            CloudField field = fe.liveField;
            if (CloudVoxelizer.voxelDensity(field, voxel, fe.liveTime, sx, sy, sz, size, lx, y, lz) <= 0) {
                continue;
            }
            String type = fe.liveFrom.anchor().typeId();
            double visibility = FogTuning.cloudVisibility(type, thinness(fe.liveFrom));
            if (visibility <= 0 || (best != null && best.visibility() <= visibility)) {
                continue;
            }
            // The same shading as the cloud's faces: by how much cloud is above the camera.
            double above = field.profile(lx, lz, fe.liveTime, 8, field.newColumn()).depthAbove(y);
            double grey = CloudVoxelizer.brightness(above, field.water) * INTERIOR_SHADE;
            best = new Inside(visibility, (float) (grey * 0.96), (float) (grey * 0.97), (float) grey, type);
        }
        return best;
    }

    /**
     * How unformed or eroded formation {@code f} is: 0 fully formed, 1 barely there. Follows the same curves as the
     * field's erosion ({@link CloudField}): birth as {@code (1 - growth)^2}, death as {@code decay^3.4}, so the fog
     * thins as the cloud visibly does, not before. Lifecycle averaged over its visible clusters.
     */
    static double thinness(CloudFormation f) {
        double growth = 0;
        double decay = 0;
        int n = 0;
        for (CloudShape c : f.members()) {
            if (c.visible()) {
                growth += c.growth();
                decay += c.decay();
                n++;
            }
        }
        if (n == 0) {
            return 1;
        }
        growth /= n;
        decay /= n;
        double unformed = Math.max(0, Math.min(1, 1 - growth));
        double dying = Math.max(0, Math.min(1, decay));
        return Math.max(unformed * unformed, Math.pow(dying, CloudField.DEATH_POWER));
    }

    private CloudInterior() {
    }
}
