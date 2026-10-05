package dev.brights0ng.enginesandempires.weather.cloud;

import java.util.UUID;

/**
 * Cloud heights at real-world proportions, scaled down by {@link #SCALE}. Shared (not client-only): the renderer draws
 * clouds at these heights, and the weather queries (rain under a cloud, lightning out of it) must use the same numbers,
 * so that rendered rain is reported rain.
 *
 * <h2>Why</h2>
 * Project Atmosphere's cloud sizes are Minecraft-sized: its storms are a few hundred blocks tall and its clouds a few
 * hundred wide. Bright's decision (2026-10-03): real proportions at x0.2 for every cloud type. Heights come from the
 * table here; widths are PA's, stretched by {@link #horizontal} so a type's typical cloud has its real width.
 * (Supercells are the exception: their template sizes itself from its height.)
 *
 * <h2>Heights</h2>
 * Each type has a real range of base heights and of tops (or thicknesses), in metres above the ground. Where in each
 * range a formation sits comes from its region id, so every cluster of one formation shares a base and a top, and
 * every player and the server agree. Heights are above {@link #GROUND_Y}, a fixed reference for the ground: a cloud
 * spans too much terrain to follow it.
 *
 * <p>Convective clouds (cumulus, cumulonimbus) grow: their tops rise from 30% to 100% of their full height with their
 * growth ({@link CloudLife}). Supercells don't here: their template grows its own updraft and anvil.
 */
public final class CloudScale {

    /** Blocks per metre. */
    public static final double SCALE = 0.2;

    /**
     * A growing type's thickness when it starts forming, as a share of its full thickness; it builds up to the full
     * thickness over its birth. (Was 0.3: a newborn storm was already hundreds of blocks thick.)
     */
    public static final double FORMING_THICKNESS = 0.12;

    /** The ground the heights are measured from (just above sea level). */
    public static final double GROUND_Y = 64;

    /** The highest any cloud's top can be (a supercell's tallest overshoot, with room to spare). */
    public static final double MAX_TOP_Y = GROUND_Y + 22_000 * SCALE;

    /**
     * One type's real heights, metres above the ground. If {@code topIsThickness}, the top range is a thickness above
     * the base; otherwise it is a height above the ground. {@code radius} is a typical cloud's real radius (metres),
     * {@code paRadius} PA's base radius for the type (blocks, its {@code CloudShapeProfile}).
     */
    record Type(double baseMin, double baseMax, double topMin, double topMax, boolean topIsThickness,
                boolean grows, double radius, double paRadius) {
    }

    private static final Type DEFAULT = new Type(600, 1500, 1000, 2000, true, true, 240, 48);

    static Type type(String typeId) {
        if (typeId == null) {
            return DEFAULT;
        }
        String id = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        return switch (id) {
            case "vapor_cluster" -> new Type(600, 1200, 100, 300, true, false, 150, 34);
            case "cumulus_humilis" -> new Type(600, 1500, 300, 800, true, true, 400, 42);
            case "cumulus_mediocris" -> new Type(600, 1500, 800, 2000, true, true, 700, 56);
            case "cumulus_congestus" -> new Type(600, 1500, 3000, 6000, true, true, 1500, 64);
            case "cumulonimbus_calvus" -> new Type(500, 1500, 8000, 12000, false, true, 4000, 88);
            case "cumulonimbus_capillatus" -> new Type(500, 1500, 10000, 15000, false, true, 6000, 116);
            case "supercell" -> new Type(500, 2000, 15000, 21000, false, false, 740, 148);
            case "stratus_nebulosus" -> new Type(100, 500, 200, 600, true, false, 5000, 180);
            case "stratocumulus" -> new Type(500, 2000, 200, 800, true, false, 3000, 130);
            case "nimbostratus" -> new Type(300, 1500, 3000, 6000, false, false, 15000, 210);
            case "cirrus" -> new Type(6000, 10000, 300, 1500, true, false, 2000, 190);
            default -> DEFAULT;
        };
    }

    /**
     * How much wider than PA sends it a cloud of this type is drawn (and rained from): radii, and offsets within a
     * formation, are multiplied by this. 1 for supercells (their template sizes itself).
     */
    public static double horizontal(String typeId) {
        Type t = type(typeId);
        return Math.max(1, t.radius * SCALE / t.paRadius);
    }

    /** A cloud's base and top, world y. */
    public record Heights(double base, double top) {
    }

    /**
     * The scaled base and top of a cluster of PA type {@code typeId} in region {@code regionId}, at growth
     * {@code growth} (0-1, {@link CloudLife.Phase#growth}).
     */
    public static Heights heights(String typeId, UUID regionId, double growth) {
        Type t = type(typeId);
        long h = regionId == null ? 0 : regionId.getMostSignificantBits() ^ regionId.getLeastSignificantBits();
        double fb = unit(h, 1);
        double ft = unit(h, 2);
        // Most storms sit low in their range of tops; the tallest are rare.
        ft = ft * ft;
        if (typeId != null && typeId.contains("supercell")) {
            // Real supercells: low-precipitation storms have higher bases, unstable ones taller tops.
            StormVariety v = StormVariety.of(regionId);
            fb = Math.max(0, Math.min(1, fb - 0.3 * v.precipitation()));
            ft = Math.max(0, Math.min(1, ft + 0.25 * v.instability()));
        }
        double base = t.baseMin + (t.baseMax - t.baseMin) * fb;
        double top = t.topMin + (t.topMax - t.topMin) * ft;
        double thickness = t.topIsThickness ? top : Math.max(top - base, 200);
        if (t.grows) {
            // A forming cloud starts as a thin layer at its base and builds upward.
            thickness *= FORMING_THICKNESS + (1 - FORMING_THICKNESS) * Math.max(0, Math.min(1, growth));
        }
        double baseY = GROUND_Y + base * SCALE;
        return new Heights(baseY, baseY + Math.max(thickness * SCALE, 4));
    }

    /** A number in [0, 1) from {@code h} and {@code salt}. */
    private static double unit(long h, int salt) {
        long z = h + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    private CloudScale() {
    }
}
