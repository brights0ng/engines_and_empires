// Reorganized by Claude
package dev.brights0ng.seismicsurveyance.datagen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.brights0ng.seismicsurveyance.logic.ThumperFluids;
import dev.brights0ng.seismicsurveyance.logic.ThumperFuels;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.conditions.ModLoadedCondition;
import net.neoforged.neoforge.common.data.DataMapProvider;

/**
 * Seismic Surveyance's own entries in the thumper fuel data map ({@link ThumperFuels}), written by its data run
 * ({@code SeismicDataGen}):
 * <ul>
 *   <li>its three fluid tags, each with its grade, so a mod can make its fluid a fuel just by tagging it (the tags take
 *       the common {@code c:} tags of other mods' fuels, {@code ThumperFluidTags});</li>
 *   <li>Create: Diesel Generators' fluids by id, behind a {@code neoforge:mod_loaded} condition on that mod, with the
 *       grades they had from its tags before split step 4c (diesel premium; gasoline and biodiesel standard; ethanol,
 *       crude oil and plant oil volatile), so they stay fuels whatever that mod's tags become.</li>
 * </ul>
 * A fluid named twice gets the better grade, so the order here does not matter.
 */
public final class ThumperFuelData extends DataMapProvider {

    /** Diesel Generators' mod id. */
    public static final String DIESEL_GENERATORS = "createdieselgenerators";

    /** Diesel Generators' fluids (still and flowing, as that mod tags them), by path, and their grades. */
    public static final Map<String, ThumperFuels.Grade> DIESEL_GENERATORS_FUELS = new LinkedHashMap<>();

    static {
        DIESEL_GENERATORS_FUELS.put("diesel", ThumperFuels.Grade.PREMIUM);
        DIESEL_GENERATORS_FUELS.put("flowing_diesel", ThumperFuels.Grade.PREMIUM);
        DIESEL_GENERATORS_FUELS.put("gasoline", ThumperFuels.Grade.STANDARD);
        DIESEL_GENERATORS_FUELS.put("flowing_gasoline", ThumperFuels.Grade.STANDARD);
        DIESEL_GENERATORS_FUELS.put("biodiesel", ThumperFuels.Grade.STANDARD);
        DIESEL_GENERATORS_FUELS.put("flowing_biodiesel", ThumperFuels.Grade.STANDARD);
        DIESEL_GENERATORS_FUELS.put("ethanol", ThumperFuels.Grade.VOLATILE);
        DIESEL_GENERATORS_FUELS.put("flowing_ethanol", ThumperFuels.Grade.VOLATILE);
        DIESEL_GENERATORS_FUELS.put("crude_oil", ThumperFuels.Grade.VOLATILE);
        DIESEL_GENERATORS_FUELS.put("plant_oil", ThumperFuels.Grade.VOLATILE);
        DIESEL_GENERATORS_FUELS.put("flowing_plant_oil", ThumperFuels.Grade.VOLATILE);
    }

    public ThumperFuelData(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup);
    }

    @Override
    protected void gather() {
        var fuels = builder(ThumperFuels.FUELS)
                .add(ThumperFluids.COMBUSTIBLE, new ThumperFuels.Fuel(ThumperFuels.Grade.VOLATILE), false)
                .add(ThumperFluids.FUEL, new ThumperFuels.Fuel(ThumperFuels.Grade.STANDARD), false)
                .add(ThumperFluids.PREMIUM_FUEL, new ThumperFuels.Fuel(ThumperFuels.Grade.PREMIUM), false);
        for (Map.Entry<String, ThumperFuels.Grade> fuel : DIESEL_GENERATORS_FUELS.entrySet()) {
            fuels.add(ResourceLocation.fromNamespaceAndPath(DIESEL_GENERATORS, fuel.getKey()),
                    new ThumperFuels.Fuel(fuel.getValue()), false, new ModLoadedCondition(DIESEL_GENERATORS));
        }
    }
}
