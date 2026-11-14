// Written by Claude (GreedyFluids M7b b3).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.meteodyne.heat.world.HeatPowerData;
import dev.brights0ng.meteodyne.heat.world.HeatTables;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block puts into GreedyFluids air until part c calibrates heat powers (M7b b3's interim input): the
 * {@code meteodyne:heat_power} variant its state matches, if it is a placed source that heats or cools the air
 * through its state alone. Natural sources (lava, fire, magma) wait for part c, as do driven ones (engines, hearths,
 * burners on by their block entity); thermostat limits ({@code max_offset}, {@code limit}) are not applied.
 *
 * <p>Watts: {@link #CAMPFIRE_WATTS} per unit of power, times the share not felt as radiant warmth (the legacy grid's
 * rule), so a lit campfire puts 45,225 W into its air. Server thread (reads the heat tables).
 */
public final class AirflowSources {

    /** One campfire, W: the GreedyFluids benches' placeholder fire (M5d2). */
    public static final double CAMPFIRE_WATTS = 90_450;

    /** The air power of {@code state}, W (negative cools); 0 for none. */
    public static double watts(BlockState state) {
        HeatPowerData.Variant v = HeatTables.power(state);
        if (v == null || v.driven() || v.natural() || !v.inAir()) {
            return 0;
        }
        return CAMPFIRE_WATTS * v.power() * (1 - v.radiant());
    }

    private AirflowSources() {
    }
}
