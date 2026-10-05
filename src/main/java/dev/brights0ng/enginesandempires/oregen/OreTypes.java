package dev.brights0ng.enginesandempires.oregen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.oregen.shape.BodyShape;
import dev.brights0ng.enginesandempires.oregen.shape.DisseminatedShape;
import dev.brights0ng.enginesandempires.oregen.shape.LumpShape;
import dev.brights0ng.enginesandempires.oregen.shape.PocketShape;
import dev.brights0ng.enginesandempires.oregen.shape.SeamShape;
import dev.brights0ng.enginesandempires.oregen.shape.VeinShape;

/**
 * Every ore the pack generates, in one place, so tuning the world is a matter of editing this file.
 *
 * <p>All the numbers here are placeholders to be tuned in play. S values are spacings: about one deposit
 * per S x S blocks (iron 384 and gold 1408 are set; the rest are guesses that keep coal common and
 * diamond and emerald rare). Sizes are for shallow deposits; deep ones are 4 to 8 times larger. Depth
 * ranges are the heights at which deposit centres are drawn.
 *
 * <p>Each ore belongs to one {@link Realm} and generates only there. The Nether has no heights below
 * y=0, so its ores use {@link DepthProfile#deepThroughout}: every nether deposit is deep-sized. Nether
 * ores are spaced much closer together than the overworld's (gold 384 against the overworld's 1408, quartz 128).
 *
 * <p>Biome rules scale the size of shallow overworld deposits (centre at y=0 or above) by the biome at the
 * surface above them, after the size cap. Deep deposits and the Nether ignore biome. Where a biome matches
 * several rules the strongest wins, an equal tie going to the rule listed first; {@code OTHERWISE} applies
 * only where nothing else matched. See {@link BiomeRule}. "Highlands" is every mountain biome plus the
 * windswept hills. The values are gameplay-first: each ore has one kind of country where it runs rich.
 */
public final class OreTypes {

    // Biome selectors used below.
    private static final String BADLANDS = "#minecraft:is_badlands";
    private static final String MOUNTAIN = "#minecraft:is_mountain";
    private static final String HILL = "#minecraft:is_hill";
    private static final String OCEAN = "#minecraft:is_ocean";
    private static final String SAVANNA = "#minecraft:is_savanna";
    private static final String FOREST = "#minecraft:is_forest";
    private static final String SWAMP = "#c:is_swamp";
    private static final String DESERT = "#c:is_desert";
    private static final String PLAINS = "#c:is_plains";
    private static final String SNOWY = "#c:is_snowy";
    private static final String OTHERWISE = BiomeRule.OTHERWISE;

    // Overworld.
    // id, S, shape, size (median, sigma, min, max), depth (min y, max y), rich share, biome rules
    public static final OreType COAL = overworld("coal", 128, SeamShape.COAL, size(400, 0.35, 150, 500), new DepthProfile(20, 150), 0.10,
            rule(SWAMP, 2.5), rule(DESERT, 0.5), rule(BADLANDS, 0.5));
    public static final OreType IRON = overworld("iron", 384, LumpShape.DEFAULT, size(300, 0.35, 100, 500), new DepthProfile(-48, 64), 0.25,
            rule(MOUNTAIN, 2.0), rule(HILL, 2.0), rule(OCEAN, 0.5));
    public static final OreType COPPER = overworld("copper", 448, DisseminatedShape.COPPER, size(450, 0.35, 150, 500), new DepthProfile(-24, 96), 0.20,
            rule(DESERT, 2.5), rule(OCEAN, 0.5));
    public static final OreType ZINC = overworld("zinc", 576, LumpShape.DEFAULT, size(250, 0.35, 80, 450), new DepthProfile(-56, 56), 0.25,
            rule(PLAINS, 2.5), rule(OCEAN, 0.5));
    public static final OreType REDSTONE = overworld("redstone", 704, VeinShape.REDSTONE, size(250, 0.35, 80, 450), new DepthProfile(-56, 32), 0.25,
            rule(FOREST, 2.5), rule(SAVANNA, 2.5), rule(OTHERWISE, 0.5));
    public static final OreType LAPIS = overworld("lapis", 896, SeamShape.LAPIS, size(200, 0.35, 60, 400), new DepthProfile(-48, 48), 0.25,
            rule(MOUNTAIN, 2.0), rule(HILL, 2.0), rule(OCEAN, 0.5));
    public static final OreType CRYSTAL = overworld("crystal", 1152, PocketShape.CRYSTAL, size(150, 0.40, 40, 400), new DepthProfile(-16, 64), 0.20);
    // In badlands gold reaches up to y=100. Its factor is scaled up from 2.5 (to about 3.7) so a badlands deposit
    // holds as much gold on average as it would with the normal range: more deposits are shallow, fewer are
    // the big deep ones, and the larger shallow factor makes up the difference.
    private static final DepthProfile GOLD_DEPTH = new DepthProfile(-56, 32);
    public static final OreType GOLD = overworld("gold", 1408, VeinShape.GOLD, size(150, 0.40, 40, 300), GOLD_DEPTH, 0.30,
            BiomeRule.raising(BADLANDS, 2.5, GOLD_DEPTH, 100), rule(OCEAN, 0.5));
    public static final OreType EMERALD = overworld("emerald", 1600, PocketShape.EMERALD, size(30, 0.50, 8, 80), new DepthProfile(64, 200), 0.40,
            rule(MOUNTAIN, 3.0), rule(HILL, 3.0), rule(OTHERWISE, 0.25));
    // Ocean is listed before snowy so that frozen oceans, which match both equally strongly, stay poor.
    public static final OreType DIAMOND = overworld("diamond", 1792, DisseminatedShape.DIAMOND, size(40, 0.40, 12, 120), new DepthProfile(-52, 24), 0.30,
            rule(OCEAN, 0.5), rule(SNOWY, 2.0));

    // Nether. Same shapes and sizes as their overworld counterparts where there are any, all deep-sized,
    // and spaced much closer than the overworld (gold 384, quartz 128).
    public static final OreType NETHER_QUARTZ = nether("nether_quartz", 128, PocketShape.QUARTZ, size(120, 0.40, 40, 300), DepthProfile.deepThroughout(10, 110), 0.25);
    public static final OreType NETHER_GOLD = nether("nether_gold", 384, VeinShape.GOLD, size(150, 0.40, 40, 300), DepthProfile.deepThroughout(10, 110), 0.30);

    public static final List<OreType> ALL = List.of(
            COAL, IRON, COPPER, ZINC, REDSTONE, LAPIS, CRYSTAL, GOLD, EMERALD, DIAMOND,
            NETHER_QUARTZ, NETHER_GOLD);

    private static final Map<String, OreType> BY_ID = new HashMap<>();
    private static final Map<Realm, List<OreType>> BY_REALM = new EnumMap<>(Realm.class);

    static {
        for (Realm realm : Realm.values()) {
            BY_REALM.put(realm, new ArrayList<>());
        }
        for (OreType type : ALL) {
            if (BY_ID.put(type.id(), type) != null) {
                throw new IllegalStateException("Two ores share the id '" + type.id() + "'");
            }
            BY_REALM.get(type.realm()).add(type);
        }
        BY_REALM.replaceAll((realm, types) -> List.copyOf(types));
    }

    /** The ore with this id. */
    public static OreType byId(String id) {
        OreType type = BY_ID.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown ore '" + id + "'");
        }
        return type;
    }

    /** The ores that generate in this realm, in {@link #ALL} order. */
    public static List<OreType> forRealm(Realm realm) {
        return BY_REALM.get(realm);
    }

    private static OreType overworld(String id, int scale, BodyShape shape,
                                     SizeProfile size, DepthProfile depth, double richShare, BiomeRule... biomes) {
        return new OreType(new OreLayer(id, scale), Realm.OVERWORLD, shape, size, depth, richShare, List.of(biomes));
    }

    private static OreType nether(String id, int scale, BodyShape shape,
                                  SizeProfile size, DepthProfile depth, double richShare) {
        return new OreType(new OreLayer(id, scale), Realm.NETHER, shape, size, depth, richShare);
    }

    private static SizeProfile size(int median, double sigma, int min, int max) {
        return new SizeProfile(median, sigma, min, max);
    }

    private static BiomeRule rule(String selector, double factor) {
        return new BiomeRule(selector, factor);
    }

    private OreTypes() {
    }
}
