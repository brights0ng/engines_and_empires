// Written by Gemini.
package dev.brights0ng.sparseores;

import dev.brights0ng.sparseores.worldgen.OreBlocks;
import dev.brights0ng.sparseores.worldgen.OreMaps;

public final class SparseOresApi {

    private SparseOresApi() {
    }

    /**
     * Registers a new ore with Sparse Ores.
     *
     * @param type             the ore's specification and distribution
     * @param stoneBlockId     the block id in stone (e.g. {@code "empire:talc_ore"})
     * @param deepslateBlockId the block id in deepslate (e.g. {@code "empire:deepslate_talc_ore"})
     */
    public static void registerOre(OreType type, String stoneBlockId, String deepslateBlockId) {
        OreTypes.register(type);
        OreBlocks.registerEntry(type.id(), stoneBlockId, deepslateBlockId, null);
        OreMaps.clearCache();
    }
}
