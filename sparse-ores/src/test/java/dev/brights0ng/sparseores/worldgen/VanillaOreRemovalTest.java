package dev.brights0ng.sparseores.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.brights0ng.sparseores.OreType;
import dev.brights0ng.sparseores.OreTypes;
import dev.brights0ng.sparseores.Realm;

/**
 * The pack generates its own ore deposits, so the ore veins vanilla (and Create) generate must be
 * switched off, or both would appear. These check the biome modifiers that do it, which are plain JSON
 * data files, against a written-down list of which worldgen features make each ore. Create's veins are
 * removed by a modifier of their own that only loads with Create: naming a feature that does not exist
 * would stop the world from loading without it.
 */
class VanillaOreRemovalTest {

    private static final String DATA = "/data/sparse_ores/neoforge/biome_modifier/";

    /** Every worldgen feature that generates each of our ores' veins in an ordinary world. */
    private static final Map<String, List<String>> FEATURES_OF = Map.ofEntries(
            Map.entry("coal", List.of("minecraft:ore_coal_upper", "minecraft:ore_coal_lower")),
            Map.entry("iron", List.of("minecraft:ore_iron_upper", "minecraft:ore_iron_middle", "minecraft:ore_iron_small")),
            Map.entry("copper", List.of("minecraft:ore_copper", "minecraft:ore_copper_large")),
            Map.entry("zinc", List.of("create:zinc_ore")),
            Map.entry("redstone", List.of("minecraft:ore_redstone", "minecraft:ore_redstone_lower")),
            Map.entry("lapis", List.of("minecraft:ore_lapis", "minecraft:ore_lapis_buried")),
            Map.entry("gold", List.of("minecraft:ore_gold", "minecraft:ore_gold_lower", "minecraft:ore_gold_extra")),
            Map.entry("emerald", List.of("minecraft:ore_emerald")),
            Map.entry("diamond", List.of("minecraft:ore_diamond", "minecraft:ore_diamond_medium",
                    "minecraft:ore_diamond_large", "minecraft:ore_diamond_buried")),
            Map.entry("nether_gold", List.of("minecraft:ore_gold_nether", "minecraft:ore_gold_deltas")),
            Map.entry("nether_quartz", List.of("minecraft:ore_quartz_nether", "minecraft:ore_quartz_deltas")));

    /** Things that look like ore features but must be left alone. */
    private static final List<String> MUST_SURVIVE = List.of(
            "minecraft:ore_ancient_debris_large", "minecraft:ore_ancient_debris_small", // not an ore of ours
            "minecraft:ore_infested",                                                    // silverfish blocks
            "minecraft:amethyst_geode",                                                  // geodes are not ore veins
            "minecraft:ore_dirt", "minecraft:ore_gravel", "minecraft:ore_clay",          // terrain blobs
            "minecraft:ore_granite_upper", "minecraft:ore_diorite_upper", "minecraft:ore_andesite_upper",
            "minecraft:ore_tuff", "minecraft:ore_magma", "minecraft:ore_soul_sand",
            "minecraft:ore_blackstone", "minecraft:ore_gravel_nether");

    private static final Pattern FEATURE_ID = Pattern.compile("\"([a-z0-9_]+:[a-z0-9_/]+)\"");

    @Test
    void everyOreWeGenerateHasItsVanillaVeinsRemovedInTheRightDimension() throws IOException {
        Set<String> overworld = overworldRemovals();
        Set<String> nether = featuresRemovedBy("remove_vanilla_ores_nether.json");
        for (OreType type : OreTypes.ALL) {
            List<String> features = FEATURES_OF.get(type.id());
            if (type.id().equals("crystal")) {
                assertNull(features, "crystal replaces geodes, which are deliberately left alone");
                continue;
            }
            assertNotNull(features, "no feature list written down for " + type.id());
            Set<String> removed = type.realm() == Realm.NETHER ? nether : overworld;
            for (String feature : features) {
                assertTrue(removed.contains(feature), feature + " (" + type.id() + ") is not removed in the " + type.realm());
            }
        }
    }

    @Test
    void nothingIsRemovedExceptTheOreVeinsWeReplace() throws IOException {
        Set<String> expectedOverworld = new HashSet<>();
        Set<String> expectedNether = new HashSet<>();
        for (OreType type : OreTypes.ALL) {
            List<String> features = FEATURES_OF.get(type.id());
            if (features != null) {
                (type.realm() == Realm.NETHER ? expectedNether : expectedOverworld).addAll(features);
            }
        }
        assertEquals(expectedOverworld, overworldRemovals());
        assertEquals(expectedNether, featuresRemovedBy("remove_vanilla_ores_nether.json"));
    }

    /** Vanilla's modifiers name only vanilla features; Create's only Create's, and it loads only with Create. */
    @Test
    void createsVeinsAreRemovedOnlyWhenCreateIsLoaded() throws IOException {
        for (String file : List.of("remove_vanilla_ores_overworld.json", "remove_vanilla_ores_nether.json")) {
            for (String feature : featuresRemovedBy(file)) {
                assertTrue(feature.startsWith("minecraft:"), file + " names " + feature + ", which is not vanilla's");
            }
            assertFalse(read(file).contains("neoforge:conditions"), file + " must always load");
        }
        for (String feature : featuresRemovedBy("remove_create_ores.json")) {
            assertTrue(feature.startsWith("create:"), "remove_create_ores.json names " + feature + ", which is not Create's");
        }
        String create = read("remove_create_ores.json").replaceAll("\\s+", "");
        assertTrue(create.contains("\"neoforge:conditions\":[{\"type\":\"neoforge:mod_loaded\",\"modid\":\"create\"}]"),
                "remove_create_ores.json must load only with Create");
    }

    @Test
    void featuresThatMustSurviveAreNeverRemoved() throws IOException {
        Set<String> all = new HashSet<>(overworldRemovals());
        all.addAll(featuresRemovedBy("remove_vanilla_ores_nether.json"));
        for (String feature : MUST_SURVIVE) {
            assertFalse(all.contains(feature), feature + " must not be removed");
        }
    }

    @Test
    void theModifiersTargetTheRightBiomesAndOurOwnDepositsAreStillAdded() throws IOException {
        String overworld = read("remove_vanilla_ores_overworld.json");
        String create = read("remove_create_ores.json");
        String nether = read("remove_vanilla_ores_nether.json");
        assertTrue(overworld.contains("\"type\": \"neoforge:remove_features\""));
        assertTrue(overworld.contains("\"biomes\": \"#minecraft:is_overworld\""));
        assertTrue(create.contains("\"type\": \"neoforge:remove_features\""));
        assertTrue(create.contains("\"biomes\": \"#minecraft:is_overworld\""));
        assertTrue(nether.contains("\"type\": \"neoforge:remove_features\""));
        assertTrue(nether.contains("\"biomes\": \"#minecraft:is_nether\""));
        // The modifiers that add our deposits must still be there, or the world would have no ore at all.
        assertTrue(read("add_deposits.json").contains("\"features\": \"sparse_ores:deposits\""));
        assertTrue(read("add_deposits_nether.json").contains("\"features\": \"sparse_ores:deposits\""));
    }

    /** Every overworld feature removed: vanilla's, and Create's (whose modifier loads only with Create). */
    private static Set<String> overworldRemovals() throws IOException {
        Set<String> ids = new LinkedHashSet<>(featuresRemovedBy("remove_vanilla_ores_overworld.json"));
        ids.addAll(featuresRemovedBy("remove_create_ores.json"));
        return ids;
    }

    /** The feature ids listed under "features" in a removal modifier. */
    private static Set<String> featuresRemovedBy(String file) throws IOException {
        String text = read(file);
        String list = text.substring(text.indexOf("\"features\""));
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = FEATURE_ID.matcher(list.substring(list.indexOf('[')));
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static String read(String file) throws IOException {
        try (InputStream in = VanillaOreRemovalTest.class.getResourceAsStream(DATA + file)) {
            assertNotNull(in, "missing data file " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
