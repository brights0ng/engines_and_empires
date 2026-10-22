// Written by Gemini.
package dev.brights0ng.empire.gametest;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Phase 0 bootstrap game test proving Empire launches its GameTestServer cleanly.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class EmpireBootstrapGameTests {

    @GameTest(template = GameTestStructures.EMPTY)
    public static void modBootstrap(GameTestHelper helper) {
        helper.succeed();
    }

    private EmpireBootstrapGameTests() {
    }
}
