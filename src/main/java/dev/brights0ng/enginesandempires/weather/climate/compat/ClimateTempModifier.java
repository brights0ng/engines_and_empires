package dev.brights0ng.enginesandempires.weather.climate.compat;

import java.util.function.Function;

import com.momosoftworks.coldsweat.api.temperature.modifier.TempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.util.entity.DummyPlayer;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import dev.brights0ng.meteodyne.climate.ApparentSync;
import dev.brights0ng.meteodyne.climate.PersonClimate;
import dev.brights0ng.meteodyne.heat.world.HeatWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The pack's air temperature as Cold Sweat's world term (weather phase 8d). It reads the entity's block position, so
 * Cold Sweat's block-position queries (which run the same stack on a dummy entity placed there) get the pack's answer
 * too.
 *
 * <p>Server: {@code Temperature.at}, underground included. Client: the synced grid, which has no underground, until
 * the first sync arrives; then the middle of the comfort range, as Cold Sweat's own biome term does with no biomes
 * to read. The server's value is what counts: Cold Sweat syncs the player's computed temperature to the client.
 *
 * <p>For a real player (not the dummy Cold Sweat places at a block to ask what it is like there) the air is then
 * felt rather than measured (weather phase 8e-2): wind chill and heat index from {@link PersonClimate}, and the sun's
 * warmth, and (8f-3) the heat simulation's room air and the radiant warmth of sources in view. The client takes that
 * part from the server ({@link ApparentSync}); it can't see the wind, shelter, ships or rooms.
 *
 * <p>Anything else (the dummy behind a block-position query such as a thermometer, and mobs) gets the air plus the
 * room's warmth at its block (8f-3).
 */
public class ClimateTempModifier extends TempModifier {

    @Override
    protected Function<Double, Double> calculate(LivingEntity entity, Temperature.Trait trait) {
        BlockPos pos = WorldHelper.sublevelToWorld(entity.level(), entity.blockPosition());
        double celsius = ColdSweatCompat.airTemperature(entity.level(), pos);
        double mc = Double.isFinite(celsius) ? ColdSweatCompat.toMc(celsius) : comfortMiddle();
        if (Double.isFinite(celsius) && entity instanceof Player player && !(entity instanceof DummyPlayer)) {
            double felt;
            if (entity.level() instanceof ServerLevel server) {
                felt = PersonClimate.offsetC(PersonClimate.sample(server, player), celsius);
            } else {
                felt = ApparentSync.clientOffsetC();
            }
            mc += ColdSweatCompat.toMc(felt);
        } else if (Double.isFinite(celsius) && entity.level() instanceof ServerLevel server) {
            HeatWorld heat = HeatWorld.of(server);
            if (heat != null) {
                // Its own block, in a ship's grid if it is one (8f-5)
                mc += ColdSweatCompat.toMc(heat.roomAt(net.minecraft.world.phys.Vec3.atCenterOf(entity.blockPosition())));
            }
        }
        double total = mc;
        return temp -> temp + total;
    }

    private static double comfortMiddle() {
        return (ConfigSettings.MIN_TEMP.get() + ConfigSettings.MAX_TEMP.get()) / 2;
    }
}
