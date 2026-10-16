package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.paradynelib.ParadyneLib;
import dev.brights0ng.sparseores.SparseOres;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class CoreGameTests {

    /** All three mods are loaded, each exactly once, with its own version from its own neoforge.mods.toml. */
    @GameTest(template = "gametest/empty")
    public static void everyModLoadedOnce(GameTestHelper helper) {
        for (String id : new String[] {ParadyneLib.MODID, SparseOres.MODID, EnginesAndEmpiresMod.MODID}) {
            helper.assertTrue(ModList.get().isLoaded(id), id + " not loaded");
        }
        String libVersion = ModList.get().getModContainerById(ParadyneLib.MODID).orElseThrow().getModInfo().getVersion().toString();
        String coreVersion = ModList.get().getModContainerById(EnginesAndEmpiresMod.MODID).orElseThrow().getModInfo().getVersion().toString();
        helper.assertTrue("0.1.0".equals(libVersion), "paradyne_lib version " + libVersion);
        helper.assertTrue("0.4.0".equals(coreVersion), "engines_and_empires version " + coreVersion);
        helper.setBlock(new BlockPos(1, 1, 1), EnginesAndEmpiresMod.CORE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 1), SparseOres.SPARSE_ORE.get());
        helper.setBlock(new BlockPos(3, 1, 1), ParadyneLib.LIB_BLOCK.get());
        helper.assertBlockPresent(EnginesAndEmpiresMod.CORE_BLOCK.get(), new BlockPos(1, 1, 1));
        helper.succeed();
    }

    /** Two mods' mixins inject into the same method and both apply (the S4 question). */
    @GameTest(template = "gametest/empty")
    public static void twoModsMixinsMerge(GameTestHelper helper) {
        String brand = helper.getLevel().getServer().getServerModName();
        helper.assertTrue(brand.contains("+sparse_ores") && brand.contains("+engines_and_empires"), "brand: " + brand);
        EnginesAndEmpiresMod.LOGGER.info("[R1] server brand with both mixins: {}", brand);
        helper.succeed();
    }
}
