package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.frontier.deep.DeepRoster;
import dev.brights0ng.frontier.incursion.Incursions;
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
 * The incursion check that needs the core (Frontier phase 6): the wave rosters and the loud machines loading, the loud
 * machines being Create's machines, whose weights are the core's data ({@code frontier:loud_machines}). The other
 * incursion checks need no core code and are the test-only mod Core-less Tests' since split step 4c
 * ({@code corelesstests.gametest.FrontierIncursionGameTests}), which runs them without the core too; this class keeps
 * the name, so the test keeps its name.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class FrontierIncursionGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH, batch = "incursion_data")
    public static void theWaveRostersAndLoudMachinesLoad(GameTestHelper helper) {
        helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.LESSER).isPresent(), "the lesser roster");
        for (int wave = 1; wave <= Incursions.GREATER_WAVES; wave++) {
            helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.greater(wave)).isPresent(), "greater wave " + wave);
        }
        DeepRoster last = DeepRoster.get(helper.getLevel(), DeepRoster.greater(5)).orElseThrow();
        helper.assertTrue(last.fixed().stream().anyMatch(f -> f.entity().getPath().equals("stalker")
                        && f.roll(helper.getLevel().random) == 1),
                "the stalker comes only in the last wave, alone");
        helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.greater(4)).orElseThrow().fixed().stream()
                .noneMatch(f -> f.entity().getPath().equals("stalker")), "and not before");
        for (int wave = 1; wave <= Incursions.GREATER_WAVES; wave++) {
            helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.greater(wave)).orElseThrow().fixed().stream()
                    .noneMatch(f -> f.entity().getPath().equals("sludge")), "no sludge in wave " + wave);
        }
        helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.LESSER).orElseThrow().entries().stream()
                .noneMatch(e -> e.entity().getPath().equals("sludge")), "no sludge in lesser waves");
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_drill")) == 10.0, "a drill weighs 10");
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_press")) == 5.0, "a press weighs 5");
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_saw")) == 1.0, "a saw weighs 1");
        helper.assertTrue(LoudMachines.weight(Blocks.STONE.defaultBlockState()) == 0.0, "stone is quiet");
        helper.succeed();
    }

    private static net.minecraft.world.level.block.state.BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    private FrontierIncursionGameTests() {
    }
}
