package dev.brights0ng.meteodyne.gametest;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.forecast.Climatology;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A headless climatology year for debugging the seasons (phase 8a), off unless asked for: run the game tests with
 * {@code -Pmeteodyne.climatologyDays=72} (and optionally {@code -Pmeteodyne.climatologyX=..., -Pmeteodyne.climatologyZ=...} for the
 * centre, default 0, 0). Spots are the centre and 3000 blocks east, south, west and north of it; the run simulates the
 * weather around each as if a player stood there. The report goes to the log and the daily and trace CSVs to
 * {@code run/logs/}. Without the property the test passes at once.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class ClimatologyDiagnosticRun {

    static final double SPREAD = 3000;

    @GameTest(template = GameTestStructures.EMPTY, batch = "weather_climatology_diagnostic", timeoutTicks = 40_000)
    public static void climatologyYear(GameTestHelper helper) {
        String daysProp = System.getProperty("meteodyne.climatologyDays");
        if (daysProp == null || daysProp.isBlank()) {
            helper.succeed();
            return;
        }
        int days = Integer.parseInt(daysProp.trim());
        double cx = Double.parseDouble(System.getProperty("meteodyne.climatologyX", "0"));
        double cz = Double.parseDouble(System.getProperty("meteodyne.climatologyZ", "0"));
        ServerLevel level = helper.getLevel();
        Climatology.clearSpots();
        Climatology.add(level, cx, cz);
        Climatology.add(level, cx + SPREAD, cz);
        Climatology.add(level, cx, cz + SPREAD);
        Climatology.add(level, cx - SPREAD, cz);
        Climatology.add(level, cx, cz - SPREAD);
        String error = Climatology.start(level, days, null);
        helper.assertTrue(error == null, "the run starts: " + error);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(Climatology.running(), "still running"))
                .thenExecute(() -> {
                    Climatology.clearSpots();
                    Meteodyne.LOGGER.info("Climatology diagnostic year:\n{}",
                            String.join("\n", Climatology.report()));
                })
                .thenSucceed();
    }

    private ClimatologyDiagnosticRun() {
    }
}
