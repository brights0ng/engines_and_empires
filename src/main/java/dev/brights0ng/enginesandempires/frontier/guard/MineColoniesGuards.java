package dev.brights0ng.enginesandempires.frontier.guard;

import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;

import dev.brights0ng.frontier.guard.GuardTypes;
import net.minecraft.world.entity.Entity;

/**
 * MineColonies' guards (knights, rangers, druids and the rest). They are ordinary colonists with a guard job, all one entity
 * type, so a tag cannot pick them out; this asks MineColonies' own API instead.
 *
 * <p>Only ever touched through {@link GuardTypes}, and only when MineColonies is loaded, so the pack runs without it.
 *
 * <p>Public since split step 1b only because {@link GuardTypes} is in Frontier's package now; seam S12 replaces that
 * call with a guard-source registry.
 */
public final class MineColoniesGuards {

    /** Whether the entity is a colonist at all (so worth keeping an eye on: it may be given a guard job later). */
    public static boolean isColonist(Entity entity) {
        return entity instanceof AbstractEntityCitizen;
    }

    /** Whether the entity is a colonist working as a guard right now. */
    public static boolean isGuard(Entity entity) {
        if (!(entity instanceof AbstractEntityCitizen citizen) || citizen.getCitizenJobHandler() == null) {
            return false;
        }
        IJob<?> job = citizen.getCitizenJobHandler().getColonyJob();
        return job != null && job.isGuard();
    }

    private MineColoniesGuards() {
    }
}
