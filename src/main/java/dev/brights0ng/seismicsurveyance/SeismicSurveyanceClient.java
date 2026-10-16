package dev.brights0ng.seismicsurveyance;

import dev.brights0ng.enginesandempires.geophone.client.PrdItemRenderer;
import dev.brights0ng.enginesandempires.geophone.client.ThumperClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Seismic Surveyance's client-side registration. Only called on the client. */
public final class SeismicSurveyanceClient {

    private SeismicSurveyanceClient() {
    }

    public static void init(IEventBus modEventBus, ModContainer container) {
        // Creates the thumper's partial models now, before models load, so Flywheel knows to bake them.
        ThumperClient.init();
        // Likewise the portable record display's model, which its item renderer draws.
        PrdItemRenderer.init();
    }
}
