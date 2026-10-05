package dev.brights0ng.enginesandempires.geophone;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Sorts whatever is in the combustive thumper's tank into a {@link CombustiveFiring.Charge}, by fluid tag alone. No
 * Create: Diesel Generators class is ever touched: the fuels are recognised by the common tags that mod (and others)
 * put them in, so the thumper works with any mod's diesel, and a data pack can re-sort anything by editing the tags.
 *
 * <ul>
 *   <li>{@link #PREMIUM_FUEL} ({@code engines_and_empires:premium_thumper_fuel}): diesel.</li>
 *   <li>{@link #FUEL} ({@code engines_and_empires:thumper_fuel}): gasoline and biodiesel, plus everything premium.</li>
 *   <li>{@link #COMBUSTIBLE} ({@code engines_and_empires:combustible}): crude oil, plant oil, ethanol, and the common
 *       {@code c:fuel} tag. Anything in here but not in {@link #FUEL} is volatile: it fires, then explodes.</li>
 *   <li>Anything else is inert.</li>
 * </ul>
 */
public final class ThumperFluids {

    public static final TagKey<Fluid> PREMIUM_FUEL = tag("premium_thumper_fuel");
    public static final TagKey<Fluid> FUEL = tag("thumper_fuel");
    public static final TagKey<Fluid> COMBUSTIBLE = tag("combustible");

    /** What this fluid does when the thumper fires on it. */
    public static CombustiveFiring.Charge classify(FluidStack stack) {
        if (stack.isEmpty()) {
            return CombustiveFiring.Charge.EMPTY;
        }
        if (stack.is(PREMIUM_FUEL)) {
            return CombustiveFiring.Charge.PREMIUM_FUEL;
        }
        if (stack.is(FUEL)) {
            return CombustiveFiring.Charge.FUEL;
        }
        if (stack.is(COMBUSTIBLE)) {
            return CombustiveFiring.Charge.VOLATILE;
        }
        return CombustiveFiring.Charge.INERT;
    }

    private static TagKey<Fluid> tag(String name) {
        return TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, name));
    }

    private ThumperFluids() {
    }
}
