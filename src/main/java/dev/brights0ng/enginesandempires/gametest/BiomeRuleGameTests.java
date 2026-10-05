package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.BiomeRule;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that every biome rule names something that exists once the game has loaded, so a typo in a tag or
 * biome id cannot silently turn a rule off, and that the key biomes land in the tags the rules rely on.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class BiomeRuleGameTests {

    @GameTest(template = GameTestStructures.EMPTY)
    public static void everyBiomeRuleMatchesSomething(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        for (OreType type : OreTypes.ALL) {
            for (BiomeRule rule : type.biomes()) {
                if (rule.isOtherwise()) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.parse(rule.id());
                boolean found = rule.isTag()
                        ? biomes.getTag(TagKey.create(Registries.BIOME, id)).map(set -> set.size() > 0).orElse(false)
                        : biomes.containsKey(id);
                helper.assertTrue(found, type.id() + ": biome rule " + rule.selector() + " matches no biome");
            }
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void keyBiomesAreInTheExpectedTags(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        assertIn(helper, biomes, Biomes.BADLANDS, "minecraft:is_badlands");
        assertIn(helper, biomes, Biomes.ERODED_BADLANDS, "minecraft:is_badlands");
        assertIn(helper, biomes, Biomes.JAGGED_PEAKS, "minecraft:is_mountain");
        assertIn(helper, biomes, Biomes.WINDSWEPT_HILLS, "minecraft:is_hill");
        assertIn(helper, biomes, Biomes.SWAMP, "c:is_swamp");
        assertIn(helper, biomes, Biomes.MANGROVE_SWAMP, "c:is_swamp");
        assertIn(helper, biomes, Biomes.DESERT, "c:is_desert");
        assertIn(helper, biomes, Biomes.PLAINS, "c:is_plains");
        assertIn(helper, biomes, Biomes.DEEP_OCEAN, "minecraft:is_ocean");
        assertIn(helper, biomes, Biomes.FROZEN_OCEAN, "minecraft:is_ocean");
        assertIn(helper, biomes, Biomes.SNOWY_PLAINS, "c:is_snowy");
        assertIn(helper, biomes, Biomes.FROZEN_PEAKS, "c:is_snowy");
        assertIn(helper, biomes, Biomes.FOREST, "minecraft:is_forest");
        assertIn(helper, biomes, Biomes.SAVANNA, "minecraft:is_savanna");
        helper.succeed();
    }

    private static void assertIn(GameTestHelper helper, Registry<Biome> biomes, ResourceKey<Biome> biome, String tag) {
        boolean in = biomes.getHolderOrThrow(biome).is(TagKey.create(Registries.BIOME, ResourceLocation.parse(tag)));
        helper.assertTrue(in, biome.location() + " is not in #" + tag);
    }

    private BiomeRuleGameTests() {
    }
}
