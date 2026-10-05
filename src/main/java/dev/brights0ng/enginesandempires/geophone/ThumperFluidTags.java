package dev.brights0ng.enginesandempires.geophone;

import java.util.concurrent.CompletableFuture;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.FluidTagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * The fluid tags {@link ThumperFluids} sorts the combustive thumper's tank by. Every entry points at a common
 * {@code c:} tag, marked optional, so nothing breaks when the mod that fills it (Create: Diesel Generators, for now) is
 * absent, and any other mod's diesel or ethanol is picked up automatically.
 */
public final class ThumperFluidTags extends FluidTagsProvider {

    public ThumperFluidTags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper existing) {
        super(output, lookup, EnginesAndEmpiresMod.MODID, existing);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(ThumperFluids.PREMIUM_FUEL).addOptionalTag(common("diesel"));
        tag(ThumperFluids.FUEL)
                .addTag(ThumperFluids.PREMIUM_FUEL)
                .addOptionalTag(common("gasoline"))
                .addOptionalTag(common("biodiesel"));
        // Combustible but not a thumper fuel: fires, then explodes. Also the thumper fuels themselves, so this tag reads
        // as "everything that burns"; ThumperFluids checks the fuel tags first.
        tag(ThumperFluids.COMBUSTIBLE)
                .addTag(ThumperFluids.FUEL)
                .addOptionalTag(common("crude_oil"))
                .addOptionalTag(common("plantoil"))
                .addOptionalTag(common("ethanol"))
                .addOptionalTag(common("fuel"));
    }

    private static ResourceLocation common(String name) {
        return ResourceLocation.fromNamespaceAndPath("c", name);
    }
}
