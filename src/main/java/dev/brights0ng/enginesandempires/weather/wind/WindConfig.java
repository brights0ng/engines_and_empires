package dev.brights0ng.enginesandempires.weather.wind;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Wind push settings. A SERVER config ({@code serverconfig/engines_and_empires-wind-server.toml} in the world folder).
 * Code reads them through {@link #params()}, which falls back to {@link WindParams#DEFAULTS} before the config loads.
 */
public final class WindConfig {

    public static final String FILE_NAME = "engines_and_empires-wind-server.toml";

    private static final WindParams D = WindParams.DEFAULTS;
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("How Project Atmosphere's wind pushes physics objects (Sable sub-levels: airships, boats, vehicles).")
                .push("push");
    }

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Whether wind pushes physics objects at all.")
            .define("enabled", D.enabled());

    public static final ModConfigSpec.DoubleValue PUSH_COEFFICIENT = BUILDER
            .comment("Force per square metre of silhouette per (m/s)^2 of wind, at sea-level air pressure.",
                    "The push is coefficient x air pressure x exposure x area x closing speed squared.",
                    "A first guess: raise it to make wind stronger, lower it to make it weaker.")
            .defineInRange("pushCoefficient", D.pushCoefficient(), 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue GUST_RAMP_SECONDS = BUILDER
            .comment("Seconds a rise in wind speed (a gust) takes to build up on a ship. Drops follow at once.")
            .defineInRange("gustRampSeconds", D.gustRampTicks() / 20.0, 0.0, 30.0);

    static {
        BUILDER.pop().comment("Where surface wind gives way to the faster aloft wind.").push("altitude");
    }

    public static final ModConfigSpec.DoubleValue SURFACE_LAYER = BUILDER
            .comment("Blocks above the ground that feel only the surface wind.")
            .defineInRange("surfaceLayer", D.surfaceLayer(), 0.0, 512.0);

    public static final ModConfigSpec.DoubleValue ALOFT_HEIGHT = BUILDER
            .comment("Height (y) from which only the aloft wind is felt. Meant to match the cloud base.")
            .defineInRange("aloftHeight", D.aloftHeight(), -64.0, 2048.0);

    static {
        BUILDER.pop().comment("How blocks around a ship shelter it from the wind.").push("shelter");
    }

    public static final ModConfigSpec.IntValue SHELTER_INTERVAL = BUILDER
            .comment("Ticks between shelter checks of each ship.")
            .defineInRange("interval", D.shelterInterval(), 1, 1200);

    public static final ModConfigSpec.IntValue SIDE_REACH = BUILDER
            .comment("How far out (blocks) the check looks from each side of a ship for cover.")
            .defineInRange("sideReach", D.sideReach(), 1, 64);

    public static final ModConfigSpec.IntValue ROOF_REACH = BUILDER
            .comment("How far up (blocks) the check looks from the top of a ship for cover.")
            .defineInRange("roofReach", D.roofReach(), 1, 128);

    public static final ModConfigSpec.DoubleValue ROOF_WEIGHT = BUILDER
            .comment("How much a full roof cuts the push on its own (0.5 = halves it). Walls facing into the wind",
                    "shelter fully; walls facing away from it don't shelter at all.")
            .defineInRange("roofWeight", D.roofWeight(), 0.0, 1.0);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static WindParams params() {
        if (!SPEC.isLoaded()) {
            return D;
        }
        return new WindParams(ENABLED.get(), PUSH_COEFFICIENT.get(), (int) Math.round(GUST_RAMP_SECONDS.get() * 20),
                SURFACE_LAYER.get(), ALOFT_HEIGHT.get(), SHELTER_INTERVAL.get(), SIDE_REACH.get(), ROOF_REACH.get(),
                ROOF_WEIGHT.get());
    }

    private WindConfig() {
    }
}
