package dev.brights0ng.enginesandempires.geophone.client;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.GeophoneGlow;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Client-side registration for the geophone: its renderer, and the small models each tier is drawn from. The models are
 * not attached to any block or item, so they have to be asked for by name, or the game would not load them.
 *
 * <p>This class is only ever loaded on a client.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class GeophoneClient {

    /** The body (spike, crossguard and rod), the sensor cap when it is dark, and the sensor cap when it glows. */
    public static final ModelResourceLocation BODY = model("block/andesite_geophone_body");
    public static final ModelResourceLocation CAP = model("block/andesite_geophone_cap");
    public static final ModelResourceLocation CAP_LIT = model("block/andesite_geophone_cap_lit");

    /**
     * Brass's own body and caps. Its glowing cap is a neutral grey-to-white texture rather than a fixed colour: see
     * {@link GeophoneRenderer}, which tints it with {@link GeophoneGlow#colorFor} at render time.
     */
    public static final ModelResourceLocation BRASS_BODY = model("block/brass_geophone_body");
    public static final ModelResourceLocation BRASS_CAP = model("block/brass_geophone_cap");
    public static final ModelResourceLocation BRASS_CAP_LIT = model("block/brass_geophone_cap_lit");

    private static ModelResourceLocation model(String path) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, path));
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SeismicContent.GEOPHONE_ENTITY.get(), GeophoneRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterModels(ModelEvent.RegisterAdditional event) {
        event.register(BODY);
        event.register(CAP);
        event.register(CAP_LIT);
        event.register(BRASS_BODY);
        event.register(BRASS_CAP);
        event.register(BRASS_CAP_LIT);
    }

    private GeophoneClient() {
    }
}
