// Written by Gemini.
package dev.brights0ng.corelesstests.gametest;

import java.util.List;

import dev.brights0ng.corelesstests.CorelessTests;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.sparseores.Deposit;
import dev.brights0ng.sparseores.DepositBody;
import dev.brights0ng.sparseores.OreMap;
import dev.brights0ng.sparseores.OreType;
import dev.brights0ng.sparseores.OreTypes;
import dev.brights0ng.sparseores.Realm;
import dev.brights0ng.sparseores.worldgen.OreBlocks;
import dev.brights0ng.sparseores.worldgen.OreMaps;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CorelessTests.MODID)
@PrefixGameTestTemplate(false)
public final class EmpireSparseOresGameTests {

    @GameTest(template = GameTestStructures.EMPTY)
    public static void talcRegistersInSparseOresAndGeneratesValidDeposits(GameTestHelper helper) {
        // 1. Verify registered OreType
        OreType talc = OreTypes.byId("talc");
        helper.assertTrue(talc != null, "Talc must be registered in OreTypes");
        helper.assertTrue(talc.layer().scale() == 192, "Talc spacing must be 192");
        helper.assertTrue(talc.realm() == Realm.OVERWORLD, "Talc must generate in the Overworld");
        helper.assertTrue(talc.depth().minY() == -48 && talc.depth().maxY() == 96, "Talc depth must be -48 to 96");
        helper.assertTrue(talc.size().median() == 350, "Talc median size must be 350");

        // 2. Verify OreBlocks palette resolves to Empire's talc blocks
        OreBlocks.Palette palette = OreBlocks.paletteFor("talc", Realm.OVERWORLD);
        BlockState stoneOre = palette.ore(false);
        BlockState deepslateOre = palette.ore(true);
        helper.assertTrue(stoneOre.is(EmpireContent.TALC_ORE.get()),
                "Stone talc ore block must be empire:talc_ore");
        helper.assertTrue(deepslateOre.is(EmpireContent.DEEPSLATE_TALC_ORE.get()),
                "Deepslate talc ore block must be empire:deepslate_talc_ore");

        // 3. Verify OreMaps produces deposits
        long seed = helper.getLevel().getSeed();
        OreMap map = OreMaps.mapFor(seed, talc);
        helper.assertTrue(map != null, "OreMap for talc must exist");
        List<Deposit> nearby = map.depositsNear(0, 0, 1000);
        helper.assertFalse(nearby.isEmpty(), "Talc map should find deposits near origin with S=192");

        // 4. Verify DepositBody generation works with seam shape
        Deposit sample = nearby.get(0);
        DepositBody body = DepositBody.generate(sample, talc);
        helper.assertTrue(body != null, "Talc deposit body must generate");
        helper.assertTrue(body.oreCount() > 0, "Talc deposit must contain ore blocks");

        helper.succeed();
    }
}
