package dev.brights0ng.enginesandempires.frontier.guard.compat;

import dev.brights0ng.frontier.guard.GuardSources;
import dev.brights0ng.frontier.guard.GuardTypes;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

/**
 * MineColonies' guards as a Frontier guard source (split seam S12). Registered only when MineColonies is
 * installed; {@link MineColoniesGuards} is the only class that names MineColonies' own.
 */
public final class MineColoniesGuardSource implements GuardSources.Source<Entity> {

    public static final String MOD_ID = "minecolonies";

    /** Registers MineColonies' guards with Frontier if MineColonies is present. Safe to call either way. */
    public static void registerIfLoaded() {
        if (ModList.get().isLoaded(MOD_ID)) {
            GuardTypes.SOURCES.register(new MineColoniesGuardSource());
        }
    }

    @Override
    public boolean isCandidate(Entity entity) {
        return MineColoniesGuards.isColonist(entity);
    }

    @Override
    public boolean isGuard(Entity entity) {
        return MineColoniesGuards.isGuard(entity);
    }

    private MineColoniesGuardSource() {
    }
}
