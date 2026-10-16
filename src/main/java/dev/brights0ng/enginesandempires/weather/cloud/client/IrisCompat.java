package dev.brights0ng.enginesandempires.weather.cloud.client;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * Lets the clouds' own shaders draw while an Iris shader pack is on (2026-10-12).
 *
 * <p>Iris blanks every shader it doesn't know: right after such a shader is applied it turns colour and depth writes
 * off ({@code MixinShaderInstance}; its {@code allowUnknownShaders} setting, off by default, is the only way around it,
 * and it lets every mod's shaders through). So after applying one of ours, {@link #afterApply} undoes that and binds the
 * pack's fallback target (colortex0 for Complementary, with the scene depth), the same as Iris does for unknown
 * shaders when that setting is on; {@link #afterClear} rebinds the main target after, again as Iris does.
 *
 * <p>Uses Iris internals ({@code DepthColorStorage}, {@code IrisRenderingPipeline.bindDefault}), not just its API, so
 * every call into Iris is guarded: if a future Iris moves them, the clouds stay hidden under shaders (as before) and
 * the log says why, instead of crashing. Iris classes are only touched through {@link Impl}, only loaded with Iris
 * installed. Render thread.
 */
public final class IrisCompat {

    private static final boolean LOADED = ModList.get().isLoaded("iris");
    private static boolean broken;

    /** Whether a shader pack is drawing the world right now. */
    public static boolean packInUse() {
        if (!LOADED || broken) {
            return false;
        }
        try {
            return Impl.packInUse();
        } catch (LinkageError | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    /** Call right after applying one of the clouds' shaders: with a pack on, lets it draw into the pack's image. */
    public static void afterApply() {
        if (!packInUse()) {
            return;
        }
        try {
            Impl.afterApply();
        } catch (LinkageError | RuntimeException e) {
            fail(e);
        }
    }

    /** Call right after clearing one of the clouds' shaders. */
    public static void afterClear() {
        if (!packInUse()) {
            return;
        }
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
    }

    private static void fail(Throwable e) {
        broken = true;
        EnginesAndEmpiresMod.LOGGER.error("Clouds: this Iris version doesn't match the clouds' shader-pack support; "
                + "the clouds won't show while a shader pack is on", e);
    }

    /** The only code that names Iris classes. */
    private static final class Impl {
        static boolean packInUse() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
        }

        static void afterApply() {
            net.irisshaders.iris.gl.blending.DepthColorStorage.unlockDepthColor();
            if (net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable()
                    instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline pipeline) {
                pipeline.bindDefault();
            }
        }
    }

    private IrisCompat() {
    }
}
