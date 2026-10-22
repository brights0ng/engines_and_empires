package dev.brights0ng.frontier.guard;

import net.minecraft.world.entity.Entity;

/**
 * Guards a tag can't pick out (split seam S12): another mod
 * registers one with {@link GuardSources#register} at mod setup. The {@code guards} entity tag is always asked as well.
 */
public interface GuardSource {

    /** Whether the entity is worth tracking: a guard now, or one that could become a guard later. */
    boolean isCandidate(Entity entity);

    /** Whether the entity is a guard right now. */
    boolean isGuard(Entity entity);
}
