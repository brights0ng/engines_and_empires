package dev.brights0ng.enginesandempires.gametest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.food.Cohort;
import dev.brights0ng.enginesandempires.food.FoodFreshness;
import dev.brights0ng.enginesandempires.food.SpoilClock;
import dev.brights0ng.enginesandempires.food.SpoilTimes;
import dev.brights0ng.enginesandempires.food.Spoilage;
import dev.brights0ng.enginesandempires.food.SpoilageConfig;
import dev.brights0ng.enginesandempires.food.compat.DietCompat;
import net.appleseed.appleseed.AppleSeed;
import net.appleseed.appleseed.api.hook.DietHookRegistry;
import net.appleseed.appleseed.api.type.IDietGroup;
import net.appleseed.appleseed.common.capability.DietData;
import net.appleseed.appleseed.common.data.group.DietGroup;
import net.appleseed.appleseed.common.data.group.DietGroups;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of the Diet hooks (food/compat/DietCompat) and the pack's Diet group data. The dev runs always have Diet.
 * Bread is Diet's built-in 4% grains.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class DietGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final float BREAD = 0.04f;

    @GameTest(template = SCRATCH)
    public static void dietIsLoadedWithThePacksGroups(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded(DietCompat.MOD_ID), "Diet should be in the dev runtime");
        List<String> names = DietGroups.getGroups(helper.getLevel()).stream().map(IDietGroup::getName).sorted().toList();
        helper.assertTrue(names.equals(List.of("fruits", "grains", "proteins", "sugars", "vegetables")), "groups: " + names);
        for (IDietGroup group : DietGroups.getGroups(helper.getLevel())) {
            helper.assertTrue(group.getDefaultValue() == 0.5f, group.getName() + " should start at 50%");
            List<String> effects = ((DietGroup) group).getEffects();
            // Only debuffs (0-25%) and the sugar peak's Hunger II are potion effects
            for (String line : effects) {
                helper.assertTrue(line.startsWith("0-25:") || !line.contains("effect(")
                                || line.equals("81-100:attribute(minecraft:generic.movement_speed,0.005),effect(minecraft:hunger,1)"),
                        group.getName() + " still has a potion buff: " + line);
            }
        }
        List<String> sugars = ((DietGroup) DietGroups.getGroup(helper.getLevel(), "sugars").orElseThrow()).getEffects();
        helper.assertTrue(sugars.stream().anyMatch(line -> line.startsWith("81-100") && line.contains("effect(minecraft:hunger,1)")),
                "sugar peak should give Hunger II: " + sugars);
        helper.succeed();
    }

    // No game test for the world's starting hunger decay: the game test server makes fresh gamerules on every start but
    // keeps the world's saved data, so after its first run the "already set" flag is there with Diet's default rule.
    // Checked by hand on a fresh world instead.

    /** Eating for real (use the item until it finishes), so Diet reads the stack NeoForge hands its event. */
    @GameTest(template = SCRATCH)
    public static void eatingScalesGainsByStage(GameTestHelper helper) {
        expectGain(helper, bread(1, times().freshTicks() / 20), BREAD * 1.25f);
        expectGain(helper, bread(1, times().freshTicks() + times().ripeTicks() / 2), BREAD);
        expectGain(helper, bread(1, times().freshTicks() + times().ripeTicks() + times().staleTicks() / 2), BREAD * 0.5f);
        expectGain(helper, bread(1, times().freshTicks() + times().ripeTicks() + times().staleTicks() * 2), 0f);
        helper.succeed();
    }

    /** A mixed stack: the gain is that of the group eaten (the top), not of what is left on top afterwards. */
    @GameTest(template = SCRATCH)
    public static void eatingAMixedStackUsesTheGroupEaten(GameTestHelper helper) {
        long now = SpoilClock.now();
        long staleAge = times().freshTicks() + times().ripeTicks() + times().staleTicks() / 2;
        ItemStack stack = new ItemStack(Items.BREAD, 10);
        Spoilage.write(stack, new FoodFreshness(List.of(new Cohort(now - 20, 1), new Cohort(now - staleAge, 9)), 0)
                .settle(now, times()));
        Player player = player(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        eat(player);
        helper.assertTrue(near(lastGain, BREAD * 1.25f), "the fresh loaf should add " + BREAD * 1.25f + ", added " + lastGain);
        int left = player.getMainHandItem().getCount();
        eat(player);
        helper.assertTrue(near(lastGain, BREAD * 0.5f), "then a stale loaf should add " + BREAD * 0.5f + ", added " + lastGain
                + " (loaves " + left + " -> " + player.getMainHandItem().getCount() + ", food " + player.getFoodData().getFoodLevel()
                + ", using " + player.isUsingItem() + ", hp " + player.getHealth() + ")");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void dietsTooltipShowsTheStagesValues(GameTestHelper helper) {
        Player player = player(helper);
        long staleAge = times().freshTicks() + times().ripeTicks() + times().staleTicks() / 2;
        Map<String, Float> shown = DietHookRegistry.modifyTooltipNutrition(bread(1, staleAge), player, Map.of("grains", BREAD));
        helper.assertTrue(near(shown.get("grains"), BREAD * 0.5f), "stale bread should show half: " + shown);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void dyingResetsToAQuarter(GameTestHelper helper) {
        Player player = player(helper);
        setAll(player, 0.9f);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(player, false));
        for (IDietGroup group : DietGroups.getGroups(helper.getLevel())) {
            float value = DietData.getValue(player, group.getName());
            helper.assertTrue(value == DietCompat.DEATH_VALUE, group.getName() + " should be 25% after dying, is " + value);
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void comingBackFromTheEndKeepsNutrition(GameTestHelper helper) {
        Player before = player(helper);
        setAll(before, 0.8f);
        Player after = player(helper);
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(after, before, false));
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(after, true));
        for (IDietGroup group : DietGroups.getGroups(helper.getLevel())) {
            float value = DietData.getValue(after, group.getName());
            helper.assertTrue(value == 0.8f, group.getName() + " should still be 80%, is " + value);
        }
        helper.succeed();
    }

    /** Diet tops groups at 0 up to 50% on login (meant for new players); an emptied group must stay empty. */
    @GameTest(template = SCRATCH)
    public static void loggingInDoesNotRefillEmptyGroups(GameTestHelper helper) {
        Player player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "diet_test"));
        setAll(player, 0.6f);
        DietData.setValue(player, "grains", 0f);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
        float grains = DietData.getValue(player, "grains");
        helper.assertTrue(grains < 1e-6f, "grains should stay empty, is " + grains);
        helper.assertTrue(DietData.getValue(player, "fruits") == 0.6f, "other groups untouched");
        helper.succeed();
    }

    // ---- helpers ----

    private static float lastGain;

    private static void expectGain(GameTestHelper helper, ItemStack bread, float expected) {
        Player player = player(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, bread);
        eat(player);
        helper.assertTrue(near(lastGain, expected), "bread should add " + expected + " grains, added " + lastGain);
    }

    /** Uses the main-hand item until it is eaten; returns (and remembers) the grains it added. */
    private static float eat(Player player) {
        // Set hunger first and let Diet see it, so the drop isn't counted as decay while eating
        player.getFoodData().setFoodLevel(0);
        player.tick();
        float before = DietData.getValue(player, "grains");
        player.startUsingItem(InteractionHand.MAIN_HAND);
        for (int tick = 0; tick < 100 && player.isUsingItem(); tick++) {
            player.tick();
        }
        lastGain = DietData.getValue(player, "grains") - before;
        return lastGain;
    }

    private static Player player(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().clearContent();
        // The eating tests tick the player: keep it from falling, suffocating or starving (damage is also Diet decay)
        player.setNoGravity(true);
        player.setInvulnerable(true);
        player.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 3, 1.5)));
        setAll(player, 0.5f);
        return player;
    }

    private static void setAll(Player player, float value) {
        for (IDietGroup group : DietGroups.getGroups(player.level())) {
            DietData.setValue(player, group.getName(), value);
        }
    }

    private static ItemStack bread(int count, long age) {
        ItemStack stack = new ItemStack(Items.BREAD, count);
        Spoilage.write(stack, FoodFreshness.born(SpoilClock.now() - age, count));
        return stack;
    }

    private static SpoilTimes times() {
        return SpoilageConfig.times();
    }

    private static boolean near(Float actual, float expected) {
        return actual != null && Math.abs(actual - expected) < 1e-4f;
    }

    private DietGameTests() {
    }
}
