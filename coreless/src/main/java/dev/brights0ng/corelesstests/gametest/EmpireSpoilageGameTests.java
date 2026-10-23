// Written by Claude.
package dev.brights0ng.corelesstests.gametest;

import java.util.List;

import dev.brights0ng.corelesstests.CorelessTests;
import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireItem;
import dev.brights0ng.empire.compat.frontier.FrontierCompat;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.tier.TierCheck;
import dev.brights0ng.empire.tier.TierChecks;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.simplespoilage.Cohort;
import dev.brights0ng.simplespoilage.FoodFreshness;
import dev.brights0ng.simplespoilage.FoodStage;
import dev.brights0ng.simplespoilage.SpoilClock;
import dev.brights0ng.simplespoilage.SpoilTimes;
import dev.brights0ng.simplespoilage.Spoilage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Empire's gods judge founding meat by Simple Spoilage's stages (PROVISIONAL): every god but the Carrion Crow refuses its
 * meat once stale, for every one of the 5 units taken (the stack's freshest), and the Crow takes any raw meat once it is
 * rotting. Each test runs to the end in its first tick.
 */
@GameTestHolder(CorelessTests.MODID)
@PrefixGameTestTemplate(false)
public final class EmpireSpoilageGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);
    private static final ResourceLocation IRON_AUROCHS = Empire.id("iron_aurochs");
    private static final ResourceLocation CARRION_CROW = Empire.id("carrion_crow");

    @GameTest(template = GameTestStructures.EMPTY)
    public static void staleBeefIsRefusedAndNothingIsTaken(GameTestHelper helper) {
        ItemStack beef = new ItemStack(Items.BEEF, 5);
        helper.assertTrue(Spoilage.stampAs(beef, FoodStage.STALE, 0.5), "the beef should take a stale stamp");
        EmpireRecord record = found(helper, beef);
        disband(helper, record);
        helper.assertTrue(record == null, "stale beef founds nothing, but founded under " + (record == null ? null : record.god()));
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void ripeBeefStillFoundsUnderTheIronAurochs(GameTestHelper helper) {
        ItemStack beef = new ItemStack(Items.BEEF, 5);
        helper.assertTrue(Spoilage.stampAs(beef, FoodStage.RIPE, 0.5), "the beef should take a ripe stamp");
        EmpireRecord record = found(helper, beef);
        disband(helper, record);
        helper.assertTrue(record != null && IRON_AUROCHS.equals(record.god()), "ripe beef founds under the Iron Aurochs");
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void everyOneOfTheFiveUnitsTakenMustBeFresh(GameTestHelper helper) {
        long now = SpoilClock.now();
        SpoilTimes times = Spoilage.times();
        long staleBorn = now - times.ageWithin(FoodStage.STALE, 0.5);

        // 3 fresh and 5 stale: the 5 freshest include 2 stale ones
        ItemStack mixed = new ItemStack(Items.BEEF, 8);
        Spoilage.write(mixed, new FoodFreshness(List.of(new Cohort(now, 3), new Cohort(staleBorn, 5)), 0));
        EmpireRecord refused = found(helper, mixed);
        disband(helper, refused);
        helper.assertTrue(refused == null, "5 beef that include 2 stale ones found nothing");

        // 5 fresh and 3 stale: the 5 freshest are all fresh, and the stale ones stay behind
        ItemStack enough = new ItemStack(Items.BEEF, 8);
        Spoilage.write(enough, new FoodFreshness(List.of(new Cohort(now, 5), new Cohort(staleBorn, 3)), 0));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EmpireRecord record = found(helper, player, enough);
        disband(helper, record);
        helper.assertTrue(record != null && IRON_AUROCHS.equals(record.god()), "the 5 fresh beef found the empire");
        ItemStack left = player.getMainHandItem();
        FoodFreshness rest = Spoilage.view(left, SpoilClock.now());
        helper.assertTrue(left.getCount() == 3 && rest.cohorts().size() == 1
                        && times.stage(SpoilClock.now() - rest.cohorts().get(0).born()) == FoodStage.STALE,
                "the 3 stale beef are what is left: " + rest);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void theCarrionCrowTakesRottingRawMeat(GameTestHelper helper) {
        ItemStack beef = new ItemStack(Items.BEEF, 5);
        helper.assertTrue(Spoilage.stampAs(beef, FoodStage.ROTTING, 0), "the beef should take a rotting stamp");
        EmpireRecord record = found(helper, beef);
        disband(helper, record);
        helper.assertTrue(record != null && CARRION_CROW.equals(record.god()),
                "rotting beef founds under the Carrion Crow, god is " + (record == null ? null : record.god()));

        ItemStack rabbit = new ItemStack(Items.RABBIT, 5);
        helper.assertTrue(Spoilage.stampAs(rabbit, FoodStage.FRESH, 0), "the rabbit should take a fresh stamp");
        EmpireRecord fresh = found(helper, rabbit);
        disband(helper, fresh);
        helper.assertTrue(fresh == null, "fresh raw meat that is no god's founding meat founds nothing");
        helper.succeed();
    }

    // ---- helpers ----

    private static EmpireRecord found(GameTestHelper helper, ItemStack meat) {
        return found(helper, helper.makeMockPlayer(GameType.SURVIVAL), meat);
    }

    /**
     * Offers {@code meat} at a lit bonfire on the test's spot (every column counting as Settled) and returns the empire it
     * founded, or null; when it founds nothing, checks nothing was taken.
     */
    private static EmpireRecord found(GameTestHelper helper, Player player, ItemStack meat) {
        int count = meat.getCount();
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.setBlock(SPOT.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true));
        BlockPos pos = helper.absolutePos(SPOT);
        BonfireItem.placeParts(helper.getLevel(), pos);
        player.setItemInHand(InteractionHand.MAIN_HAND, meat);
        TierChecks.install(() -> TierCheck.ALWAYS_PASS);
        try {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            helper.getLevel().getBlockState(pos).useItemOn(player.getMainHandItem(), helper.getLevel(), player,
                    InteractionHand.MAIN_HAND, hit);
        } finally {
            FrontierCompat.registerIfLoaded();
        }
        EmpireRecord record = EmpireSavedData.of(helper.getLevel().getServer()).getEmpireOf(player.getUUID());
        if (record == null) {
            helper.assertTrue(player.getMainHandItem().getCount() == count, "a refused offering takes nothing");
        }
        return record;
    }

    private static void disband(GameTestHelper helper, EmpireRecord record) {
        if (record != null) {
            EmpireSavedData.of(helper.getLevel().getServer()).disbandEmpire(record.id(), null);
        }
    }
}
