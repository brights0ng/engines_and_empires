package dev.brights0ng.sparseores.gametest;

import dev.brights0ng.paradynelib.ParadyneLib;
import dev.brights0ng.paradynelib.Services;
import dev.brights0ng.paradynelib.ToyProvider;
import dev.brights0ng.sparseores.SparseOres;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(SparseOres.MODID)
@PrefixGameTestTemplate(false)
public final class SparseOresGameTests {

    /** Its own block and the library's block are both registered, so both mods loaded. */
    @GameTest(template = "gametest/empty")
    public static void blocksOfBothModsPlace(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), SparseOres.SPARSE_ORE.get());
        helper.setBlock(new BlockPos(2, 1, 1), ParadyneLib.LIB_BLOCK.get());
        helper.assertBlockPresent(SparseOres.SPARSE_ORE.get(), new BlockPos(1, 1, 1));
        helper.assertBlockPresent(ParadyneLib.LIB_BLOCK.get(), new BlockPos(2, 1, 1));
        helper.succeed();
    }

    /** It registered its provider in the library's registry, and its mixin config was applied. */
    @GameTest(template = "gametest/empty")
    public static void providerAndMixinAreLive(GameTestHelper helper) {
        helper.assertTrue(SparseOres.MODID.equals(Services.get(ToyProvider.class, ToyProvider.NONE).name()),
                "Services should hold sparse_ores' provider");
        String brand = helper.getLevel().getServer().getServerModName();
        helper.assertTrue(brand.contains("+sparse_ores"), "sparse_ores mixin not applied: " + brand);
        helper.succeed();
    }
}
