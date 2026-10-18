package dev.brights0ng.frontier.seismic.client;

import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import dev.brights0ng.seismicsurveyance.client.MapLayers;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Frontier's client side of Seismic Surveyance: its tiers and incursions on the survey's maps (the smart logger and the
 * portable record display), {@link TierOverlay}. Reached only through {@link #registerIfLoaded}, so Frontier's client
 * runs without Seismic Surveyance; then there are no such maps to draw on. Since split step 4b Frontier registers the
 * layer itself (the core's client setup did before), so Frontier and Seismic draw it without the core.
 */
public final class FrontierSeismicClient {

    /** Puts the tier layer on Seismic Surveyance's maps if it is present. Safe to call either way. */
    public static void registerIfLoaded() {
        if (ModList.get().isLoaded(SeismicSurveyance.MODID)) {
            Hook.register();
        }
    }

    /** Kept separate so that Seismic Surveyance's classes are only looked up once it is known to be there. */
    private static final class Hook {

        static void register() {
            MapLayers.register(TierOverlay.INSTANCE);
            NeoForge.EVENT_BUS.addListener(TierOverlay::onLoggingOut);
        }
    }

    private FrontierSeismicClient() {
    }
}
