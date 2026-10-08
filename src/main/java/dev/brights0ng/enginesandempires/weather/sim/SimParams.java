package dev.brights0ng.enginesandempires.weather.sim;

/**
 * The weather systems' settings (phase 2 of {@code claude/weather-backbone-plan.md}), from the server config. Pure.
 *
 * @param bands       whether the climate bands are on (mirrored zones, seasonal track shift); else biome mode
 * @param bandPeriod  blocks from one coldest band to the next; storm tracks lie every half period
 * @param speed       how fast systems are steered along the jet at its core, blocks per second, before seasons
 * @param spacing     blocks between successive lows along a storm track, before seasons
 * @param zoneRadius  blocks around each player kept supplied with weather systems
 * @param jetCore     the aloft wind at the jet's core, m/s, before seasons
 * @param blockChance the chance a new high blocks, before seasons
 */
public record SimParams(boolean bands, int bandPeriod, double speed, double spacing, double zoneRadius, double jetCore,
                        double blockChance) {

    /** Jet core 8 m/s (was 15; halved 2026-10-06 with the systems' wind aloft, so clouds drift at a realistic pace). */
    public static final SimParams DEFAULT = new SimParams(true, 64000, 2.5, 12000, 16000, 8, 0.06);

    /** Blocks between neighbouring storm tracks. */
    public double trackSpacing() {
        return bandPeriod / 2.0;
    }

    /**
     * How the seasons scale the weather systems (Bright, 2026-10-05: realistic). {@code s} is the season factor, -1
     * midwinter to +1 midsummer. Winter: a stronger, faster jet, more frequent and deeper lows; summer the reverse.
     */
    public record Seasonal(double speed, double jet, double spacing, double depth, double blockChance) {

        public static Seasonal of(double s, double baseBlockChance) {
            double season = Double.isFinite(s) ? Math.max(-1, Math.min(1, s)) : 0;
            return new Seasonal(
                    0.9 - 0.3 * season,      // winter 1.2, summer 0.6
                    1.0 - 0.3 * season,      // winter 1.3, summer 0.7
                    1.1 + 0.3 * season,      // winter 0.8 (more lows), summer 1.4 (fewer)
                    1.0 - 0.3 * season,      // winter 1.3, summer 0.7
                    baseBlockChance * (1 + 0.7 * Math.abs(season)));
        }
    }
}
