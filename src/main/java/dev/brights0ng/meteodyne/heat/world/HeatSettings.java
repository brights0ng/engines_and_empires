package dev.brights0ng.meteodyne.heat.world;

/**
 * The heat simulation's server settings (weather phase 8f-2; {@code [heat]} in the weather server config).
 *
 * @param enabled      whether it runs
 * @param budgetCores  CPU cores it may use on average
 * @param threads      worker threads
 * @param nearStep     seconds between steps of a region a player or ship is near
 * @param farStep      seconds between steps of other regions (Bright, 2026-10-15: 10)
 * @param naturalReach blocks from a player or ship within which natural sources take part (Bright: about 32)
 * @param margin       blocks simulated around a group of sources
 * @param maxSize      most blocks a region spans along an axis
 */
public record HeatSettings(boolean enabled, double budgetCores, int threads, double nearStep, double farStep,
                           int naturalReach, int margin, int maxSize) {

    public static final HeatSettings DEFAULT = new HeatSettings(true, 0.25, 1, 1.0, 10.0, 32, 12, 48);
}
