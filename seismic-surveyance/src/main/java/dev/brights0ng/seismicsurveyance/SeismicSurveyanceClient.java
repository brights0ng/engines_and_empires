package dev.brights0ng.seismicsurveyance;

import dev.brights0ng.seismicsurveyance.client.PrdItemRenderer;
import dev.brights0ng.seismicsurveyance.client.ThumperClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/** Seismic Surveyance's client-side registration (its own client entry point since split step 2e). */
@Mod(value = SeismicSurveyance.MODID, dist = Dist.CLIENT)
public final class SeismicSurveyanceClient {

    public SeismicSurveyanceClient(IEventBus modEventBus, ModContainer container) {
        init(modEventBus, container);
    }

    private static void init(IEventBus modEventBus, ModContainer container) {
        // Creates the thumper's partial models now, before models load, so Flywheel knows to bake them.
        ThumperClient.init();
        // Likewise the portable record display's model, which its item renderer draws.
        PrdItemRenderer.init();
    }
}
