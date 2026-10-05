package dev.brights0ng.enginesandempires.weather.cloud;

import java.util.SplittableRandom;
import java.util.UUID;

/**
 * The environment a storm grew in, which is most of why real supercells look so different from each other. Three
 * numbers per storm, drawn from its region id (so every client and the server agree), each centred on 0 (the
 * average, "classic" storm) with a standard deviation of 0.5 and clamped to [-1, 1]:
 *
 * <ul>
 *   <li><b>precipitation</b>: the low- to high-precipitation spectrum. Low (LP, -1): a slim, sculpted, strongly
 *       striated updraft on a high base, with little forward flank and no real shelf. High (HP, +1): a broad storm on a
 *       low base with a huge, dark, rain-filled forward flank, a big shelf cloud and a large wall cloud.</li>
 *   <li><b>shear</b>: how much the wind changes with height. More shear: a more tilted tower, a longer anvil and a
 *       longer flanking line.</li>
 *   <li><b>instability</b>: how violently the air rises. More: a wider, bulgier tower, a taller overshooting top, a
 *       thicker anvil pushing further upwind, taller flanking towers, more mammatus, and a higher top.</li>
 * </ul>
 *
 * Shared code: {@link CloudScale} uses it for heights (LP bases are higher, unstable storms taller), and the renderer
 * for shapes. The three are independent of each other, as their real causes mostly are.
 */
public record StormVariety(double precipitation, double shear, double instability) {

    public static final StormVariety AVERAGE = new StormVariety(0, 0, 0);

    public static StormVariety of(UUID regionId) {
        if (regionId == null) {
            return AVERAGE;
        }
        SplittableRandom rng = random(regionId, 0x5EED);
        return new StormVariety(draw(rng), draw(rng), draw(rng));
    }

    /** A random generator for further per-storm detail, from the region id and a salt. */
    public static SplittableRandom random(UUID regionId, long salt) {
        long h = regionId.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ regionId.getLeastSignificantBits();
        return new SplittableRandom(h ^ salt * 0xD1B54A32D192ED03L);
    }

    /** A normal draw with standard deviation 0.5, clamped to [-1, 1]. */
    private static double draw(SplittableRandom rng) {
        double u1 = Math.max(1e-12, rng.nextDouble());
        double u2 = rng.nextDouble();
        double n = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        return Math.max(-1, Math.min(1, 0.5 * n));
    }
}
