// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import dev.brights0ng.meteodyne.climate.MeteodyneTemperature;
import dev.brights0ng.meteodyne.ships.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** The common measurement path for held instruments and mounted sensors. Server thread only. */
public final class InstrumentReadings {
    public static double temperature(ServerLevel level, Vec3 at) {
        // A held item can be in world space while a mounted sensor is stored in the ship's plot.
        // Resolve both to the same cabin frame before asking for outside air and the room offset.
        Vec3 sample = at;
        if (!Ships.get().inPlot(level, BlockPos.containing(at))) {
            Vec3 plot = Ships.get().plotPoint(level, at.x, at.y, at.z);
            if (plot != null) { sample = plot; }
        }
        MeteodyneTemperature provider = MeteodyneTemperature.INSTANCE;
        double air = provider.air(level, BlockPos.containing(sample));
        return Double.isFinite(air) ? air + provider.room(level, sample) : Double.NaN;
    }

    /** Local sheltered wind, with the same height blend as wind physics. Ship velocity is excluded. */
    public static WindReading wind(ServerLevel level, Vec3 at) { return wind(level, at, null); }

    /** Mounted instruments exclude exactly their own housing; held instruments exclude no world or ship blocks. */
    public static WindReading wind(ServerLevel level, Vec3 at, BlockPos ignored) {
        if (!dev.brights0ng.meteodyne.WeatherOwnership.owns(level)) { return null; }
        Vec3 world = worldPoint(level, at);
        if (world == null) { return null; }
        var column = dev.brights0ng.meteodyne.wind.WindSources.column(level, world.x, world.z);
        if (column == null) { return null; }
        var params = dev.brights0ng.paradynelib.wind.WindConfig.params();
        BlockPos here = BlockPos.containing(world);
        double ground = level.hasChunkAt(here) ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                here.getX(), here.getZ()) : level.getSeaLevel();
        double[] vector = column.at(dev.brights0ng.paradynelib.wind.WindBlend.aloftShare(world.y, ground,
                params.surfaceLayer(), params.aloftHeight()));
        if (!Double.isFinite(vector[0]) || !Double.isFinite(vector[1])) { return null; }
        double speed = Math.hypot(vector[0], vector[1]);
        if (speed > 1e-6) {
            double exposure = dev.brights0ng.meteodyne.wind.InstrumentShelter.exposure(level, world,
                    vector[0] / speed, vector[1] / speed, params, ignored);
            vector[0] *= exposure;
            vector[1] *= exposure;
        }
        return new WindReading(level.dimension().location().toString(), level.getGameTime(), (float) vector[0], (float) vector[1]);
    }

    /** A plot position becomes a world position; ordinary positions pass through unchanged. */
    public static Vec3 worldPoint(ServerLevel level, Vec3 at) {
        BlockPos block = BlockPos.containing(at);
        if (!Ships.get().inPlot(level, block)) {
            return at;
        }
        return Ships.get().worldCentre(level, block);
    }

    public static boolean withinReach(ServerLevel level, Player player, BlockPos sensor) {
        Vec3 target = worldPoint(level, sensor.getCenter());
        Vec3 viewer = worldPoint(level, player.position());
        return target != null && viewer != null && viewer.distanceToSqr(target) <= 64;
    }

    private InstrumentReadings() { }
}
