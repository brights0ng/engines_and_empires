package dev.brights0ng.enginesandempires.weather.sky.client;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.cloud.CloudSources;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.sky.StormShade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The storm's shade at the camera (weather phase 6d; Bright, 2026-10-09): {@link StormShade} sampled once a tick at
 * the camera from the synced clouds, eased over {@value #EASE_SECONDS} s so a cloud update never pops. The fog, the
 * sky colour, the world's light ({@code ClientLevelSkyDarkenMixin}) and the sun, moon and stars
 * ({@code LevelRendererSunMixin}) all read it. Minecraft lights a frame with one value, so this follows where the
 * camera is (Bright's choice: camera-based dimming).
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class ClientStorm {

    static final double EASE_SECONDS = 0.75;

    private static double darkness;
    private static double prevDarkness;
    private static double cover;
    private static double prevCover;
    private static StormShade.Sample last = StormShade.Sample.CLEAR;
    private static boolean active;

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        prevDarkness = darkness;
        prevCover = cover;
        active = level != null && LocalWeather.enabled(level);
        if (!active) {
            darkness = prevDarkness = cover = prevCover = 0;
            last = StormShade.Sample.CLEAR;
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double t = level.getGameTime();
        last = StormShade.sample(new StormShade.Index(CloudSources.client(t), t), cam.x, cam.z);
        double a = 1 - Math.exp(-1 / (EASE_SECONDS * 20));
        darkness += (last.darkness() - darkness) * a;
        cover += (last.cover() - cover) * a;
    }

    /** Whether the pack's storms shade this client's sky (the Overworld, the pack's weather on). */
    public static boolean active() {
        return active;
    }

    /** The storm's darkness at the camera, eased (0-1). */
    public static double darkness(float partial) {
        return Mth.lerp(partial, prevDarkness, darkness);
    }

    /** How much the clouds overhead cover the sun, eased (0-1). */
    public static double cover(float partial) {
        return Mth.lerp(partial, prevCover, cover);
    }

    /**
     * How hidden the sun, moon and stars are by the clouds overhead (0-1), even without rain: fading in as the cover
     * passes 0.35 and fully hidden by 0.85.
     */
    public static double skyHidden(float partial) {
        double c = cover(partial);
        double t = Math.max(0, Math.min(1, (c - 0.35) / 0.5));
        return t * t * (3 - 2 * t);
    }

    /** The last raw sample (for the debug readout). */
    public static StormShade.Sample last() {
        return last;
    }

    private ClientStorm() {
    }
}
