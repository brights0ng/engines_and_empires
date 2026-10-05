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
        BUILDER.comment("The pack's voxel cloud renderer. It draws the clouds Project Atmosphere simulates.")
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
                    "pick up Project Atmosphere's changes (growing, shrinking, changing type). A whole formation is",
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
    public static final ModConfigSpec.DoubleValue VARIETY = shape("supercell.variety",
            "How much supercells differ from each other (low- to high-precipitation, sheared, unstable): 1.0 as",
            "designed, 0 makes them all the same, 2.0 twice as varied.");
    public static final ModConfigSpec.DoubleValue UPDRAFT_WIDTH = shape("supercell.updraftWidth",
            "Width of the main tower.");
    public static final ModConfigSpec.DoubleValue UPDRAFT_LEAN = shape("supercell.updraftLean",
            "How far the tower leans downwind (more with height).");
    public static final ModConfigSpec.DoubleValue UPDRAFT_BASE_FLARE = shape("supercell.updraftBaseFlare",
            "How much wider the tower's base (the rotating rain-free base) is than its middle.");
    public static final ModConfigSpec.DoubleValue UPDRAFT_WAIST = shape("supercell.updraftWaist",
            "How much the tower narrows in its middle (higher is narrower).");
    public static final ModConfigSpec.DoubleValue UPDRAFT_BULGES = shape("supercell.updraftBulges",
            "Size of the large bulges along the tower.");
    public static final ModConfigSpec.DoubleValue OVERSHOOT_HEIGHT = shape("supercell.overshootHeight",
            "Height of the overshooting top above the anvil.");
    public static final ModConfigSpec.DoubleValue ANVIL_LENGTH = shape("supercell.anvilLength",
            "How far the anvil reaches downwind.");
    public static final ModConfigSpec.DoubleValue ANVIL_WIDTH = shape("supercell.anvilWidth",
            "How wide the anvil spreads.");
    public static final ModConfigSpec.DoubleValue ANVIL_THICKNESS = shape("supercell.anvilThickness",
            "How thick the anvil is.");
    public static final ModConfigSpec.DoubleValue BACKSHEAR_REACH = shape("supercell.backshearReach",
            "How far the anvil overhangs the tower upwind.");
    public static final ModConfigSpec.DoubleValue FORWARD_FLANK_LENGTH = shape("supercell.forwardFlankLength",
            "Length of the forward flank (the rain area under the anvil).");
    public static final ModConfigSpec.DoubleValue FORWARD_FLANK_WIDTH = shape("supercell.forwardFlankWidth",
            "Width of the forward flank.");
    public static final ModConfigSpec.DoubleValue SHELF_REACH = shape("supercell.shelfReach",
            "How far the shelf cloud sticks out ahead of the forward flank.");
    public static final ModConfigSpec.DoubleValue SHELF_WIDTH = shape("supercell.shelfWidth",
            "Width of the shelf cloud's arc.");
    public static final ModConfigSpec.DoubleValue SHELF_HEIGHT = shape("supercell.shelfHeight",
            "Height of the shelf cloud's tiers.");
    public static final ModConfigSpec.DoubleValue FLANKING_LINE_LENGTH = shape("supercell.flankingLineLength",
            "Length of the flanking line of towers to the right-rear.");
    public static final ModConfigSpec.DoubleValue FLANKING_TOWER_HEIGHT = shape("supercell.flankingTowerHeight",
            "Height of the flanking line's towers.");
    public static final ModConfigSpec.DoubleValue WALL_CLOUD_SIZE = shape("supercell.wallCloudSize",
            "Size of the wall cloud under the tower.");
    public static final ModConfigSpec.DoubleValue MAMMATUS_SIZE = shape("supercell.mammatusSize",
            "Size of the pouches under the anvil.");

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
            .comment("Visibility inside supercells, cumulonimbus and nimbostratus. 0 = no fog.")
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
    public static final ModConfigSpec.DoubleValue FOG_RAIN_BRIGHTNESS = BUILDER
            .comment("The rain colour, as a grey from 0 (black) to 1 (white): the rain fog and the sky under rain both",
                    "turn to this grey. This is its value in full daylight; it dims with the sky at dusk and night.",
                    "Applies live when the file is saved.")
            .defineInRange("rain.fogBrightness", 0.62, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue FOG_RAIN_NIGHT = BUILDER
            .comment("The rain grey's brightness at night, as a share of its daylight value (0.05 = a twentieth).",
                    "Dusk and dawn blend between the two.")
            .defineInRange("rain.nightBrightness", 0.05, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue FOG_RAIN_FULL_GREY = BUILDER
            .comment("Rain strength at which the fog is fully grey instead of the sky's colour; lighter rain blends",
                    "between the two.")
            .defineInRange("rain.fullGreyStrength", 0.35, 0.01, 1.0);
    public static final ModConfigSpec.DoubleValue FOG_RAIN_EASE = BUILDER
            .comment("Seconds rain fog takes to follow changes, so the edge of a shower doesn't pop. 0 = instant.")
            .defineInRange("rain.easeSeconds", 1.0, 0.0, 30.0);

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
        CloudTuning.variety = VARIETY.get();
        CloudTuning.updraftWidth = UPDRAFT_WIDTH.get();
        CloudTuning.updraftLean = UPDRAFT_LEAN.get();
        CloudTuning.updraftBaseFlare = UPDRAFT_BASE_FLARE.get();
        CloudTuning.updraftWaist = UPDRAFT_WAIST.get();
        CloudTuning.updraftBulges = UPDRAFT_BULGES.get();
        CloudTuning.overshootHeight = OVERSHOOT_HEIGHT.get();
        CloudTuning.anvilLength = ANVIL_LENGTH.get();
        CloudTuning.anvilWidth = ANVIL_WIDTH.get();
        CloudTuning.anvilThickness = ANVIL_THICKNESS.get();
        CloudTuning.backshearReach = BACKSHEAR_REACH.get();
        CloudTuning.forwardFlankLength = FORWARD_FLANK_LENGTH.get();
        CloudTuning.forwardFlankWidth = FORWARD_FLANK_WIDTH.get();
        CloudTuning.shelfReach = SHELF_REACH.get();
        CloudTuning.shelfWidth = SHELF_WIDTH.get();
        CloudTuning.shelfHeight = SHELF_HEIGHT.get();
        CloudTuning.flankingLineLength = FLANKING_LINE_LENGTH.get();
        CloudTuning.flankingTowerHeight = FLANKING_TOWER_HEIGHT.get();
        CloudTuning.wallCloudSize = WALL_CLOUD_SIZE.get();
        CloudTuning.mammatusSize = MAMMATUS_SIZE.get();
        CloudTuning.waterContent = WATER_CONTENT.get();
        CloudTuning.baseLight = BASE_LIGHT.get();
        CloudTuning.shadeContrast = SHADE_CONTRAST.get();
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
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.rainFogBrightness = FOG_RAIN_BRIGHTNESS.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.rainNightBrightness = FOG_RAIN_NIGHT.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.rainFullGrey = FOG_RAIN_FULL_GREY.get();
        dev.brights0ng.enginesandempires.weather.fog.FogTuning.rainEaseSeconds = FOG_RAIN_EASE.get();
    }

    private static String snapshot() {
        return CloudTuning.sectionSize + "|" + CloudTuning.maxVoxel + "|" + java.util.Arrays.toString(CloudTuning.lodDistances)
                + "|" + CloudTuning.noiseScale + "|" + CloudTuning.noiseStrength + "|" + CloudTuning.variety + "|"
                + CloudTuning.updraftWidth + "|"
                + CloudTuning.updraftLean + "|" + CloudTuning.updraftBaseFlare + "|" + CloudTuning.updraftWaist + "|"
                + CloudTuning.updraftBulges + "|" + CloudTuning.overshootHeight + "|" + CloudTuning.anvilLength + "|"
                + CloudTuning.anvilWidth + "|" + CloudTuning.anvilThickness + "|" + CloudTuning.backshearReach + "|"
                + CloudTuning.forwardFlankLength + "|" + CloudTuning.forwardFlankWidth + "|" + CloudTuning.shelfReach
                + "|" + CloudTuning.shelfWidth + "|" + CloudTuning.shelfHeight + "|" + CloudTuning.flankingLineLength
                + "|" + CloudTuning.flankingTowerHeight + "|" + CloudTuning.wallCloudSize + "|"
                + CloudTuning.mammatusSize + "|" + CloudTuning.waterContent + "|" + CloudTuning.baseLight + "|"
                + CloudTuning.shadeContrast;
    }

    private CloudConfig() {
    }
}
