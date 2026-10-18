package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.frontier.incursion.LoudMachines;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The incursion check that needs the core (Frontier phase 6): the loud machines loading, being Create's machines, whose
 * weights are the core's data ({@code frontier:loud_machines}). The wave rosters check is Frontier's own since split
 * step 4e ({@code theWaveRostersLoad}, in Frontier's {@code FrontierIncursionGameTests}). The other
 * incursion checks need no core code and are Frontier's, Seismic Surveyance's or the test-only mod Core-less Tests'
 * ({@code corelesstests.gametest.FrontierIncursionGameTests}), which run them without the core too. Split step 4e renamed
 * this class (it was {@code FrontierIncursionGameTests}, as the others still are) to say what it checks: the pack's side
 * of the incursion wiring, which is data the core ships for Frontier's machinery.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class PackIncursionGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH, batch = "incursion_data")
    public static void theLoudMachinesLoad(GameTestHelper helper) {
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_drill")) == 10.0, "a drill weighs 10");
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_press")) == 5.0, "a press weighs 5");
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_saw")) == 1.0, "a saw weighs 1");
        helper.assertTrue(LoudMachines.weight(Blocks.STONE.defaultBlockState()) == 0.0, "stone is quiet");
        helper.succeed();
    }

    private static net.minecraft.world.level.block.state.BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    private PackIncursionGameTests() {
    }
}
