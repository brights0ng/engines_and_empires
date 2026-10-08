package dev.brights0ng.enginesandempires.weather.field;

import dev.brights0ng.enginesandempires.oregen.TerrainProbe;

/**
 * The ground's height from the world's terrain noise, for field cells that may never have been generated. Pure over
 * a {@link TerrainProbe}: scans down in 8-block steps for the first solid block, then refines to the block.
 */
public final class TerrainHeights {

    /** The surface height over (x, z): the top solid block plus one, or {@code minY} if none. */
    public static int surface(TerrainProbe probe, int x, int z, int minY, int maxY) {
        for (int y = maxY; y > minY; y -= 8) {
            if (probe.isSolid(x, y, z)) {
                for (int yy = Math.min(maxY, y + 7); yy >= y; yy--) {
                    if (probe.isSolid(x, yy, z)) {
                        return yy + 1;
                    }
                }
                return y + 1;
            }
        }
        return minY;
    }

    /** Elevation above sea level (0 for seabed and anything lower). */
    public static double elevation(TerrainProbe probe, int x, int z, int minY, int maxY, int seaLevel) {
        return Math.max(0, surface(probe, x, z, minY, maxY) - seaLevel);
    }

    private TerrainHeights() {
    }
}
