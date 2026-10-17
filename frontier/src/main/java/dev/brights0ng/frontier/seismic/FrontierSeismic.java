package dev.brights0ng.frontier.seismic;

import dev.brights0ng.frontier.incursion.ThumperAccess;
import dev.brights0ng.frontier.seismic.gametest.FrontierDeepGameTests;
import dev.brights0ng.paradynelib.services.Services;
import dev.brights0ng.seismicsurveyance.EchoesEvent;
import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import dev.brights0ng.seismicsurveyance.ThumpEvent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Frontier's side of Seismic Surveyance (split seams S5-S7). This package is the only part of Frontier that names
 * Seismic Surveyance's or Sparse Ores' classes, and it is reached only through {@link #registerIfLoaded}, so Frontier
 * runs without them: then no thumper shot ever reaches the Deep, thumper incursions find no thumpers, and the Deep's game
 * tests are not registered.
 */
public final class FrontierSeismic {

    /** Hooks the Deep up to Seismic Surveyance's thumper shots if it is present. Safe to call either way. */
    public static void registerIfLoaded(IEventBus modEventBus) {
        if (ModList.get().isLoaded(SeismicSurveyance.MODID)) {
            Hook.register(modEventBus);
        }
    }

    /** Kept separate so that Seismic Surveyance's classes are only looked up once it is known to be there. */
    private static final class Hook {

        static void register(IEventBus modEventBus) {
            // A thumper shot and its echoes set off what lives below (see Deep)
            NeoForge.EVENT_BUS.addListener(Hook::onThump);
            NeoForge.EVENT_BUS.addListener(Hook::onEchoes);
            // The Deep's pending sculk triggers and stirred deposits, as time passes
            NeoForge.EVENT_BUS.addListener(Hook::onLevelTick);
            NeoForge.EVENT_BUS.addListener(Hook::onLevelUnload);
            // /frontier deep
            NeoForge.EVENT_BUS.addListener(DeepCommand::onRegisterCommands);
            // The thumpers thumper incursions go after and choke
            Services.register(ThumperAccess.KEY, SeismicThumpers.INSTANCE);
            // The Deep's game tests need Seismic Surveyance's shots, so they are registered here rather than found as a
            // @GameTestHolder (NeoForge loads every holder, Seismic Surveyance or not)
            modEventBus.addListener(Hook::registerGameTests);
        }

        private static void registerGameTests(RegisterGameTestsEvent event) {
            event.register(FrontierDeepGameTests.class);
        }

        private static void onThump(ThumpEvent event) {
            Deep.of(event.level()).onThump(event.pos(), event.source(), event.range());
        }

        private static void onEchoes(EchoesEvent event) {
            Deep.of(event.level()).onEchoes(event.source(), event.echoers());
        }

        private static void onLevelTick(LevelTickEvent.Post event) {
            if (event.getLevel() instanceof ServerLevel level) {
                Deep.of(level).tick();
            }
        }

        private static void onLevelUnload(LevelEvent.Unload event) {
            if (event.getLevel() instanceof ServerLevel level) {
                Deep.forget(level);
            }
        }
    }

    private FrontierSeismic() {
    }
}
