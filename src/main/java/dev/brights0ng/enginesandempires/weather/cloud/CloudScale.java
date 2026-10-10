package dev.brights0ng.enginesandempires.weather.cloud;

import java.util.UUID;

/**
 * Cloud heights at real-world proportions, scaled down by {@link #SCALE} (Bright, 2026-10-03: real proportions at x0.2
 * for every cloud type). Shared (not client-only): the renderer draws clouds at these heights, and the weather queries
 * (rain under a cloud, lightning out of it) use the same numbers, so that rendered rain is reported rain.
 *
 * <h2>Heights</h2>
 * Each type ({@link CloudType}) has a real range of base heights and of tops (or thicknesses), metres above the ground.
 * The spawner picks a cloud's base (for low clouds from the air's condensation level) and its full thickness when it
 * spawns it; {@link #heights(CloudType, UUID, double)} picks both from the cloud's id instead (tests, debug spawns).
 * Heights are above {@link #GROUND_Y}, a fixed reference for the ground: a cloud spans too much terrain to follow it.
 * No base is ever below {@link #MIN_BASE_Y} (Bright, 2026-10-08: low stratus and nimbostratus at sea level are
 * unpleasant to play in, realistic or not); a cloud that would sit lower sits there instead, its thickness unchanged.
 * Raised to 120 on 2026-10-10, and made a floor for the whole cloud, not just its base: the renderer cuts every part of
 * every cloud off there ({@code CloudField.FLOOR_Y}).
 *
 * <p>Convective clouds grow: their tops rise from {@link #FORMING_THICKNESS} of their full thickness to all of it over
 * their birth ({@link CloudLife}).
 */
public final class CloudScale {

    /** Blocks per metre. */
    public static final double SCALE = 0.2;

    /** A growing type's thickness when it starts forming, as a share of its full thickness. */
    public static final double FORMING_THICKNESS = 0.12;

    /** The ground the heights are measured from (just above sea level). */
    public static final double GROUND_Y = 64;

    /**
     * The lowest any cloud's base can be, world y, and the lowest any part of a cloud is drawn (Bright, 2026-10-08 at
     * 100; 2026-10-10 raised to 120 and applied to the whole cloud).
     */
    public static final double MIN_BASE_Y = 120;

    /** The highest any cloud's top can be (the tallest cumulonimbus, with room to spare). */
    public static final double MAX_TOP_Y = GROUND_Y + 16_000 * SCALE;

    /** A cloud's base and top, world y. */
    public record Heights(double base, double top) {
    }

    /** World y of a base {@code metres} above the ground, never below {@link #MIN_BASE_Y}. */
    public static double baseY(double metres) {
        return floorBase(GROUND_Y + metres * SCALE);
    }

    /** {@code y} raised to {@link #MIN_BASE_Y} if it is lower (also applied to clouds loaded from older saves). */
    public static double floorBase(double y) {
        return Math.max(MIN_BASE_Y, y);
    }

    /** A type's full thickness for a base at {@code baseMetres}, picked {@code f} (0-1) through its range, blocks. */
    public static double thickness(CloudType t, double baseMetres, double f) {
        double top = t.topMin + (t.topMax - t.topMin) * f;
        double metres = t.topIsThickness ? top : Math.max(top - baseMetres, 200);
        return Math.max(metres * SCALE, 4);
    }

    /**
     * The drawn base and top of a cloud with base {@code baseY} and full thickness {@code thickness} (blocks), at
     * growth {@code growth} (0-1, {}): a growing type starts as a thin layer at its base.
     */
    public static Heights heights(CloudType t, double baseY, double thickness, double growth) {
        double h = thickness;
        if (t != null && t.grows) {
            h *= FORMING_THICKNESS + (1 - FORMING_THICKNESS) * Math.max(0, Math.min(1, growth));
        }
        double base = floorBase(baseY);
        return new Heights(base, base + Math.max(h, 4));
    }

    /**
     * Heights picked from the cloud's id: where in its type's ranges it sits (tests and debug spawns; the spawner uses
     * the air's humidity for low bases). Most tops sit low in their range; the tallest are rare.
     */
    public static Heights heights(CloudType t, UUID regionId, double growth) {
        long h = regionId == null ? 0 : regionId.getMostSignificantBits() ^ regionId.getLeastSignificantBits();
        double fb = unit(h, 1);
        double ft = unit(h, 2);
        ft = ft * ft;
        double base = t.baseMin + (t.baseMax - t.baseMin) * fb;
        return heights(t, baseY(base), thickness(t, base, ft), growth);
    }

    /** {@link #heights(CloudType, UUID, double)} by type id (unknown ids get mid-level cumulus heights). */
    public static Heights heights(String typeId, UUID regionId, double growth) {
        CloudType t = CloudType.of(typeId);
        return heights(t == null ? CloudType.CUMULUS_MEDIOCRIS : t, regionId, growth);
    }

    /** A number in [0, 1) from {@code h} and {@code salt}. */
    public static double unit(long h, int salt) {
        long z = h + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    private CloudScale() {
    }
}
