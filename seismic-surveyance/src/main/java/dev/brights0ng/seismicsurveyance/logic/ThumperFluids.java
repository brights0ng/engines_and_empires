// Reorganized by Claude
package dev.brights0ng.seismicsurveyance.logic;

import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Sorts whatever is in the combustive thumper's tank into a {@link CombustiveFiring.Charge}, by the fluid's entry in the
 * thumper fuel data map ({@link ThumperFuels}, split step 4c; until then by these tags directly). No Create: Diesel
 * Generators class is ever touched: its fuels are in the data map by id and by the common tags that mod (and others)
 * put them in, so the thumper works with any mod's diesel, and a data pack can re-sort anything with a data map entry.
 *
 * <p>The three fluid tags below are Seismic's, and its own data map entries give each its grade, so tagging a fluid is
 * enough to make it a fuel:
 * <ul>
 *   <li>{@link #PREMIUM_FUEL} ({@code seismic_surveyance:premium_thumper_fuel}): diesel. Premium.</li>
 *   <li>{@link #FUEL} ({@code seismic_surveyance:thumper_fuel}): gasoline and biodiesel, plus everything premium.
 *       Standard.</li>
 *   <li>{@link #COMBUSTIBLE} ({@code seismic_surveyance:combustible}): crude oil, plant oil, ethanol, and the common
 *       {@code c:fuel} tag. Volatile: it fires, then explodes. A fluid in several of these takes the best grade.</li>
 *   <li>A fluid with no entry is inert.</li>
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
        ThumperFuels.Fuel fuel = ThumperFuels.of(stack.getFluid());
        return fuel == null ? CombustiveFiring.Charge.INERT : fuel.grade().charge();
    }

    private static TagKey<Fluid> tag(String name) {
        return TagKey.create(Registries.FLUID, SeismicSurveyance.id(name));
    }

    private ThumperFluids() {
    }
}
