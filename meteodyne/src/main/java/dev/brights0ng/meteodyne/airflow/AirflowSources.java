// Written by Claude (GreedyFluids M7b b3); edited by Claude (M7c c3: real-world watts, walls, limits, drivers).
package dev.brights0ng.meteodyne.airflow;

import java.util.List;

import dev.brights0ng.meteodyne.heat.world.HeatDrivers;
import dev.brights0ng.meteodyne.heat.world.HeatPowerData;
import dev.brights0ng.meteodyne.heat.world.HeatTables;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block puts into GreedyFluids air (M7c c3; Bright, 2026-11-15: real-world watts), from its
 * {@code meteodyne:heat_power} variant:
 * <ul>
 *   <li><b>Watts:</b> the variant's {@code watts} (the heat into the air, W); absent, {@link #WATTS_PER_POWER} ×
 *       {@code power} × (1 − {@code radiant}). A variant whose block is a hot wall gives none.</li>
 *   <li><b>Driven</b> variants (on by their block entity) count only through {@link #driven}, polled once a second.</li>
 *   <li><b>Walls:</b> {@code wall_temperature}, C: the block's cell is fixed at it (lava, magma; {@link AirflowCells}).</li>
 *   <li><b>Limits:</b> an absolute {@code limit}, C, makes the source a thermostat (the model switches it off past it).
 *       {@code max_offset}, the legacy model's cap, isn't used.</li>
 * </ul>
 * Server thread (reads the heat tables and block entities).
 */
public final class AirflowSources {

    /** Watts per unit of a variant's {@code power} without its own {@code watts}: a campfire's 10 kW into the air. */
    public static final double WATTS_PER_POWER = 10_000;
    /** Body heat per player, W (Bright, 2026-11-15). */
    public static final double PLAYER_WATTS = 500;

    /** A source's power, W (negative cools), and its absolute limit, C (NaN for none). */
    public record Power(double watts, double limit) {
        public static final Power NONE = new Power(0, Double.NaN);

        public boolean isNone() {
            return watts == 0;
        }
    }

    /** The air power of a variant, W. */
    public static double watts(HeatPowerData.Variant v) {
        if (v == null || Double.isFinite(v.wallTemperature()) && Double.isNaN(v.watts())) {
            return 0;
        }
        if (!Double.isNaN(v.watts())) {
            return v.watts();
        }
        return v.inAir() ? WATTS_PER_POWER * v.power() * (1 - v.radiant()) : 0;
    }

    /** What {@code state} puts into the air by its state alone (driven variants excluded). */
    public static Power power(BlockState state) {
        HeatPowerData.Variant v = HeatTables.power(state);
        if (v == null || v.driven()) {
            return Power.NONE;
        }
        double w = watts(v);
        return w == 0 ? Power.NONE : new Power(w, v.limit());
    }

    /** The air power of {@code state} by its state alone, W. */
    public static double watts(BlockState state) {
        return power(state).watts();
    }

    /** Whether {@code state} has driven variants (its power depends on its block entity). */
    public static boolean driven(BlockState state) {
        return !HeatTables.driven(state).isEmpty();
    }

    /**
     * What a driven block puts in now: every driven variant that fits, at its driver's share. The limit is the first
     * one any of them has.
     */
    public static Power driven(ServerLevel level, BlockPos pos, BlockState state) {
        List<HeatPowerData.Variant> variants = HeatTables.driven(state);
        double w = 0, limit = Double.NaN;
        for (HeatPowerData.Variant v : variants) {
            double share = HeatDrivers.share(level, pos, state, v);
            if (share > 0) {
                w += watts(v) * share;
                if (Double.isNaN(limit)) {
                    limit = v.limit();
                }
            }
        }
        return w == 0 ? Power.NONE : new Power(w, limit);
    }

    /** The fixed wall temperature of {@code state}'s cell, C, or NaN. */
    public static double wall(BlockState state) {
        HeatPowerData.Variant v = HeatTables.power(state);
        return v == null ? Double.NaN : v.wallTemperature();
    }

    private AirflowSources() {
    }
}
