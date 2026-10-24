// Written by Gemini.
// Edited by Gemini: registered TalcBorderRenderer for client-side empire perimeter border visualization when holding talc.
// Edited by Claude: TalcBorderRenderer now registers its own listeners (render, block changes, chunk loads).
// Edited by Claude: ClaimCacheKeeper keeps the claims around the player fresh (protection prediction, talc borders).
package dev.brights0ng.empire;

import dev.brights0ng.empire.client.ClaimCacheKeeper;
import dev.brights0ng.empire.client.TalcBorderRenderer;
import dev.brights0ng.empire.compat.seismic.EmpireSeismicClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Empire on the client. With Seismic Surveyance installed, it registers its claim overlay
 * ({@link dev.brights0ng.empire.compat.seismic.ClaimOverlay}) on the survey's maps.
 * Also registers {@link TalcBorderRenderer} for rendering perimeter boundary curtains and talc lines
 * when holding Blessed or Tarnished Talc.
 */
@Mod(value = Empire.MODID, dist = Dist.CLIENT)
public final class EmpireClient {

    public EmpireClient(IEventBus modEventBus, ModContainer container) {
        EmpireSeismicClient.registerIfLoaded();
        ClaimCacheKeeper.register();
        TalcBorderRenderer.register();
    }
}
