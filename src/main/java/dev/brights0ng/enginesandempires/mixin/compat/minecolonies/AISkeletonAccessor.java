package dev.brights0ng.enginesandempires.mixin.compat.minecolonies;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;

/** The worker behind a MineColonies job AI (a protected field of its base class). */
@Pseudo
@Mixin(targets = "com.minecolonies.core.entity.ai.workers.AbstractAISkeleton", remap = false)
public interface AISkeletonAccessor {

    @Accessor("worker")
    AbstractEntityCitizen engines_and_empires$worker();
}
