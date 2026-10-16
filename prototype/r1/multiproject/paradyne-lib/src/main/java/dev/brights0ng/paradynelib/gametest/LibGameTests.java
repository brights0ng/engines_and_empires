package dev.brights0ng.paradynelib.gametest;

import dev.brights0ng.paradynelib.ParadyneLib;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(ParadyneLib.MODID)
@PrefixGameTestTemplate(false)
public final class LibGameTests {

    @GameTest(template = "gametest/empty")
    public static void libBlockPlaces(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ParadyneLib.LIB_BLOCK.get());
        helper.assertBlockPresent(ParadyneLib.LIB_BLOCK.get(), pos);
        helper.succeed();
    }
}
