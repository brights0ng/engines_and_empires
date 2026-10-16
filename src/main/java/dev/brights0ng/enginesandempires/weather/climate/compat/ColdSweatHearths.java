package dev.brights0ng.enginesandempires.weather.climate.compat;

import com.momosoftworks.coldsweat.common.blockentity.HearthBlockEntity;
import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.enginesandempires.mixin.compat.coldsweat.HearthFuelAccessor;
import dev.brights0ng.meteodyne.heat.world.HeatDrivers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Cold Sweat's hearth, boiler and icebox become sources in the pack's heat simulation (weather phase 8f-3,
 * {@code claude/weather-backbone-phase8f.md} section 8; Bright, 2026-10-15).
 *
 * <ul>
 *   <li><b>Where:</b> in the Overworld, ships included (8f-5). In other dimensions they keep working Cold Sweat's own
 *       way.</li>
 *   <li><b>Switched off</b> there (mixins in {@code mixin/compat/coldsweat}): their air spreading
 *       ({@code tickPaths}) and the insulation they give ({@code insulateEntity}), and their smart mode, which can't
 *       work without that insulation.</li>
 *   <li><b>On and fuel</b> by Cold Sweat's own controls: a hearth heats while it is using hot fuel and cools while it
 *       is using cold fuel (a redstone signal on its heating or cooling side, its smokestack, fuel); a boiler or icebox
 *       the same with a smokestack on it. Cold Sweat drains the fuel as before. These are the {@link HeatDrivers}
 *       {@code cold_sweat_heating} and {@code cold_sweat_cooling}.</li>
 * </ul>
 * Only loaded when Cold Sweat is installed.
 */
public final class ColdSweatHearths {

    /** The heat drivers of hearth-family blocks using hot and cold fuel (moved out of {@link HeatDrivers}, split seam S13). */
    public static final ResourceLocation HEATING = EnginesAndEmpires.id("cold_sweat_heating");
    public static final ResourceLocation COOLING = EnginesAndEmpires.id("cold_sweat_cooling");

    /** Whether the pack's heat simulation stands in for the hearth-family block at {@code pos}. */
    public static boolean replaced(Level level, BlockPos pos) {
        return level != null && level.dimension() == Level.OVERWORLD;
    }

    /** Whether the block entity (a hearth, boiler or icebox) is replaced. */
    public static boolean replaced(BlockEntity be) {
        return replaced(be.getLevel(), be.getBlockPos());
    }

    static void registerDrivers() {
        HeatDrivers.register(HEATING, (level, pos, state, be) ->
                be instanceof HearthBlockEntity && replaced(level, pos)
                        && ((HearthFuelAccessor) be).engines_and_empires$usingHotFuel() ? 1 : 0);
        HeatDrivers.register(COOLING, (level, pos, state, be) ->
                be instanceof HearthBlockEntity && replaced(level, pos)
                        && ((HearthFuelAccessor) be).engines_and_empires$usingColdFuel() ? 1 : 0);
    }

    private ColdSweatHearths() {
    }
}
