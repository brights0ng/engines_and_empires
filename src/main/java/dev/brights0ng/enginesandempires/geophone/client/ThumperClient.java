package dev.brights0ng.enginesandempires.geophone.client;

import org.joml.Quaternionf;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client-side registration for the mechanical thumper.
 *
 * <p>The housing is an ordinary block model, registered through its blockstate. Everything that moves or glows is drawn
 * separately, the way Create draws its own machines:
 * <ul>
 *   <li>{@link ThumperVisual}, with Flywheel on (the default): the cog and the head, as GPU instances.</li>
 *   <li>{@link ThumperRenderer}, always: the lit lamp; and the cog and head too, whenever Flywheel is off.</li>
 * </ul>
 *
 * <p>The extra models are Flywheel {@link PartialModel}s, which load themselves (no {@code ModelEvent} needed), but only
 * if they exist before models load. {@link #init} is called from the client mod constructor to make sure of that.
 *
 * <p>This class is only ever loaded on a client.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class ThumperClient {

    public static final PartialModel HEAD = PartialModel.of(model("block/mechanical_thumper_head"));
    public static final PartialModel LAMP_LIT = PartialModel.of(model("block/mechanical_thumper_lamp_lit"));

    // The combustive thumper's moving and glowing parts.
    public static final PartialModel COMBUSTIVE_RAM = PartialModel.of(model("block/combustive_thumper_ram"));
    public static final PartialModel COMBUSTIVE_RODS = PartialModel.of(model("block/combustive_thumper_rods"));
    public static final PartialModel COMBUSTIVE_NEEDLE = PartialModel.of(model("block/combustive_thumper_needle"));
    public static final PartialModel COMBUSTIVE_LAMP_LIT = PartialModel.of(model("block/combustive_thumper_lamp_lit"));

    /** The smart logger's buttons: a small bronze push button, standing on the bar. */
    public static final PartialModel LOGGER_BUTTON = PartialModel.of(model("block/smart_logger_button"));

    /** A quarter turn around Y, the same one the blockstate gives the housing for the X axis. */
    private static final Quaternionf X_AXIS_TURN = new Quaternionf().rotationY((float) Math.toRadians(-90));

    /** Loads this class, and with it the partial models above, early enough for them to be baked. */
    public static void init() {
    }

    /**
     * How the head and lamp (modelled for the Z axis) must be turned to line up with the housing in this state. A
     * blockstate's {@code "y": 90} turns a model by -90 degrees around Y, so this does the same.
     */
    public static Quaternionf modelRotation(BlockState state) {
        return state.getValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS) == Axis.X
                ? new Quaternionf(X_AXIS_TURN) : new Quaternionf();
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(SeismicContent.MECHANICAL_THUMPER_ENTITY.get(), ThumperRenderer::new);
        event.registerBlockEntityRenderer(SeismicContent.COMBUSTIVE_THUMPER_ENTITY.get(), CombustiveThumperRenderer::new);
        event.registerBlockEntityRenderer(SeismicContent.SMART_LOGGER_ENTITY.get(), SmartLoggerRenderer::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Never skip the ordinary renderer: it always draws the lamp, and checks for itself whether Flywheel is on.
        event.enqueueWork(() -> {
            SimpleBlockEntityVisualizer.builder(SeismicContent.MECHANICAL_THUMPER_ENTITY.get())
                    .factory(ThumperVisual::new)
                    .neverSkipVanillaRender()
                    .apply();
            // The combustive thumper: its shaft, ram and lift rods. The renderer still always draws the gauges and lamp.
            SimpleBlockEntityVisualizer.builder(SeismicContent.COMBUSTIVE_THUMPER_ENTITY.get())
                    .factory(CombustiveThumperVisual::new)
                    .neverSkipVanillaRender()
                    .apply();
            // The smart logger: each quarter's cog, turning like the mixer's. The renderer still draws the map and buttons.
            SimpleBlockEntityVisualizer.builder(SeismicContent.SMART_LOGGER_ENTITY.get())
                    .factory(SingleAxisRotatingVisual.of(AllPartialModels.SHAFTLESS_COGWHEEL))
                    .neverSkipVanillaRender()
                    .apply();
        });
    }

    private static ResourceLocation model(String path) {
        return ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, path);
    }

    private ThumperClient() {
    }
}
