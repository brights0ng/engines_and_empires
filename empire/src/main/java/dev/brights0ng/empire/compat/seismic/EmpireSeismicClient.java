// Written by Gemini.
package dev.brights0ng.empire.compat.seismic;

import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import dev.brights0ng.seismicsurveyance.client.MapLayers;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Empire's client side of Seismic Surveyance: claims and bonfires on the survey's maps
 * (the smart logger and the portable record display), {@link ClaimOverlay}. Reached only
 * through {@link #registerIfLoaded}, so Empire's client runs cleanly without Seismic Surveyance.
 */
public final class EmpireSeismicClient {

    public static void registerIfLoaded() {
        if (ModList.get().isLoaded(SeismicSurveyance.MODID)) {
            Hook.register();
        }
    }

    private static final class Hook {
        static void register() {
            MapLayers.register(ClaimOverlay.INSTANCE);
            NeoForge.EVENT_BUS.addListener(ClaimOverlay::onLoggingOut);
        }
    }

    private EmpireSeismicClient() {
    }
}
