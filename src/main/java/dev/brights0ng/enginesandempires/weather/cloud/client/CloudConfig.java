package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Cloud renderer settings. A CLIENT config ({@code config/engines_and_empires-clouds-client.toml}): each player picks
 * their own, to suit their PC.
 */
public final class CloudConfig {

    public static final String FILE_NAME = "engines_and_empires-clouds-client.toml";

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("The pack's voxel cloud renderer. It draws the clouds the weather simulation makes.")
                .push("clouds");
    }

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Whether the voxel cloud renderer draws at all. Off gives vanilla clouds back.")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue VOXEL_SIZE = BUILDER
            .comment("Size of one cloud voxel near you, in blocks: 2, 4, 8 or 16. Smaller is more detailed and costs more.",
                    "Clouds further away use bigger voxels automatically.")
            .defineInRange("voxelSize", 4, 2, 16);

    public static final ModConfigSpec.IntValue DRAW_DISTANCE = BUILDER
            .comment("How far away clouds are drawn, in blocks. They fade into the sky toward this distance.")
            .defineInRange("drawDistance", 3072, 512, 8192);

    public static final ModConfigSpec.DoubleValue REBUILD_SECONDS = BUILDER
            .comment("The shortest time between rebuilds of one cloud formation, in seconds: clouds slowly churn, and",
                    "pick up the simulation's changes (growing, shrinking, changing type). A whole formation is",
                    "rebuilt at once and swapped in when done, so big storms may take longer than this (see",
                    "lod.rebuildBudget). Moving a cloud is free.")
            .defineInRange("rebuildSeconds", 1.0, 0.25, 30.0);

    static {
        BUILDER.pop().comment("Level of detail: clouds are cut into cubes (sections), each with its own voxel size by",
                "distance. Changes apply live.").push("lod");
    }

    public static final ModConfigSpec.IntValue SECTION_SIZE = BUILDER
            .comment("Section side in blocks: 256, 512 or 1024. Smaller sections follow distance more closely; larger",
                    "ones mean fewer seams and draw calls.")
            .defineInRange("sectionSize", 512, 256, 1024);

    public static final ModConfigSpec.DoubleValue LOD_DISTANCE_1 = BUILDER
            .comment("Distance (blocks) past which sections use 2x the voxel size.")
            .defineInRange("distance2x", 384.0, 0.0, 100000.0);
    public static final ModConfigSpec.DoubleValue LOD_DISTANCE_2 = BUILDER
            .comment("Distance past which sections use 4x the voxel size.")
            .defineInRange("distance4x", 1024.0, 0.0, 100000.0);
    public static final ModConfigSpec.DoubleValue LOD_DISTANCE_3 = BUILDER
            .comment("Distance past which sections use 8x the voxel size.")
            .defineInRange("distance8x", 2048.0, 0.0, 100000.0);
    public static final ModConfigSpec.DoubleValue LOD_DISTANCE_4 = BUILDER
            .comment("Distance past which sections use 16x the voxel size.")
            .defineInRange("distance16x", 4096.0, 0.0, 100000.0);

    public static final ModConfigSpec.IntValue MAX_VOXEL = BUILDER
            .comment("The largest voxel size, in blocks (snapped to 8, 16, 32, 64 or 128).")
            .defineInRange("maxVoxel", 64, 8, 128);

    public static final ModConfigSpec.DoubleValue REBUILD_BUDGET = BUILDER
            .comment("Background CPU for rebuilding clouds, in cores (0.5 = half of one core). Lower makes big storms",
                    "change in slower steps; higher costs more CPU. First builds of new clouds don't wait for it.")
            .defineInRange("rebuildBudget", 0.5, 0.05, 4.0);

    public static final ModConfigSpec.IntValue MESHER_THREADS = BUILDER
            .comment("Background threads building cloud meshes. Takes effect after a restart.")
            .defineInRange("mesherThreads", 2, 1, 4);

    static {
        BUILDER.pop().comment("Cloud shapes, for experimenting. Multipliers on the built-in design: 1.0 is as designed.",
                "They only change how clouds look on this client (rain and lightning won't follow them). Changes",
                "apply live.").push("shape");
    }

    public static final ModConfigSpec.DoubleValue NOISE_SCALE = shape("noiseScale",
            "Size of the churning lumps and billows, relative to the cloud's size.");
    public static final ModConfigSpec.DoubleValue NOISE_STRENGTH = shape("noiseStrength",
            "How strongly the churn changes the outline.");
    public static final ModConfigSpec.DoubleValue BUBBLE_SMOOTHING = BUILDER
            .comment("How smoothly a cumulus's bubbles blend into each other: 1 = crisp creases between bubbles,",
                    "higher fills them in (softer, rounder lumps), 0.5 = sharper.")
            .defineInRange("bubbleSmoothing", 1.6, 0.5, 4.0);
    private static ModConfigSpec.DoubleValue shape(String path, String... comment) {
        return BUILDER.comment(comment).defineInRange(path, 1.0, 0.0, 10.0);
    }

    static {
        BUILDER.pop().comment("Cloud shading: a cloud is grey where there is a lot of cloud above, and the more water it",
                "holds the faster it darkens (real optical depth). Changes rebuild the clouds.").push("shading");
    }

    public static final ModConfigSpec.DoubleValue WATER_CONTENT = BUILDER
            .comment("Multiplier on every cloud type's water content. Higher makes thick clouds darker.")
            .defineInRange("waterContent", 1.0, 0.0, 10.0);
    public static final ModConfigSpec.DoubleValue BASE_LIGHT = BUILDER
            .comment("The darkest a cloud's underside gets (0 black, 1 white): sky light reaches even a storm's base.")
            .defineInRange("baseLight", 0.22, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue SHADE_CONTRAST = BUILDER
            .comment("How quickly clouds darken with depth (0.1 to 1). Higher darkens even modest clouds; lower saves",
                    "the grey for the very thickest.")
            .defineInRange("contrast", 0.35, 0.05, 1.0);
    public static final ModConfigSpec.DoubleValue SHADOW_SIDE = BUILDER
            .comment("How bright a cloud's side away from the sun (or moon) is, as a share of the sunlit side.",
                    "1 = no sun shading. Applies live.")
            .defineInRange("shadowSide", 0.65, 0.2, 1.0);
    public static final ModConfigSpec.DoubleValue CREASES = BUILDER
            .comment("Cumulus: how strongly the creases between bubbles darken (0 = not at all).")
            .defineInRange("creases", 0.35, 0.0, 0.8);
    public static final ModConfigSpec.DoubleValue SILVER_LINING = BUILDER
            .comment("Cumulus: how strongly thin edges glow when you look toward the sun (0 = no silver lining).",
                    "Applies live.")
            .defineInRange("silverLining", 0.9, 0.0, 3.0);
    public static final ModConfigSpec.DoubleValue CLOUD_SHADOWS = BUILDER
            .comment("How strongly clouds shade the clouds below them: a cumulus under a thick deck goes grey (0 = not at",
                    "all, 1 = realistic). Changes rebuild the clouds.")
            .defineInRange("cloudShadows", 1.0, 0.0, 2.0);

    static {
        BUILDER.pop().comment("Wisps: soft haze along the edges of nearby cumulus and ragged shreds under their bases,",
                "drifting off and fading. Changes apply live.").push("wisps");
    }

    public static final ModConfigSpec.BooleanValue WISPS_ENABLED = BUILDER
            .comment("Whether wisps are drawn.")
            .define("enabled", true);
    public static final ModConfigSpec.DoubleValue WISP_DISTANCE = BUILDER
            .comment("How far away (blocks, to the cloud's edge) cumulus get wisps.")
            .defineInRange("distance", 300.0, 0.0, 2048.0);
    public static final ModConfigSpec.DoubleValue WISP_DENSITY = BUILDER
            .comment("How many wisps a cloud has, as a multiplier (forming and dying clouds have more).")
            .defineInRange("density", 1.0, 0.0, 5.0);
    public static final ModConfigSpec.DoubleValue WISP_OPACITY = BUILDER
            .comment("How opaque the wisps are, as a multiplier.")
            .defineInRange("opacity", 1.0, 0.0, 3.0);

    static {
        BUILDER.pop().comment("The see-through high clouds: cirrostratus strips and veils, and cirrus. Changes apply live.")
                .push("veils");
    }

    public static final ModConfigSpec.DoubleValue VEIL_OPACITY = BUILDER
            .comment("How opaque cirrostratus and cirrus are, as a multiplier (0 hides them).")
            .defineInRange("opacity", 1.0, 0.0, 3.0);

    static {
        BUILDER.pop().comment("How rain and snow are drawn. Changes apply live.").push("rain");
    }

    public static final ModConfigSpec.DoubleValue RAIN_SLANT_SECONDS = BUILDER
            .comment("Rain and snow lean with the wind averaged over this many seconds, so gusts don't swing the",
                    "streaks. A gust still bends rain that starts falling after it, travelling down the streak.")
            .defineInRange("slantAverageSeconds", 15.0, 1.0, 120.0);

    public static final ModConfigSpec.DoubleValue RAIN_SLANT_RATE = BUILDER
            .comment("The most the lean may change per second (blocks sideways per block of fall).")
            .defineInRange("slantMaxChangePerSecond", 0.1, 0.01, 2.0);

    static {
        BUILDER.pop().comment("Fog inside clouds and in rain or snow. Turning it on also turns Project Atmosphere's fog",
                "off. Visibility is in blocks. Changes apply live.").push("fog");
    }

    public static final ModConfigSpec.BooleanValue FOG_ENABLED = BUILDER
            .comment("Whether the pack's fog is on. Off gives Project Atmosphere's fog back.")
            .define("enabled", true);

    public static final ModConfigSpec.DoubleValue FOG_STORM_CLOUD = BUILDER
            .comment("Visibility inside cumulonimbus and nimbostratus. 0 = no fog.")
            .defineInRange("cloud.stormVisibility", 5.0, 0.0, 512.0);
    public static final ModConfigSpec.DoubleValue FOG_CONGESTUS = BUILDER
            .comment("Visibility inside cumulus congestus and stratus. 0 = no fog.")
            .defineInRange("cloud.congestusVisibility", 8.0, 0.0, 512.0);
    public static final ModConfigSpec.DoubleValue FOG_FAIR_CLOUD = BUILDER
            .comment("Visibility inside stratocumulus and fair-weather cumulus. 0 = no fog.")
            .defineInRange("cloud.fairVisibility", 12.0, 0.0, 512.0);
    public static final ModConfigSpec.DoubleValue FOG_HIGH_CLOUD = BUILDER
            .comment("Visibility inside cirrus and vapour. 0 = no fog.")
            .defineInRange("cloud.highVisibility", 0.0, 0.0, 512.0);
    public static final ModConfigSpec.DoubleValue FOG_WISP = BUILDER
            .comment("Visibility inside a cloud that is only just forming or nearly gone: a cloud's visibility eases",
                    "toward this as it forms or erodes.")
            .defineInRange("cloud.wispVisibility", 48.0, 0.0, 1024.0);

    public static final ModConfigSpec.DoubleValue FOG_HEAVY_RAIN = BUILDER
            .comment("Visibility in the heaviest rain. Lighter rain sees further: this divided by the rain's strength",
                    "(drizzle is about 0.15, heavy rain 0.7 to 1).")
            .defineInRange("rain.heavyVisibility", 48.0, 2.0, 2048.0);
    public static final ModConfigSpec.DoubleValue FOG_SNOW = BUILDER
            .comment("Snow's visibility as a share of rain's at the same strength (0.5 = half as far).")
            .defineInRange("rain.snowVisibility", 0.5, 0.05, 2.0);
    public static final ModConfigSpec.DoubleValue FOG_RAIN_START = BUILDER
            .comment("Where rain fog starts in full rain, as a share of its visibility: 0 is right at you, so the fog",
                    "thickens continuously from you outward. Lighter rain moves the start out toward the game's own.")
            .defineInRange("rain.fogStart", 0.0, 0.0, 0.95);
    public static final ModConfigSpec.ConfigValue<String> FOG_RAIN_COLOUR = BUILDER
            .comment("The rain and storm fog colour as hex RRGGBB: the fog and the sky under rain or a storm both turn",
                    "to it. This is its value in full daylight; it dims with the sky at dusk and night.")
            .define("rain.fogColour", "5C6880");
    public static final ModConfigSpec.ConfigValue<String> FOG_SNOW_COLOUR = BUILDER
            .comment("The snow fog colour as hex RRGGBB (full daylight). The fog eases between this and the rain colour",
                    "as what falls turns from rain to snow.")
            .define("snow.fogColour", "D1D6E0");
    public static final ModConfigSpec.DoubleValue FOG_RAIN_NIGHT = BUILDER
            .comment("The fog colours' brightness at night, as a share of their daylight value (0.05 = a twentieth).",
                    "Dusk and dawn blend between the two.")
            .defineInRange("rain.nightBrightness", 0.05, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue FOG_RAIN_EASE = BUILDER
            .comment("Seconds rain fog takes to follow changes, so the edge of a shower doesn't pop. 0 = instant.")
            .defineInRange("rain.easeSeconds", 1.0, 0.0, 30.0);
    public static final ModConfigSpec.DoubleValue FOG_STORM_HAZE = BUILDER
            .comment("Visibility in the darkest storm's air when nothing is falling, blocks: the gloom under and near",
                    "a storm thickens the air gradually with the storm's darkness (added to any rain fog).")
            .defineInRange("storm.hazeVisibility", 400.0, 16.0, 8192.0);
    public static final ModConfigSpec.DoubleValue FOG_STORM_GREY = BUILDER
            .comment("How much fog turns the fog and sky to the storm's colour, as a visibility in blocks: at this",
                    "visibility about two-thirds of the way, at a third of it fully. Larger turns them grey sooner.")
            .defineInRange("storm.greyVisibility", 300.0, 8.0, 8192.0);
    public static final ModConfigSpec.DoubleValue FOG_STORM_DARKENING = BUILDER
            .comment("How much darker the fog colour gets under the darkest storm (0.5 = half as bright). Snow darkens",
                    "less. Light rain stays at the rain colour.")
            .defineInRange("storm.darkening", 0.5, 0.0, 0.95);
    public static final ModConfigSpec.DoubleValue FOG_CLOUD_HEIGHT = BUILDER
            .comment("The storm fog hides clouds too, by their distance across the ground; this is how much their",
                    "height counts as well (0 = a column: the cloud straight overhead never fogs; 1 = full distance).")
            .defineInRange("storm.cloudHeightWeight", 0.05, 0.0, 1.0);

    static {
        BUILDER.pop().comment("Debug views.").push("debug");
    }

    public static final ModConfigSpec.BooleanValue OUTLINES = BUILDER
            .comment("Draw each cloud's outline (base and top rings, plus where it is heading over the next 10 seconds).",
                    "Also toggled in game with /eae clouds outlines.")
            .define("outlines", false);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static final List<Integer> SIZES = List.of(2, 4, 8, 16);

    /** The configured voxel size, snapped to the nearest of 2, 4, 8, 16. */
    public static int voxelSize() {
        int wanted = SPEC.isLoaded() ? VOXEL_SIZE.get() : 4;
        int best = 4;
        for (int s : SIZES) {
            if (Math.abs(s - wanted) < Math.abs(best - wanted)) {
                best = s;
            }
        }
        return best;
    }

    public static boolean enabled() {
        return !SPEC.isLoaded() || ENABLED.get();
    }

    public static int drawDistance() {
        return SPEC.isLoaded() ? DRAW_DISTANCE.get() : 3072;
    }

    public static long rebuildTicks() {
        return Math.round((SPEC.isLoaded() ? REBUILD_SECONDS.get() : 1.0) * 20);
    }

    /** Set by {@code /eae clouds outlines}; starts from the config value. */
    private static Boolean outlinesOverride;

    public static boolean outlines() {
        if (outlinesOverride != null) {
            return outlinesOverride;
        }
        return SPEC.isLoaded() && OUTLINES.get();
    }

    public static void setOutlines(boolean on) {
        outlinesOverride = on;
    }

    /** Background mesher threads (read once, when the mesher starts). */
    public static int mesherThreads() {
        return SPEC.isLoaded() ? MESHER_THREADS.get() : 2;
    }

    /** Copies the config into {@link CloudTuning}; bumps its version if anything changed. */
    public static void apply() {
        if (!SPEC.isLoaded()) {
            return;
        }
        int section = SECTION_SIZE.get() <= 384 ? 256 : SECTION_SIZE.get() <= 768 ? 512 : 1024;
        int maxVoxel = Integer.highestOneBit(Math.max(8, Math.min(128, MAX_VOXEL.get())));
        double[] distances = {LOD_DISTANCE_1.get(), LOD_DISTANCE_2.get(), LOD_DISTANCE_3.get(), LOD_DISTANCE_4.get()};
        String before = snapshot();
        CloudTuning.sectionSize = section;
        CloudTuning.maxVoxel = maxVoxel;
        CloudTuning.lodDistances = distances;
        CloudTuning.rebuildBudget = REBUILD_BUDGET.get();
        CloudTuning.noiseScale = NOISE_SCALE.get();
        CloudTuning.noiseStrength = NOISE_STRENGTH.get();
        CloudTuning.bubbleSmoothing = BUBBLE_SMOOTHING.get();
        CloudTuning.waterContent = WATER_CONTENT.get();
        CloudTuning.baseLight = BASE_LIGHT.get();
        CloudTuning.shadeContrast = SHADE_CONTRAST.get();
        CloudTuning.shadowSide = SHADOW_SIDE.get();
        CloudTuning.creases = CREASES.get();
        CloudTuning.silverLining = SILVER_LINING.get();
        CloudTuning.cloudShadows = CLOUD_SHADOWS.get();
        CloudTuning.wisps = WISPS_ENABLED.get();
        CloudTuning.wispDistance = WISP_DISTANCE.get();
        CloudTuning.wispDensity = WISP_DENSITY.get();
        CloudTuning.wispOpacity = WISP_OPACITY.get();
        CloudTuning.veilOpacity = VEIL_OPACITY.get();
        dev.brights0ng.enginesandempires.weather.rain.RainSlant.averageSeconds = RAIN_SLANT_SECONDS.get();
        dev.brights0ng.enginesandempires.weather.rain.RainSlant.maxChangePerSecond = RAIN_SLANT_RATE.get();
        applyFog();
        if (!before.equals(snapshot())) {
            CloudTuning.version++;
        }
    }

    /** Copies the {@code fog} section into {@link dev.brights0ng.enginesandempires.weather.fog.FogTuning}. */
    private static void applyFog() {
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.enabled = FOG_ENABLED.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.stormCloudVisibility = FOG_STORM_CLOUD.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.congestusVisibility = FOG_CONGESTUS.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.fairCloudVisibility = FOG_FAIR_CLOUD.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.highCloudVisibility = FOG_HIGH_CLOUD.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.wispVisibility = FOG_WISP.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.heavyRainVisibility = FOG_HEAVY_RAIN.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.snowVisibility = FOG_SNOW.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.rainFogStart = FOG_RAIN_START.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.rainFogColour = dev.brights0ng.enginesandempires
                .weather.fog.FogTuning.parseColour(FOG_RAIN_COLOUR.get(), new double[] {0.36, 0.41, 0.50});
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.snowFogColour = dev.brights0ng.enginesandempires
                .weather.fog.FogTuning.parseColour(FOG_SNOW_COLOUR.get(), new double[] {0.82, 0.84, 0.88});
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.rainNightBrightness = FOG_RAIN_NIGHT.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.rainEaseSeconds = FOG_RAIN_EASE.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.stormHazeVisibility = FOG_STORM_HAZE.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.stormGreyVisibility = FOG_STORM_GREY.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.stormDarkening = FOG_STORM_DARKENING.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.cloudFogHeightWeight = FOG_CLOUD_HEIGHT.get();
    }

    private static String snapshot() {
        return CloudTuning.sectionSize + "|" + CloudTuning.maxVoxel + "|" + java.util.Arrays.toString(CloudTuning.lodDistances)
                + "|" + CloudTuning.noiseScale + "|" + CloudTuning.noiseStrength + "|" + CloudTuning.waterContent + "|" + CloudTuning.baseLight + "|"
                + CloudTuning.shadeContrast + "|" + CloudTuning.shadowSide + "|"
                + CloudTuning.creases + "|" + CloudTuning.bubbleSmoothing + "|" + CloudTuning.cloudShadows;
    }

    private CloudConfig() {
    }
}
