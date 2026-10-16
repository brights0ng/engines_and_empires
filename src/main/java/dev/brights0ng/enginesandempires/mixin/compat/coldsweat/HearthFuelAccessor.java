package dev.brights0ng.enginesandempires.mixin.compat.coldsweat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Whether a Cold Sweat hearth (boiler, icebox) is using hot or cold fuel by its own controls (weather phase 8f-3): the
 * plain flags, without the boiler's and icebox's waterskin warming folded in.
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.common.blockentity.HearthBlockEntity", remap = false)
public interface HearthFuelAccessor {

    @Accessor("usingHotFuel")
    boolean engines_and_empires$usingHotFuel();

    @Accessor("usingColdFuel")
    boolean engines_and_empires$usingColdFuel();
}
