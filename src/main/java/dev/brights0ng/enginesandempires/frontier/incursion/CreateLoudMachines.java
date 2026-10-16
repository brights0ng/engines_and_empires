package dev.brights0ng.enginesandempires.frontier.incursion;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.brights0ng.frontier.incursion.LoudMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Create's machines for Frontier's loud machines (moved out of {@link LoudMachines} in split seam S14): a Create
 * kinetic block runs while it turns, and an engine that drives a separate shaft (the steam engine, the huge diesel
 * engine) while a generating shaft within two blocks turns. The weights stay in Frontier's {@code loud_machines} data map.
 */
public final class CreateLoudMachines {

    /** Registers Create's running check with Frontier. Call at mod setup. */
    public static void register() {
        LoudMachines.PROVIDERS.register(CreateLoudMachines::isRunning);
    }

    /** Whether the machine is a Create machine running right now (a kinetic block that isn't turning is not). */
    static boolean isRunning(LoudMachines.Machine machine) {
        ServerLevel level = machine.level();
        BlockPos pos = machine.pos();
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof KineticBlockEntity kinetic) {
            return kinetic.getSpeed() != 0;
        }
        // An engine driving a shaft of its own (steam engine, huge diesel engine): is a generating shaft nearby turning?
        for (Direction direction : Direction.values()) {
            for (int step = 1; step <= 2; step++) {
                if (level.getBlockEntity(pos.relative(direction, step)) instanceof GeneratingKineticBlockEntity shaft
                        && shaft.getSpeed() != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private CreateLoudMachines() {
    }
}
