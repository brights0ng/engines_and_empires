package dev.brights0ng.enginesandempires.oregen.refining;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.sparseores.rich.RichOreBlocks;
import dev.brights0ng.sparseores.rich.RichOreDataGen;
import dev.brights0ng.sparseores.rich.RichOres;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * The pack's loot cuts: what the ores the pack cuts down ({@link Refining#CUTS}) drop, replacing the loot tables of
 * both their ordinary (vanilla) blocks and their rich blocks. Coal, diamond and nether quartz drop one chip instead of
 * a whole item; redstone, lapis and nether gold drop about a quarter of vanilla's amount. A rich block drops
 * {@link RichOres#RICHNESS} times its ordinary block, as Sparse Ores' own rich blocks do.
 *
 * <p>Both tables of a cut ore are generated from its Sparse Ores {@link RichOres} entry with the cut drop
 * ({@link Refining#packSpec}), with Sparse Ores' {@link RichOreDataGen.OreLoot}: once over for the ordinary block, five
 * times over for the rich one, so the two cannot drift apart. Sparse Ores' entries hold vanilla's drops, so without the core its
 * rich ores drop five times vanilla's amounts and agree with vanilla's ordinary ores (split step 4); the core's tables
 * share the rich blocks' ids and win because the core loads after Sparse Ores. The cuts are the core's because cutting
 * vanilla's drops is pack content (plan decision 8: loot cuts live in the core).
 */
public final class LootCuts extends RichOreDataGen.OreLoot {

    public LootCuts(HolderLookup.Provider registries) {
        super(registries);
    }

    @Override
    protected void generate() {
        for (Refining.Cut cut : Refining.CUTS) {
            RichOres.Spec spec = Refining.packSpec(cut.oreId());
            for (RichOres.Variant variant : spec.variants()) {
                Block ordinary = ordinary(variant);
                add(ordinary, oreDrop(ordinary, spec, 1));
                Block rich = RichOreBlocks.block(variant.name());
                add(rich, oreDrop(rich, spec, RichOres.RICHNESS));
            }
        }
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (Refining.Cut cut : Refining.CUTS) {
            for (RichOres.Variant variant : RichOres.forOre(cut.oreId()).variants()) {
                blocks.add(ordinary(variant));
                blocks.add(RichOreBlocks.block(variant.name()));
            }
        }
        return blocks;
    }

    /** The ordinary (vanilla) block a rich block copies. */
    private static Block ordinary(RichOres.Variant variant) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(variant.counterpart()));
    }
}
