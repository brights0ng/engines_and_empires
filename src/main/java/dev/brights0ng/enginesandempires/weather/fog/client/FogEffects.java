package dev.brights0ng.enginesandempires.weather.fog.client;

import com.mojang.blaze3d.shaders.FogShape;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudInterior;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudRenderer;
import dev.brights0ng.enginesandempires.weather.fog.FogTuning;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.sky.client.ClientStorm;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The pack's fog (Bright, 2026-10-04; storm rework 2026-10-09, phase 6d; visual only). Two effects, the thicker one
 * winning:
 *
 * <ul>
 *   <li><b>Inside a cloud</b> ({@link CloudInterior}): on the moment the camera enters a drawn cloud voxel, off the
 *       moment it leaves. Visibility by cloud type ({@link FogTuning#cloudVisibility}), as a sphere (up and down too),
 *       in the cloud's own grey lit by the sky. The sky (sun, moon, stars) is hidden ({@code LevelRendererSkyMixin}),
 *       and the cloud renderer fades the cloud's own faces into the fog ({@link CloudRenderer}).</li>
 *   <li><b>Storm and rain fog</b> outdoors: one extinction ({@link FogTuning#extinction}) from the rain or snow
 *       falling on the camera and the storm's darkness overhead and around ({@link ClientStorm}). The fog's distance,
 *       where it starts, and how far the fog and sky turn to the storm's colour all follow that one number, so the fog
 *       thickens smoothly as a storm darkens and its rain arrives, with no steps. Its colour is the rain's neutral
 *       grey, darker and slightly blue-grey as the storm darkens (Bright, 2026-10-09). How open the camera is to the
 *       sky (its sky light, eased) scales the fog, so a house in a storm isn't full of it; the sky's tint doesn't, so
 *       the storm outside a window still looks stormy. The cloud renderer doesn't follow this fog: the storm overhead
 *       stays visible.</li>
 * </ul>
 *
 * <p>Both listeners run last ({@link EventPriority#LOWEST}, even for events already cancelled) and only ever tighten
 * the fog, so the game's own (water, blindness) still wins where it is thicker.
 */
public final class FogEffects {

    /** The rain or snow falling on the camera (strength 0-1), eased. */
    private static double rain;
    /** How far from rain to snow (0-1), eased. */
    private static double snow;
    /** How open the camera is to the sky (its sky light / 15), eased. */
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

    /** Whether the pack's fog is in charge. */
    public static boolean enabled() {
        return FogTuning.enabled;
    }

    /** The cloud the camera is inside this frame, or null. */
    public static CloudInterior.Inside insideCloud() {
        return FogTuning.enabled ? inside : null;
    }

    /** The air's extinction at the camera, as if out in the open (rain, snow and the storm's haze). */
    public static double extinction(float partial) {
        return FogTuning.extinction(rain, snow, ClientStorm.darkness(partial));
    }

    /**
     * How far the sky and the fog are turned to the storm's colour (0-1). The sky and the fog use the same amount and
     * the same colour, so the horizon has no seam.
     */
    public static double skyTint(float partial) {
        if (!FogTuning.enabled) {
            return 0;
        }
        return FogTuning.tint(extinction(partial));
    }

    /**
     * The storm's colour: the rain's neutral grey ({@link FogTuning#rainFogBrightness}, lit by the day-night light:
     * {@link FogTuning#rainNightBrightness} of it at night), darker ({@link FogTuning#stormDarkening}) and slightly
     * blue-grey ({@link FogTuning#stormBlue}) as the storm darkens.
     */
    public static Vec3 stormColour(ClientLevel level, float partial) {
        double day = Math.max(0, Math.min(1, Math.cos(level.getTimeOfDay(partial) * Math.PI * 2) * 2 + 0.5));
        double night = FogTuning.rainNightBrightness;
        double grey = FogTuning.rainFogBrightness * (night + (1 - night) * day);
        double d = ClientStorm.darkness(partial);
        double b = grey * (1 - FogTuning.stormDarkening * d);
        double blue = FogTuning.stormBlue * d;
        return new Vec3(clamp(b * (1 - 0.6 * blue)), clamp(b * (1 - 0.15 * blue)), clamp(b * (1 + blue)));
    }

    /** {@code colour} turned toward the storm's colour by {@link #skyTint}. */
    public static Vec3 stormTinted(ClientLevel level, float partial, Vec3 colour) {
        double k = skyTint(partial);
        if (k <= 1e-4) {
            return colour;
        }
        Vec3 c = stormColour(level, partial);
        return colour.add(c.subtract(colour).scale(k));
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
        BlockPos at = BlockPos.containing(cam);
        double target;
        boolean snowing;
        if (LocalWeather.enabled(level)) {
            LocalWeather.Here here = LocalWeather.at(level, cam.x, cam.y, cam.z);
            target = here.falling() ? here.strength() : 0;
            snowing = here.snow();
        } else {
            // The pack's rain model is off: follow the game's own rain.
            target = level.isRaining() ? level.getRainLevel(1f) : 0;
            snowing = level.getBiome(at).value().coldEnoughToSnow(at);
        }
        // Sky light rather than a yes/no "can see the sky": under an overhang or in a doorway the fog is partial.
        double open = level.getBrightness(LightLayer.SKY, at) / 15.0;
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
            event.setRed((float) clamp(in.red() * tint.x));
            event.setGreen((float) clamp(in.green() * tint.y));
            event.setBlue((float) clamp(in.blue() * tint.z));
            return;
        }
        float partial = (float) event.getPartialTick();
        if (skyTint(partial) <= 1e-4) {
            return;
        }
        Vec3 c = stormTinted(level, partial, new Vec3(event.getRed(), event.getGreen(), event.getBlue()));
        event.setRed((float) clamp(c.x));
        event.setGreen((float) clamp(c.y));
        event.setBlue((float) clamp(c.z));
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
        // One continuous extinction, scaled by how open the camera is: the fog's end comes in smoothly from the game's
        // own as it rises, and its start slides from the game's start (most of the way out) toward the storm fog's
        // (right at you, by default) by the same amount the colour turns, so the fog thickens from the camera outward
        // rather than standing as a wall.
        double ext = extinction((float) event.getPartialTick()) * exposure;
        if (ext * far < 1e-3) {
            return;
        }
        double end = Math.min(far, FogTuning.visibility(ext, far));
        double w = FogTuning.tint(ext);
        double gameStart = Math.min(near, far) / far * end;
        double stormStart = end * FogTuning.rainFogStart;
        event.setFarPlaneDistance((float) end);
        event.setNearPlaneDistance((float) Math.min(near, gameStart + (stormStart - gameStart) * w));
        event.setCanceled(true);
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private FogEffects() {
    }
}
