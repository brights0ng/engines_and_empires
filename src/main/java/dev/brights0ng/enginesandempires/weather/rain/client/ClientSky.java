package dev.brights0ng.enginesandempires.weather.rain.client;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The client's rain and thunder levels where the pack owns the weather: what is falling on the open air above the
 * camera, eased over a couple of seconds. {@code mixin/client/LevelRainLevelMixin} hands these to the game in place of
 * vanilla's global levels, so everything built on them follows the local weather: the sky darkening, rain and thunder
 * levels other mods read, the cloud tint.
 *
 * <p>Snow darkens the sky as rain does (vanilla's rain level covers both). Thunder never exceeds rain.
 */
public final class ClientSky {

    /** Time constant of the easing, ticks. */
    static final double EASE_TICKS = 40;

    private static float rain;
    private static float prevRain;
    private static float thunder;
    private static float prevThunder;

    /** Steps toward the weather above the camera. Once per client tick, after the clouds are updated. */
    public static void tick(ClientLevel level) {
        prevRain = rain;
        prevThunder = thunder;
        double targetRain = 0;
        double targetThunder = 0;
        if (WeatherOwnership.owns(level)) {
            Vec3 c = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            LocalWeather.Here h = WeatherQueries.overhead(level, c.x, c.y, c.z);
            if (h.falling()) {
                targetRain = Math.min(1, h.strength());
                targetThunder = h.thunder() ? targetRain : 0;
            }
        }
        double a = 1 - Math.exp(-1 / EASE_TICKS);
        rain += (float) ((targetRain - rain) * a);
        thunder += (float) ((targetThunder - thunder) * a);
        thunder = Math.min(thunder, rain);
    }

    public static float rain(float partialTick) {
        return Mth.lerp(partialTick, prevRain, rain);
    }

    public static float thunder(float partialTick) {
        return Mth.lerp(partialTick, prevThunder, thunder);
    }

    public static void clear() {
        rain = prevRain = thunder = prevThunder = 0;
    }

    private ClientSky() {
    }
}
