package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import dev.brights0ng.enginesandempires.frontier.tier.TierParams;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of Frontier's tiers: that anchor blocks are counted whichever way they are placed, that an anchor plus
 * enough inhabited time makes land Settled, and that a villager turning up makes it Settled at once.
 *
 * <p>Tiers look at the land for 4 subchunks around, which is wider than the space between tests, so each test runs in a
 * batch of its own (batches run one after another) and forgets the inhabited time around it before and after.
 *
 * <p>The game test world is a superflat one below y 0, where everything is Frontier whatever is built; so these check the
 * section's <em>base</em> tier, and that the tier at the block is Frontier because of the depth.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class FrontierGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = SCRATCH, batch = "frontier_anchors")
    public static void anchorBlocksAreCounted(GameTestHelper helper) {
        FrontierLevel frontier = frontier(helper);
        long key = FrontierLevel.keyOf(helper.absolutePos(SPOT));
        int before = frontier.countsAround(key).anchors();

        helper.setBlock(SPOT, Blocks.LANTERN);
        helper.assertTrue(frontier.countsAround(key).anchors() == before + 1, "a lantern should count as an anchor");
        helper.setBlock(SPOT, Blocks.HAY_BLOCK);
        helper.assertTrue(frontier.countsAround(key).anchors() == before + 1, "a hay bale (for pillager outposts) should count");
        helper.setBlock(SPOT, Blocks.TORCH);
        helper.assertTrue(frontier.countsAround(key).anchors() == before, "a torch is not an anchor");
        helper.setBlock(SPOT, Blocks.CRAFTING_TABLE);
        helper.setBlock(SPOT.east(), Blocks.BLAST_FURNACE);
        helper.assertTrue(frontier.countsAround(key).anchors() == before + 2, "workstations should count");
        helper.setBlock(SPOT, Blocks.AIR);
        helper.setBlock(SPOT.east(), Blocks.AIR);
        helper.assertTrue(frontier.countsAround(key).anchors() == before, "breaking them should take them off again");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_settle")
    public static void anAnchorAndEnoughTimeSettleTheLand(GameTestHelper helper) {
        FrontierLevel frontier = frontier(helper);
        BlockPos at = helper.absolutePos(SPOT);
        long key = FrontierLevel.keyOf(at);
        frontier.clearHabitation(at, 8);
        helper.setBlock(SPOT, Blocks.LANTERN);
        frontier.refreshAround(at, 4);
        helper.assertTrue(frontier.baseTier(key) == Tier.FRONTIER, "a lantern alone should not settle the land");

        TierParams p = frontier.params();
        frontier.addHabitation(at, p.settleTicks() / 2);
        frontier.refreshAround(at, 4);
        helper.assertTrue(frontier.baseTier(key) == Tier.FRONTIER, "half the time should not be enough");

        frontier.addHabitation(at, p.settleTicks());
        frontier.refreshAround(at, 4);
        helper.assertTrue(frontier.baseTier(key) == Tier.SETTLED, "a lantern and 1.5 days should settle the land, got "
                + frontier.baseTier(key));
        if (at.getY() < p.frontierBelowY()) {
            helper.assertTrue(frontier.tierAt(at) == Tier.FRONTIER, "below y 0 should be Frontier whatever is built");
        }

        helper.setBlock(SPOT, Blocks.AIR);
        frontier.refreshAround(at, 4);
        // Earlier tests leave blocks behind (furnaces, crafting tables...) that may still anchor the land here.
        if (frontier.countsAround(key).anchors() == 0) {
            helper.assertTrue(frontier.baseTier(key) == Tier.FRONTIER, "without its anchor the land should go back to Frontier");
        }
        frontier.clearHabitation(at, 8);
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_village")
    public static void aVillagerSettlesTheLandAtOnce(GameTestHelper helper) {
        FrontierLevel frontier = frontier(helper);
        BlockPos at = helper.absolutePos(SPOT);
        long key = FrontierLevel.keyOf(at);
        frontier.clearHabitation(at, 8);
        helper.setBlock(SPOT, Blocks.COMPOSTER);
        frontier.refreshAround(at, 4);
        helper.assertTrue(frontier.baseTier(key) == Tier.FRONTIER, "a workstation alone should not settle the land");

        Villager villager = helper.spawn(EntityType.VILLAGER, SPOT.north());
        frontier.refreshAround(at, 4);
        helper.assertTrue(frontier.countsAround(key).recentResident(), "the villager should have been noticed arriving");
        helper.assertTrue(frontier.baseTier(key) == Tier.SETTLED, "a workstation and a villager should settle the land at once");

        villager.discard();
        helper.setBlock(SPOT, Blocks.AIR);
        frontier.clearHabitation(at, 8);
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_crowd", timeoutTicks = 200)
    public static void aCrowdOfVillagersSettlesNoFasterThanOne(GameTestHelper helper) {
        FrontierLevel frontier = frontier(helper);
        BlockPos at = helper.absolutePos(SPOT);
        long key = FrontierLevel.keyOf(at);
        frontier.clearHabitation(at, 8);
        for (int i = 0; i < 12; i++) {
            helper.spawn(EntityType.VILLAGER, new BlockPos(1 + i % 5, 1, 1 + i / 5));
        }
        long start = helper.getLevel().getGameTime();
        helper.runAfterDelay(100, () -> {
            long passed = helper.getLevel().getGameTime() - start;
            long inhabited = frontier.countsAround(key).habitation();
            helper.assertTrue(inhabited > 0, "the villagers should make the land inhabited");
            helper.assertTrue(inhabited <= passed + 20,
                    "12 villagers should count as one: " + inhabited + " ticks inhabited in " + passed + " ticks");
            helper.killAllEntitiesOfClass(Villager.class);
            frontier.clearHabitation(at, 8);
            helper.succeed();
        });
    }

    private static FrontierLevel frontier(GameTestHelper helper) {
        FrontierLevel frontier = FrontierLevels.of(helper.getLevel());
        if (frontier == null) {
            helper.fail("the game test level has no Frontier state (is it the Overworld?)");
        }
        return frontier;
    }

    private FrontierGameTests() {
    }
}
