// Written by Claude.
package dev.brights0ng.meteodyne.heat.airgrid;

import java.util.Locale;
import java.util.Map;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The air-cell classifier's rules on vanilla blocks, through the memoized table and uncached. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class AirCellClassifierGameTests {

    @GameTest(template = GameTestStructures.EMPTY, batch = "air_cell_classifier")
    public static void vanillaBlocksClassifyByTheRules(GameTestHelper helper) {
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState();
        expect(helper, Blocks.STONE.defaultBlockState(), AirCellType.SOLID);
        expect(helper, Blocks.AIR.defaultBlockState(), AirCellType.AIR);
        expect(helper, slab.setValue(SlabBlock.TYPE, SlabType.BOTTOM), AirCellType.BOTTOM_HALF);
        expect(helper, slab.setValue(SlabBlock.TYPE, SlabType.TOP), AirCellType.TOP_HALF);
        expect(helper, Blocks.OAK_FENCE.defaultBlockState(), AirCellType.THIN);
        expect(helper, Blocks.GLASS_PANE.defaultBlockState(), AirCellType.THIN);
        expect(helper, Blocks.RED_STAINED_GLASS_PANE.defaultBlockState(), AirCellType.THIN);
        expect(helper, Blocks.OAK_LEAVES.defaultBlockState(), AirCellType.PERMEABLE);
        expect(helper, Blocks.OAK_DOOR.defaultBlockState(), AirCellType.DOOR);
        expect(helper, Blocks.WATER.defaultBlockState(), AirCellType.FLUID);
        expect(helper, Fluids.FLOWING_WATER.getFlowing(3, false).createLegacyBlock(), AirCellType.FLUID);
        expect(helper, Blocks.LAVA.defaultBlockState(), AirCellType.FLUID);
        expect(helper, slab.setValue(SlabBlock.TYPE, SlabType.BOTTOM).setValue(BlockStateProperties.WATERLOGGED, true), AirCellType.FLUID);
        BlockState snow = Blocks.SNOW.defaultBlockState();
        expect(helper, snow.setValue(SnowLayerBlock.LAYERS, 2), AirCellType.AIR);
        expect(helper, snow.setValue(SnowLayerBlock.LAYERS, 5), AirCellType.BOTTOM_HALF);
        expect(helper, snow.setValue(SnowLayerBlock.LAYERS, 8), AirCellType.SOLID);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "air_cell_classifier")
    public static void stairsFallThroughToTheVolumeRule(GameTestHelper helper) {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState();
        AirCellClassifier.Classification c = AirCellClassifier.classify(stairs, AirCellClassifier.OVERRIDES);
        helper.assertTrue(c.volumeRule(), "Stairs reach the volume rule");
        expect(helper, stairs, AirCellType.SOLID);
        AirCellClassifier.Table table = AirCellClassifier.table();
        helper.assertTrue(table.volumeRuleStates() > 0, "The table counts volume-rule states");
        helper.assertTrue(!table.topVolumeRule().isEmpty() && table.topVolumeRule().size() <= 20, "The report lists at most 20 blocks");
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "air_cell_classifier")
    public static void overrideTagWinsOverEveryRule(GameTestHelper helper) {
        // The shipped override tags are empty, so a vanilla tag stands in for one: oak slabs forced SOLID.
        Map<AirCellType, TagKey<Block>> overrides = Map.of(AirCellType.SOLID, BlockTags.WOODEN_SLABS);
        BlockState bottom = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        helper.assertTrue(AirCellClassifier.classify(bottom, overrides).type() == AirCellType.SOLID, "Override beats the shape rule");
        helper.assertTrue(AirCellClassifier.classify(bottom.setValue(BlockStateProperties.WATERLOGGED, true), overrides).type()
                == AirCellType.SOLID, "Override beats the fluid rule");
        helper.assertTrue(AirCellClassifier.classify(Blocks.STONE_SLAB.defaultBlockState(), overrides).type() == AirCellType.BOTTOM_HALF,
                "Blocks outside the tag keep their rule");
        AirCellClassifier.Table forced = AirCellClassifier.build(overrides);
        int id = Block.BLOCK_STATE_REGISTRY.getId(bottom);
        helper.assertTrue(forced.types()[id] == AirCellType.SOLID.ordinal(), "A table built with the override uses it");
        for (AirCellType type : AirCellType.values()) {
            helper.assertTrue(AirCellClassifier.OVERRIDES.get(type).location().equals(Meteodyne.id("air_cell/" + type.name().toLowerCase(Locale.ROOT))),
                    "Override tag for " + type);
        }
        helper.succeed();
    }

    private static void expect(GameTestHelper helper, BlockState state, AirCellType type) {
        AirCellType memo = AirCellClassifier.type(state);
        AirCellType fresh = AirCellClassifier.classify(state, AirCellClassifier.OVERRIDES).type();
        helper.assertTrue(memo == type, state + " is " + type + ", not " + memo);
        helper.assertTrue(fresh == type, state + " classifies uncached as " + type + ", not " + fresh);
    }
}
