package dev.brights0ng.enginesandempires.oregen.refining;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.sparseores.rich.RichOreDataGen;
import dev.brights0ng.sparseores.rich.RichOres;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * The pack's loot cuts: the loot tables of the ordinary (vanilla) blocks of the ores the pack cuts down
 * ({@link RichOres#PACK_LOOT}), replacing vanilla's. Coal, diamond and nether quartz drop one chip instead of a whole
 * item; redstone, lapis and nether gold drop about a quarter of vanilla's amount.
 *
 * <p>They are generated from Sparse Ores' {@link RichOres} table with its {@link RichOreDataGen.OreLoot}, once over, the
 * same entry and the same builder its rich ores drop five times over, so the ordinary and the rich blocks cannot drift
 * apart. Sparse Ores generated them until split step 2f; they are the core's because cutting vanilla's drops is pack
 * content (plan decision 8: loot cuts live in the core), so without the core vanilla's ores drop what vanilla says.
 */
public final class LootCuts extends RichOreDataGen.OreLoot {

    public LootCuts(HolderLookup.Provider registries) {
        super(registries);
    }

    @Override
    protected void generate() {
        for (Block block : blocks()) {
            add(block, oreDrop(block, specOf(block), 1));
        }
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return blocks();
    }

    /** The ordinary (vanilla) ore blocks whose loot tables the pack replaces. */
    private static List<Block> blocks() {
        List<Block> blocks = new ArrayList<>();
        for (String oreId : RichOres.PACK_LOOT) {
            for (RichOres.Variant variant : RichOres.forOre(oreId).variants()) {
                blocks.add(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(variant.counterpart())));
            }
        }
        return blocks;
    }

    /** The entry of the ore an ordinary block belongs to. */
    private static RichOres.Spec specOf(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        for (RichOres.Spec spec : RichOres.ALL) {
            for (RichOres.Variant variant : spec.variants()) {
                if (variant.counterpart().equals(id)) {
                    return spec;
                }
            }
        }
        throw new IllegalStateException("No ore owns " + id);
    }
}
