// Written by ChatGPT.
package dev.brights0ng.meteodyne.wind;

import dev.brights0ng.meteodyne.ships.Ships;
import dev.brights0ng.paradynelib.wind.Shelter;
import dev.brights0ng.paradynelib.wind.ShelterProbe;
import dev.brights0ng.paradynelib.wind.WindParams;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Local instrument exposure, sharing the library's solid-block, directional wall and roof rules. */
public final class InstrumentShelter {
    public static double exposure(ServerLevel level, Vec3 world, double x, double z, WindParams params, BlockPos ignored) {
        double[] coverage = new double[Shelter.FACES];
        ShelterProbe.measurePoint(level, world, params.sideReach(), params.roofReach(), ignored, coverage);
        if (Ships.get().near(level, world.x, world.y, world.z, Math.max(params.sideReach(), params.roofReach()))) {
            for (Direction direction : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                int face = switch (direction) {
                    case UP -> Shelter.TOP; case NORTH -> Shelter.NORTH; case SOUTH -> Shelter.SOUTH;
                    case WEST -> Shelter.WEST; default -> Shelter.EAST;
                };
                int reach = direction == Direction.UP ? params.roofReach() : params.sideReach();
                int ship = Ships.get().rayObstruction(level, world.x, world.y, world.z,
                        direction.getStepX(), direction.getStepY(), direction.getStepZ(), 0, reach,
                        state -> ShelterProbe.isSolid(level, state) ? 2 : 0, ignored);
                if (ship >= 2) { coverage[face] = 1; }
            }
        }
        return Shelter.exposure(coverage, x, z, params.roofWeight());
    }
    private InstrumentShelter() { }
}
