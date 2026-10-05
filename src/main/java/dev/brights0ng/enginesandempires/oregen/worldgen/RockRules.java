package dev.brights0ng.enginesandempires.oregen.worldgen;

import dev.brights0ng.enginesandempires.oregen.Realm;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one definition of which existing blocks a deposit is allowed to replace when it is built into
 * real terrain. Only solid host rock qualifies. Air, water, lava, caves, dirt, sand, gravel, other
 * ores and everything else that is not plain rock is never touched, so a deposit can never fill a cave
 * or pool with stone, or overwrite fluids.
 *
 * <p>This uses vanilla's own tags, which is what vanilla ores use, so datapacks and other mods that add
 * new rock types to those tags get deposits in them too:
 * <ul>
 *   <li>Overworld: {@code #minecraft:stone_ore_replaceables} (stone, granite, diorite, andesite) and
 *       {@code #minecraft:deepslate_ore_replaceables} (deepslate, tuff).</li>
 *   <li>Nether: {@code #minecraft:base_stone_nether} (netherrack, basalt, blackstone).</li>
 * </ul>
 */
final class RockRules {

    /** True if a deposit block may replace this existing block. */
    static boolean isReplaceable(Realm realm, BlockState existing) {
        return switch (realm) {
            case OVERWORLD -> existing.is(BlockTags.STONE_ORE_REPLACEABLES)
                    || existing.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
            case NETHER -> existing.is(BlockTags.BASE_STONE_NETHER);
        };
    }

    /** True if an ore replacing this rock should use its deepslate version. */
    static boolean isDeepslateType(BlockState existing) {
        return existing.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
    }

    private RockRules() {
    }
}
