package dev.brights0ng.enginesandempires.weather.fog.client;

import com.mojang.blaze3d.shaders.FogShape;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudInterior;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudRenderer;
import dev.brights0ng.enginesandempires.weather.fog.FogTuning;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The pack's fog (Bright, 2026-10-04; visual only). Two effects, the thicker one winning:
 *
 * <ul>
 *   <li><b>Inside a cloud</b> ({@link CloudInterior}): on the moment the camera enters a drawn cloud voxel, off the
 *       moment it leaves. Visibility by cloud type ({@link FogTuning#cloudVisibility}), as a sphere (up and down too),
 *       in the cloud's own grey lit by the sky. The sky (sun, moon, stars) is hidden ({@code LevelRendererSkyMixin}),
 *       and the cloud renderer fades the cloud's own faces into the fog ({@link CloudRenderer}).</li>
 *   <li><b>Rain and snow</b> falling on the camera ({@link LocalWeather}): visibility {@link FogTuning#rainVisibility},
 *       more rain thicker, snow twice as thick; the fog turns a light grey (dimming with the sky at dusk and night)
 *       instead of the sky's blue (Bright, 2026-10-04). Rain strength, rain-or-snow and being under a roof all ease over
 *       {@link FogTuning#rainEaseSeconds}, so nothing steps: a house in a storm isn't full of fog, and crossing the
 *       rain-snow line doesn't halve the visibility at once. Where the fog starts eases from the game's own start to the
 *       rain fog's as the rain thickens, so the fog doesn't jump closer the moment it becomes thicker than the game's.
 *       The cloud renderer doesn't follow this one: the storm overhead stays visible.</li>
 * </ul>
 *
 * <p>Both listeners run last ({@link EventPriority#LOWEST}, even for events already cancelled) and only ever tighten
 * the fog, so the game's own (water, blindness) still wins where it is thicker.
 */
public final class FogEffects {

    private static double rain;
    /** How far from rain to snow (0-1), eased. */
    private static double snow;
    private static double exposure;

    /** The cloud around the camera this frame, or null; and what the frame was. */
    private static CloudInterior.Inside inside;
    private static long frameTick = Long.MIN_VALUE;
    private static float framePartial = Float.NaN;
    private static Vec3 frameCamera = Vec3.ZERO;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ViewportEvent.ComputeFogColor.class,
                FogEffects::onFogColour);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ViewportEvent.RenderFog.class,
                FogEffects::onRenderFog);
        NeoForge.EVENT_BUS.addListener(FogEffects::onClientTick);
    }

    /** Whether the pack's fog is in charge (and Project Atmosphere's off). */
    public static boolean enabled() {
        return FogTuning.enabled;
    }

    /** The cloud the camera is inside this frame, or null. */
    public static CloudInterior.Inside insideCloud() {
        return FogTuning.enabled ? inside : null;
    }

    /** The rain fog's strength now (rain strength, eased, times how open to the sky the camera is). */
    public static double rainShown() {
        return rain * exposure;
    }

    /**
     * How far the fog and the sky are turned to the rain's grey (0-1): fully once the rain reaches
     * {@link FogTuning#rainFullGrey}. The sky and the fog use the same amount and the same grey, so the horizon has
     * no seam (Bright, 2026-10-04: the sky should change with the fog, as in vanilla rain).
     */
    public static double rainTint() {
        if (!FogTuning.enabled) {
            return 0;
        }
        return Math.min(1, Math.min(1, rainShown()) / Math.max(0.01, FogTuning.rainFullGrey));
    }

    /**
     * The rain's grey: a neutral grey of brightness {@link FogTuning#rainFogBrightness} (0 black to 1 white), lit by
     * the day-night light: full in daylight, {@link FogTuning#rainNightBrightness} of it at night (darker than the
     * clouds' own sky light, Bright 2026-10-04: night rain fog was brighter than the clouds), blended at dusk and dawn
     * on vanilla's day-night curve.
     */
    public static Vec3 rainColour(ClientLevel level, float partial) {
        double day = Math.max(0, Math.min(1, Math.cos(level.getTimeOfDay(partial) * Math.PI * 2) * 2 + 0.5));
        double night = FogTuning.rainNightBrightness;
        double grey = FogTuning.rainFogBrightness * (night + (1 - night) * day);
        return new Vec3(grey, grey, grey);
    }

    /** {@code colour} turned toward the rain's grey by {@link #rainTint}. */
    public static Vec3 rainTinted(ClientLevel level, float partial, Vec3 colour) {
        double k = rainTint();
        if (k <= 1e-4) {
            return colour;
        }
        Vec3 grey = rainColour(level, partial);
        return colour.add(grey.subtract(colour).scale(k));
    }

    // ---- per tick: the rain at the camera ------------------------------------------------------------------------

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !FogTuning.enabled) {
            reset();
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double target;
        boolean snowing;
        if (LocalWeather.enabled(level)) {
            LocalWeather.Here here = LocalWeather.at(level, cam.x, cam.y, cam.z);
            target = here.falling() ? here.strength() : 0;
            snowing = here.snow();
        } else {
            // The pack's rain model is off: follow the game's own rain.
            target = level.isRaining() ? level.getRainLevel(1f) : 0;
            snowing = level.getBiome(BlockPos.containing(cam)).value().coldEnoughToSnow(BlockPos.containing(cam));
        }
        double open = level.canSeeSky(BlockPos.containing(cam)) ? 1 : 0;
        double seconds = FogTuning.rainEaseSeconds;
        double a = seconds <= 0 ? 1 : 1 - Math.exp(-1 / (seconds * 20));
        rain += (target - rain) * a;
        exposure += (open - exposure) * a;
        snow += ((snowing ? 1 : 0) - snow) * a;
    }

    private static void reset() {
        rain = 0;
        exposure = 0;
        snow = 0;
        inside = null;
        frameTick = Long.MIN_VALUE;
    }

    // ---- per frame: the cloud around the camera ------------------------------------------------------------------

    /** Works out {@link #inside} once per frame (the fog events and the renderers all read it). */
    private static void updateFrame(Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !FogTuning.enabled || !CloudRenderer.active(level)) {
            inside = null;
            return;
        }
        // The same moment the clouds are drawn at (CloudRenderer).
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        long tick = level.getGameTime();
        Vec3 pos = camera.getPosition();
        if (tick == frameTick && partial == framePartial && pos.equals(frameCamera)) {
            return;
        }
        frameTick = tick;
        framePartial = partial;
        frameCamera = pos;
        inside = camera.getFluidInCamera() == FogType.NONE
                ? CloudInterior.at(pos.x, pos.y, pos.z, tick + (double) partial) : null;
    }

    private static void onFogColour(ViewportEvent.ComputeFogColor event) {
        updateFrame(event.getCamera());
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !FogTuning.enabled) {
            return;
        }
        CloudInterior.Inside in = inside;
        if (in != null) {
            Vec3 tint = CloudRenderer.tint(level, (float) event.getPartialTick());
            event.setRed(clamp((float) (in.red() * tint.x)));
            event.setGreen(clamp((float) (in.green() * tint.y)));
            event.setBlue(clamp((float) (in.blue() * tint.z)));
            return;
        }
        if (rainTint() <= 1e-4) {
            return;
        }
        Vec3 c = rainTinted(level, (float) event.getPartialTick(),
                new Vec3(event.getRed(), event.getGreen(), event.getBlue()));
        event.setRed(clamp((float) c.x));
        event.setGreen(clamp((float) c.y));
        event.setBlue(clamp((float) c.z));
    }

    private static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getType() != FogType.NONE || !FogTuning.enabled) {
            return;
        }
        updateFrame(event.getCamera());
        float far = event.getFarPlaneDistance();
        float near = event.getNearPlaneDistance();
        CloudInterior.Inside in = inside;
        if (in != null) {
            float visibility = (float) in.visibility();
            event.setFarPlaneDistance(Math.min(far, visibility));
            event.setNearPlaneDistance(0);
            event.setFogShape(FogShape.SPHERE);
            event.setCanceled(true);
            return;
        }
        double visibility = FogTuning.rainVisibility(rainShown(), snow);
        // Continuous in the rain's strength, with no point where the fog switches from the game's to the rain's: the
        // fog's end comes in as visibility drops below the game's, and its start slides from the game's start (most of
        // the way out) to the rain fog's (right at you, by default) as the rain thickens, by the same amount the
        // colour turns grey. So the fog thickens from the camera outward rather than standing as a wall.
        double w = rainTint();
        if (w <= 1e-4 && visibility >= far) {
            return;
        }
        double end = Math.min(far, visibility);
        double gameStart = Math.min(near, far) / far * end;
        double rainStart = end * FogTuning.rainFogStart;
        event.setFarPlaneDistance((float) end);
        event.setNearPlaneDistance((float) Math.min(near, gameStart + (rainStart - gameStart) * w));
        event.setCanceled(true);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private FogEffects() {
    }
}
