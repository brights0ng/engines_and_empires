// Written by Claude (M8e4).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** M8e4: the airflow cell table keeps its settings as it grows. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class AirflowCellsGameTests {

    /**
     * A newly seen cell adds a code, and the new table keeps the config's settings: before M8e4 it was rebuilt without
     * the dynamic start's switch, so in game every region after the first new block type started cold.
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void aNewCellKeepsTheTablesSettings(GameTestHelper helper) {
        AirflowCells.Table before = AirflowCells.table();
        // A material no block has: an odd conductance and capacity, so the cell is new to the table.
        Material odd = new Material("m8e4-test", 0.000123457, 1234.5679);
        AirflowCells.intern(new AirflowCells.Cell(AirflowCells.Kind.SOLID, odd));
        AirflowCells.Table after = AirflowCells.table();
        helper.assertTrue(after.size() == before.size() + 1 || before.size() >= AirflowCells.MAX_CODES,
                "a new code: " + before.size() + " -> " + after.size());
        helper.assertTrue(after.dynamicStart() == WeatherConfig.airflowDynamicStart(),
                "the dynamic start's switch kept: " + after.dynamicStart());
        helper.assertTrue(after.dynamicStart() == before.dynamicStart(), "as before the new code");
        helper.assertTrue(after.fixedKeepFine() == before.fixedKeepFine(), "the keep-fine radius kept");
        helper.assertTrue(after.filmResistance() == before.filmResistance(), "the film kept");
        helper.assertTrue(after.generation() == before.generation(), "the same generation");
        helper.succeed();
    }

    private AirflowCellsGameTests() {
    }
}
