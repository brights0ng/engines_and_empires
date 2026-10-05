package dev.brights0ng.enginesandempires.frontier.spawn;

import dev.brights0ng.enginesandempires.frontier.Tier;

/**
 * Which natural spawns each tier turns away. (Frontier adds spawns rather than turning them away; see
 * {@link FrontierSpawner}.)
 * <ul>
 *   <li>Civilized: no hostile mobs at all, night or day.</li>
 *   <li>Settled: no creepers, no daytime hunters (polar bears, the Naturalist predators), and only
 *       {@code settledSurfaceMonsters} of the other night-time monsters on the surface.</li>
 *   <li>Uninhabited and Frontier: vanilla.</li>
 * </ul>
 *
 * <p>Nothing here touches Minecraft.
 */
public final class SpawnRules {

    /**
     * Whether a natural spawn is turned away.
     *
     * @param monster        whether the mob is in the MONSTER category
     * @param creeper        whether it is a creeper
     * @param daytimeHostile whether it is in the {@code daytime_hostile} tag
     * @param seesSky        whether it would stand under open sky (the surface)
     * @param roll           a random number from 0 (inclusive) to 1 (exclusive)
     */
    public static boolean denies(Tier tier, boolean monster, boolean creeper, boolean daytimeHostile, boolean seesSky,
                                 double roll, double settledSurfaceMonsters) {
        return switch (tier) {
            case CIVILIZED -> monster || creeper || daytimeHostile;
            case SETTLED -> creeper || daytimeHostile || (monster && seesSky && roll >= settledSurfaceMonsters);
            case UNINHABITED, FRONTIER -> false;
        };
    }

    private SpawnRules() {
    }
}
