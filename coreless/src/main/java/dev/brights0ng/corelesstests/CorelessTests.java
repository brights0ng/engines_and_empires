package dev.brights0ng.corelesstests;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.data.DataGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Core-less Tests (split step 4c): a test-only mod, never released, that holds the game tests of the family's feature
 * mods working together which need no code of the Engines and Empires core. It loads in the core-less combined run
 * ({@code coreless/}, the feature mods without the core) and in the aggregate, so those tests run both with and
 * without the core. It registers nothing but the empty structure its tests build on (its data run).
 */
@Mod(CorelessTests.MODID)
public final class CorelessTests {

    public static final String MODID = "coreless_tests";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CorelessTests(IEventBus modEventBus) {
        modEventBus.addListener(CorelessTests::gatherData);
    }

    private static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(event.includeServer(), new GameTestStructures(generator.getPackOutput(), MODID));
    }
}
